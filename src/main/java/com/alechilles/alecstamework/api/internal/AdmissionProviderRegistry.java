package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.AdmissionProviderApi;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.SynchronousPopulationAdmissionProvider;
import com.alechilles.alecstamework.companion.runtime.ThrottledWarnings;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Thread-safe provider registry. {@link #decide} calls the provider inline on the caller's thread
 * and fails closed: a missing, mismatched, throwing or null-returning provider is UNAVAILABLE.
 */
public final class AdmissionProviderRegistry implements AdmissionProviderApi, AutoCloseable {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** A call slower than this is logged: providers run on world threads and under the index lock. */
    static final long SLOW_CALL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();
    /** Keyed by provider id, a small fixed set. */
    private final ThrottledWarnings warnings = new ThrottledWarnings(System::currentTimeMillis, 60_000L);

    @Override
    @Nonnull
    public AutoCloseable register(
            @Nonnull String providerId,
            int contractVersion,
            @Nonnull SynchronousPopulationAdmissionProvider provider
    ) {
        String id = canonical(providerId);
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("contractVersion must be positive");
        }
        Objects.requireNonNull(provider, "provider");
        if (closed.get()) {
            throw new IllegalStateException("Admission provider registry is closed");
        }
        Registration registration = new Registration(id, contractVersion, provider, UUID.randomUUID().toString());
        Registration existing = registrations.putIfAbsent(id, registration);
        if (existing != null) {
            throw new IllegalStateException("Provider is already registered: " + id);
        }
        if (closed.get()) {
            registration.active.set(false);
            registrations.remove(id, registration);
            throw new IllegalStateException("Admission provider registry is closed");
        }
        return new RegistrationHandle(id, registration);
    }

    /** Returns immutable readiness and generation evidence for one provider. */
    @Nonnull
    public ProviderReadiness readiness(@Nonnull String providerId, int contractVersion) {
        String id = canonical(providerId);
        Registration registration = registrations.get(id);
        if (registration == null || !registration.active.get() || closed.get()) {
            return ProviderReadiness.unavailable(id, contractVersion, "provider-not-registered");
        }
        if (registration.contractVersion != contractVersion) {
            return ProviderReadiness.unavailable(id, contractVersion, "provider-contract-mismatch");
        }
        return new ProviderReadiness(id, registration.contractVersion, registration.generationToken, true, "ready");
    }

    /** Returns all active provider snapshots without exposing callback objects. */
    @Nonnull
    public Map<String, ProviderReadiness> snapshot() {
        java.util.LinkedHashMap<String, ProviderReadiness> result = new java.util.LinkedHashMap<>();
        registrations.forEach((id, registration) -> {
            if (registration.active.get() && !closed.get()) {
                result.put(id, new ProviderReadiness(
                        id, registration.contractVersion, registration.generationToken, true, "ready"));
            }
        });
        return Map.copyOf(result);
    }

    /**
     * Asks the request's provider on this thread and returns its decision. UNAVAILABLE when the
     * provider is not registered, has another contract version, throws or returns null.
     */
    @Nonnull
    public PopulationAdmissionProviderDecision decide(@Nullable PopulationAdmissionProviderRequest request) {
        if (request == null || closed.get()) {
            return unavailable("provider-not-ready");
        }
        String id = canonical(request.providerId());
        Registration registration = registrations.get(id);
        if (registration == null || !registration.active.get()
                || request.contractVersion() != registration.contractVersion) {
            return unavailable("provider-not-ready");
        }
        long started = System.nanoTime();
        PopulationAdmissionProviderDecision decision;
        try {
            decision = registration.provider.decide(request);
        } catch (Throwable failure) {
            if (warnings.shouldLog("throw " + id)) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Admission provider %s failed; refused as unavailable",
                        id);
            }
            return unavailable("provider-exception");
        }
        long elapsed = System.nanoTime() - started;
        if (elapsed > SLOW_CALL_NANOS && warnings.shouldLog("slow " + id)) {
            LOGGER.at(Level.WARNING).log("Admission provider %s took %d ms to decide. Providers are called on world"
                    + " threads and must answer from memory.", id, TimeUnit.NANOSECONDS.toMillis(elapsed));
        }
        return decision == null ? unavailable("provider-null-decision") : decision;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            registrations.values().forEach(registration -> registration.active.set(false));
            registrations.clear();
        }
    }

    private static PopulationAdmissionProviderDecision unavailable(String reason) {
        return PopulationAdmissionProviderDecision.unavailable(reason);
    }

    private static String canonical(String providerId) {
        String normalized = Objects.requireNonNull(providerId, "providerId").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        return normalized;
    }

    /** Public so a consumer in another class loader can call {@code close} by reflection. */
    public final class RegistrationHandle implements AutoCloseable {
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
        private final SynchronousPopulationAdmissionProvider provider;
        private final String generationToken;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(String id, int contractVersion, SynchronousPopulationAdmissionProvider provider,
                             String generationToken) {
            this.id = id;
            this.contractVersion = contractVersion;
            this.provider = provider;
            this.generationToken = generationToken;
        }
    }

    /** Immutable provider registration evidence. */
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

        private static ProviderReadiness unavailable(String providerId, int contractVersion, String detail) {
            return new ProviderReadiness(providerId, Math.max(0, contractVersion), "unavailable", false, detail);
        }
    }
}
