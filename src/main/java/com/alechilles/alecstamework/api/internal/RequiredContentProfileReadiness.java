package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.RequiredContentProfileApi;
import com.alechilles.alecstamework.api.RequiredContentProfileStatus;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Readiness of a managed-content profile: its config must be loaded and usable, and the admission
 * provider it names must be registered with the contract version the config asks for. A profile
 * that is not ready reports why in {@code detail}.
 */
public final class RequiredContentProfileReadiness implements RequiredContentProfileApi {
    private final Function<String, ManagedActivityConfigRegistry.Readiness> managed;
    private final AdmissionProviderRegistry providers;

    /** @param managed the config readiness of a profile id, {@code ManagedActivityConfigRegistry::readiness} */
    public RequiredContentProfileReadiness(
            @Nonnull Function<String, ManagedActivityConfigRegistry.Readiness> managed,
            @Nonnull AdmissionProviderRegistry providers) {
        this.managed = Objects.requireNonNull(managed, "managed");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    @Nonnull
    public RequiredContentProfileStatus status(@Nonnull String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        ManagedActivityConfigRegistry.Readiness readiness = managed.apply(profileId);
        AdmissionProviderRegistry.ProviderReadiness provider = readiness.providerId().isBlank()
                ? null
                : providers.readiness(readiness.providerId(), readiness.providerContractVersion());
        boolean providerAvailable = provider != null && provider.available();
        String detail = !readiness.available() || providerAvailable
                ? readiness.detail()
                : provider == null ? "provider-not-registered" : provider.detail();
        return new RequiredContentProfileStatus(
                readiness.profileId().isBlank() ? profileId : readiness.profileId(),
                readiness.available() && providerAvailable,
                readiness.providerId(),
                readiness.providerContractVersion(),
                readiness.configRevision(),
                detail);
    }
}
