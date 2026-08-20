package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.AdmissionProviderApi;
import com.alechilles.alecstamework.api.PopulationAdmissionProvider;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe provider registry with bounded, fail-closed evaluation. */
public final class AdmissionProviderRegistry implements AdmissionProviderApi {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private final Duration timeout;
    private final ConcurrentMap<String, Registration> registrations =
            new ConcurrentHashMap<>();

    public AdmissionProviderRegistry() {
        this(DEFAULT_TIMEOUT);
    }

    public AdmissionProviderRegistry(@Nonnull Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Provider timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    @Nonnull
    public AutoCloseable register(
            @Nonnull String providerId,
            int contractVersion,
            @Nonnull PopulationAdmissionProvider provider
    ) {
        String id = canonical(providerId);
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("contractVersion must be positive");
        }
        Objects.requireNonNull(provider, "provider");
        Registration registration = new Registration(
                id,
                contractVersion,
                provider,
                UUID.randomUUID().toString()
        );
        Registration existing = registrations.putIfAbsent(id, registration);
        if (existing != null) {
            throw new IllegalStateException("Provider is already registered: " + id);
        }
        return new RegistrationHandle(id, registration);
    }

    /** Returns immutable readiness and generation evidence for one provider. */
    @Nonnull
    public ProviderReadiness readiness(
            @Nonnull String providerId,
            int contractVersion
    ) {
        String id = canonical(providerId);
        Registration registration = registrations.get(id);
        if (registration == null) {
            return ProviderReadiness.unavailable(id, contractVersion, "provider-not-registered");
        }
        if (registration.contractVersion != contractVersion) {
            return ProviderReadiness.unavailable(
                    id,
                    contractVersion,
                    "provider-contract-mismatch"
            );
        }
        return new ProviderReadiness(
                id,
                registration.contractVersion,
                registration.generationToken,
                true,
                "ready"
        );
    }

    /** Returns all active provider snapshots without exposing callback objects. */
    @Nonnull
    public Map<String, ProviderReadiness> snapshot() {
        java.util.LinkedHashMap<String, ProviderReadiness> result =
                new java.util.LinkedHashMap<>();
        registrations.forEach((id, registration) -> result.put(
                id,
                new ProviderReadiness(
                        id,
                        registration.contractVersion,
                        registration.generationToken,
                        true,
                        "ready"
                )
        ));
        return Map.copyOf(result);
    }

    /** Evaluates a request outside Tamework's SQLite writer transaction. */
    @Nonnull
    public CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull PopulationAdmissionProviderRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return evaluate(request.providerId(), request);
    }

    /** Evaluates under an explicit provider identity, rejecting mismatches. */
    @Nonnull
    public CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull String providerId,
            @Nullable PopulationAdmissionProviderRequest request
    ) {
        String id = canonical(providerId);
        Registration registration = registrations.get(id);
        if (registration == null || request == null
                || !id.equals(canonical(request.providerId()))
                || request.contractVersion() != registration.contractVersion) {
            return CompletableFuture.completedFuture(
                    unavailable("provider-not-ready")
            );
        }
        CompletionStage<PopulationAdmissionProviderDecision> stage;
        try {
            stage = registration.provider.evaluate(request);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(
                    unavailable("provider-exception")
            );
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(
                    unavailable("provider-null-stage")
            );
        }
        CompletableFuture<PopulationAdmissionProviderDecision> bounded;
        try {
            bounded = stage.toCompletableFuture()
                    .exceptionally(failure -> unavailable("provider-exception"));
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(
                    unavailable("provider-exception")
            );
        }
        return bounded.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                .exceptionally(failure -> unavailable(
                        failure instanceof java.util.concurrent.TimeoutException
                                ? "provider-timeout"
                                : "provider-exception"
                ))
                .thenApply(decision -> decision == null
                        ? unavailable("provider-null-decision")
                        : decision.status() == PopulationAdmissionProviderStatus.UNAVAILABLE
                                ? decision
                                : decision);
    }

    private static PopulationAdmissionProviderDecision unavailable(String reason) {
        return PopulationAdmissionProviderDecision.unavailable(reason);
    }

    private static String canonical(String providerId) {
        String normalized = Objects.requireNonNull(providerId, "providerId")
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        return normalized;
    }

    private final class RegistrationHandle implements AutoCloseable {
        private final String id;
        private final Registration registration;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private RegistrationHandle(String id, Registration registration) {
            this.id = id;
            this.registration = registration;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registrations.remove(id, registration);
            }
        }
    }

    private record Registration(
            String id,
            int contractVersion,
            PopulationAdmissionProvider provider,
            String generationToken
    ) {
    }

    /** Immutable provider registration evidence frozen into admission tokens. */
    public record ProviderReadiness(
            @Nonnull String providerId,
            int contractVersion,
            @Nonnull String generationToken,
            boolean available,
            @Nonnull String detail
    ) {
        public ProviderReadiness {
            providerId = canonical(providerId);
            if (contractVersion < 0 || generationToken == null
                    || generationToken.isBlank() || detail == null
                    || detail.isBlank()) {
                throw new IllegalArgumentException("Complete provider readiness is required");
            }
            generationToken = generationToken.trim();
            detail = detail.trim();
        }

        private static ProviderReadiness unavailable(
                String providerId,
                int contractVersion,
                String detail
        ) {
            return new ProviderReadiness(
                    providerId,
                    Math.max(0, contractVersion),
                    "unavailable",
                    false,
                    detail
            );
        }
    }
}
