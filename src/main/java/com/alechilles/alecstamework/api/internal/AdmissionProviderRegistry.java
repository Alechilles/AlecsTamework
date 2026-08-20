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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe provider registry with bounded, fail-closed evaluation. */
public final class AdmissionProviderRegistry implements AdmissionProviderApi, AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_CALLBACK_WORKERS = 4;

    private final Duration timeout;
    private final ExecutorService callbacks;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean closed = new AtomicBoolean();
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
        this.callbacks = Executors.newFixedThreadPool(
                MAX_CALLBACK_WORKERS,
                daemonFactory("tamework-admission-provider")
        );
        this.timers = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("tamework-admission-timeout")
        );
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
        if (closed.get()) {
            throw new IllegalStateException("Admission provider registry is closed");
        }
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
        if (registration == null || !registration.active.get() || closed.get()) {
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
        registrations.forEach((id, registration) -> {
            if (registration.active.get() && !closed.get()) {
                result.put(id, new ProviderReadiness(
                        id, registration.contractVersion,
                        registration.generationToken, true, "ready"
                ));
            }
        });
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
        if (closed.get() || registration == null || !registration.active.get() || request == null
                || !id.equals(canonical(request.providerId()))
                || request.contractVersion() != registration.contractVersion) {
            return CompletableFuture.completedFuture(
                    unavailable("provider-not-ready")
            );
        }
        CompletableFuture<PopulationAdmissionProviderDecision> result =
                new CompletableFuture<>();
        java.util.concurrent.ScheduledFuture<?> timeoutTask = timers.schedule(
                () -> result.complete(unavailable("provider-timeout")),
                timeout.toNanos(),
                java.util.concurrent.TimeUnit.NANOSECONDS
        );
        try {
            callbacks.execute(() -> invoke(
                    registration,
                    request,
                    result,
                    timeoutTask
            ));
        } catch (Throwable failure) {
            timeoutTask.cancel(false);
            result.complete(unavailable("provider-exception"));
        }
        return result;
    }

    private void invoke(
            Registration registration,
            PopulationAdmissionProviderRequest request,
            CompletableFuture<PopulationAdmissionProviderDecision> result,
            java.util.concurrent.ScheduledFuture<?> timeoutTask
    ) {
        if (result.isDone() || !registration.active.get() || closed.get()) {
            timeoutTask.cancel(false);
            result.complete(unavailable("provider-closed"));
            return;
        }
        CompletionStage<PopulationAdmissionProviderDecision> stage;
        try {
            stage = registration.provider.evaluate(request);
        } catch (Throwable failure) {
            timeoutTask.cancel(false);
            result.complete(unavailable("provider-exception"));
            return;
        }
        if (stage == null) {
            timeoutTask.cancel(false);
            result.complete(unavailable("provider-null-stage"));
            return;
        }
        try {
            stage.whenComplete((decision, failure) -> {
                timeoutTask.cancel(false);
                if (!registration.active.get() || closed.get()) {
                    result.complete(unavailable("provider-closed"));
                } else if (failure != null) {
                    result.complete(unavailable("provider-exception"));
                } else if (decision == null) {
                    result.complete(unavailable("provider-null-decision"));
                } else {
                    result.complete(decision);
                }
            });
        } catch (Throwable failure) {
            timeoutTask.cancel(false);
            result.complete(unavailable("provider-exception"));
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            registrations.values().forEach(registration -> registration.active.set(false));
            registrations.clear();
            callbacks.shutdownNow();
            timers.shutdownNow();
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegistrationHandle(String id, Registration registration) {
            this.id = id;
            this.registration = registration;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registration.active.set(false);
                registrations.remove(id, registration);
            }
        }
    }

    private static final class Registration {
        private final String id;
        private final int contractVersion;
        private final PopulationAdmissionProvider provider;
        private final String generationToken;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(
                String id,
                int contractVersion,
                PopulationAdmissionProvider provider,
                String generationToken
        ) {
            this.id = id;
            this.contractVersion = contractVersion;
            this.provider = provider;
            this.generationToken = generationToken;
        }
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
