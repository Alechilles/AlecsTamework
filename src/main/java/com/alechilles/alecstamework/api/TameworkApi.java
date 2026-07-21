package com.alechilles.alecstamework.api;

import java.util.EnumSet;

public interface TameworkApi {
    String getApiVersion();

    EnumSet<TameworkApiCapability> getCapabilities();

    NpcProfilesApi profiles();

    CommandLinksApi commandLinks();

    ProgressionApi progression();

    PolicyApi policies();

    InteractionExtensionApi interactionExtensions();

    TraitEffectApi traitEffects();

    ProfileDataApi profileData();

    TameworkEventsApi events();

    TameworkConfigReadApi configs();

    DiagnosticsApi diagnostics();

    /**
     * Returns the mutation-bound bonded-vessel authority when advertised by
     * {@link TameworkApiCapability#BONDED_VESSELS}.
     */
    default BondedVesselsApi bondedVessels() {
        return BondedVesselsApi.unavailable();
    }

    /**
     * Returns the idempotent companion-provisioning authority when advertised by
     * {@link TameworkApiCapability#COMPANION_PROVISIONING}.
     */
    default CompanionProvisioningApi companionProvisioning() {
        return CompanionProvisioningApi.unavailable();
    }
}

