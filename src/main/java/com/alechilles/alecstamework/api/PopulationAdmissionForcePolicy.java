package com.alechilles.alecstamework.api;

/** Explicitly distinguishes normal enforcement from narrowly authorized forced transitions. */
public enum PopulationAdmissionForcePolicy {
    ENFORCE,
    ADMIN_OVERRIDE,
    ENGINE_RELOCATION
}
