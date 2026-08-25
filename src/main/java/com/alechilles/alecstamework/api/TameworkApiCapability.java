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
    /**
     * @deprecated Use Activity API V2 outcomes for new integrations. The capability remains
     * available for released legacy XP subscribers.
     */
    @Deprecated
    COMPANION_XP_EVENTS,
    CONFIG_READ,
    DIAGNOSTICS,
    CAPTURE_POLICY,
    /** Durable revision-fenced profile-data mutations and restart-visible operation queries. */
    PROFILE_DATA_TRANSACTIONS,
    PERSISTENCE_RESILIENCE,
    POPULATION_GROUPS,
    /** Process-local loaded-owner counts filtered by population groups. */
    LOADED_POPULATION_GROUP_COUNTS,
    /** Durable owned-profile counts filtered by population groups. */
    DURABLE_POPULATION_GROUP_COUNTS,
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
    /** Revival activities include managed role and group attribution when available. */
    REVIVAL_ACTIVITY_CONTEXT,
    /** Durable companion output operations and pending-output claims. */
    DURABLE_OUTPUT_OPERATIONS,
    /** Named, weighted population-capacity reservations. */
    NAMED_CAPACITY_RESERVATIONS,
    /** External admission-policy provider registration and evaluation. */
    EXTERNAL_ADMISSION_PROVIDERS,
    /** Validated managed-content profiles and readiness state. */
    REQUIRED_CONTENT_PROFILES,
    /** External command UI renderers can register with the live runtime. */
    COMMAND_UI_RENDERERS,
    /** External command UI contributors can register with the live runtime. */
    COMMAND_UI_CONTRIBUTORS,
    /** Command UI sessions can issue Tamework-defined action handles. */
    COMMAND_UI_CUSTOM_ACTIONS,
    /** Command UI sessions can host Tamework-managed custom flows. */
    COMMAND_UI_CUSTOM_FLOWS
}

