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
    CAPTURE_POLICY,
    /** Durable revision-fenced profile-data mutations and restart-visible operation queries. */
    PROFILE_DATA_TRANSACTIONS,
    PERSISTENCE_RESILIENCE,
    POPULATION_GROUPS,
    COMPANION_PROVISIONING,
    /** Durable command-roster leases, active-cap storage, expiry, and resummon cooldowns. */
    COMMAND_TIMED_SUMMONING,
    /** Data-driven, idempotent, exact multi-item command revival. */
    PAID_COMMAND_REVIVAL,
    /** Durable owner/command-family/profile roster authority. */
    COMMAND_FAMILY_ROSTERS,
    /** Exact source decrement after either terminal capture roll. */
    CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION,
    /** Successful capture can tame the live NPC and commit command-roster membership. */
    CAPTURE_TAME_AND_LINK,
    /** Separate canonical profile, lease, revive, and extension-data authority. */
    BONDED_COMPANIONS,
    /** Process-local, best-effort filtered Activity API V2 feed. */
    ACTIVITY_FEED_V2,
    /** Durable companion output operations and pending-output claims. */
    DURABLE_OUTPUT_OPERATIONS,
    /** Named, weighted population-capacity reservations. */
    NAMED_CAPACITY_RESERVATIONS,
    /** External admission-policy provider registration and evaluation. */
    EXTERNAL_ADMISSION_PROVIDERS,
    /** Validated managed-content profiles and readiness state. */
    REQUIRED_CONTENT_PROFILES
}

