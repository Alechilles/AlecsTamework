package com.alechilles.alecstamework.api;

public enum TameworkApiCapability {
    PROFILES,
    COMMAND_LINKS,
    PROGRESSION,
    PROGRESSION_MUTATIONS,
    POLICY,
    INTERACTION_EXTENSIONS,
    TRAIT_EFFECTS,
    PROFILE_DATA,
    EVENTS,
    COMPANION_XP_EVENTS,
    CONFIG_READ,
    DIAGNOSTICS,
    PERSISTENCE_RESILIENCE,
    CAPTURE_POLICY,
    BONDED_VESSELS,
    POPULATION_GROUPS,
    COMPANION_PROVISIONING,
    /** Durable revision-fenced profile-data mutations and restart-visible operation queries. */
    PROFILE_DATA_TRANSACTIONS
}

