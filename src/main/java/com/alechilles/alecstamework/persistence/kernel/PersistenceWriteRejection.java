package com.alechilles.alecstamework.persistence.kernel;

/** Exact reason a replacement writer did not accept an operation. */
public enum PersistenceWriteRejection {
    CANCELLED_BEFORE_ACCEPTANCE,
    SATURATED,
    DRAINING,
    CLOSED
}
