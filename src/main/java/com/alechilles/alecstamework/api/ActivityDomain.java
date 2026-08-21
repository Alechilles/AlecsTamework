package com.alechilles.alecstamework.api;

/** Stable domains used to select Activity API V2 payloads. */
public enum ActivityDomain {
    /** Managed care and production actions such as feeding, harvest, and breeding. */
    MANAGED_CARE_PRODUCTION,
    /** Wild-to-tamed acquisition actions. */
    TAMING,
    /** Companion revival actions. */
    REVIVAL,
    /** Companion combat actions. */
    COMBAT,
    /** Accepted avatar-flight actions. */
    AVATAR_FLIGHT,
    /** Summoning and recall lifecycle actions. */
    SUMMONING;

}
