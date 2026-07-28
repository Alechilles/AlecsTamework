package com.alechilles.alecstamework.persistence.projection;

/** Idempotent consumer outcome; every variant is safe to durably acknowledge. */
public enum ProjectionApplyOutcome {
    APPLIED,
    ALREADY_APPLIED,
    IRRELEVANT
}
