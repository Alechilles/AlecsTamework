package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Public registration surface for external population-admission policy providers. */
public interface AdmissionProviderApi {
    /**
     * Registers a provider and returns an idempotent handle for unregistering it. One provider
     * may be registered per id. Closing the handle stops new calls; a call already running
     * finishes. See {@link SynchronousPopulationAdmissionProvider} for the threading contract.
     *
     * @throws IllegalStateException when the id is already registered or Tamework is shutting down
     */
    @Nonnull
    AutoCloseable register(
            @Nonnull String providerId,
            int contractVersion,
            @Nonnull SynchronousPopulationAdmissionProvider provider
    );

    /** Returns a singleton registry that fails closed while preserving safe lifecycle calls. */
    @Nonnull
    static AdmissionProviderApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation for older and degraded API compositions. */
    final class UnavailableHolder {
        private static final AdmissionProviderApi INSTANCE = new AdmissionProviderApi() {
            @Override
            public AutoCloseable register(
                    String providerId,
                    int contractVersion,
                    SynchronousPopulationAdmissionProvider provider
            ) {
                requireText(providerId, "providerId");
                if (contractVersion <= 0) {
                    throw new IllegalArgumentException("contractVersion must be positive.");
                }
                Objects.requireNonNull(provider, "provider");
                return () -> {
                    // No runtime provider is registered in the unavailable facade.
                };
            }
        };

        private UnavailableHolder() {
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required.");
            }
            return normalized;
        }
    }
}
