package com.alechilles.alecstamework.api;

/** Stable outcome states returned by an external admission-policy provider. */
public enum PopulationAdmissionProviderStatus {
    /** The provider permits the requested admission. */
    ALLOW,
    /** The provider rejects the requested admission. */
    DENY,
    /** The provider cannot make a safe decision from its current snapshot. */
    UNAVAILABLE
}
