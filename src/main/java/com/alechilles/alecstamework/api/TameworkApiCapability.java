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
    COMPANION_PROVISIONING,
    /** Durable command-roster leases, active-cap storage, expiry, and resummon cooldowns. */
    COMMAND_TIMED_SUMMONING,
    /** Durable owner/command-family/profile roster authority. */
    COMMAND_FAMILY_ROSTERS,
    /** Exact source decrement after either terminal capture roll. */
    CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION,
    /** Successful capture can tame the live NPC and commit command-roster membership. */
    CAPTURE_TAME_AND_LINK,
    /** Durable revision-fenced profile-data mutations and restart-visible operation queries. */
    PROFILE_DATA_TRANSACTIONS
}

