package com.alechilles.alecstamework.persistence.control;

/** Failure-containment policy declared once by a feature descriptor. */
public enum PersistenceCircuitPolicy {
    BOUNDED_SCOPE,
    GLOBAL_FAIL_CLOSED
}
