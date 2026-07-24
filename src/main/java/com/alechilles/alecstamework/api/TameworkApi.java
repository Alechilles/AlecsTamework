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

    /** Read-only population-group counts used by roster capacity presentation. */
    default PopulationGroupApi populationGroups() {
        return PopulationGroupApi.unavailable();
    }
}

