package com.alechilles.alecstamework.api;

/** Explicitly distinguishes normal enforcement from narrowly authorized forced transitions. */
public enum PopulationAdmissionForcePolicy {
    /** Applies provider, owner, group, and domain limits. */
    ENFORCE,
    /** Bypasses admission limits but still records every resulting claim. */
    ADMIN_OVERRIDE,
    /** Allows an engine-owned relocation without changing ownership. */
    ENGINE_RELOCATION
}
