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
    POPULATION_GROUPS,
    /** Exact source decrement after either terminal capture roll. */
    CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION,
    /** Durable revision-fenced profile-data mutations and restart-visible operation queries. */
    PROFILE_DATA_TRANSACTIONS
}

