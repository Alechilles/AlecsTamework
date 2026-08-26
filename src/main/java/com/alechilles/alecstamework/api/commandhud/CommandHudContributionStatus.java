package com.alechilles.alecstamework.api.commandhud;

/** Outcome of one contributor composition attempt. */
public enum CommandHudContributionStatus {
    /** The contributor returned valid, available detached data. */
    AVAILABLE,
    /** The contributor is not currently available but did not fail the HUD. */
    UNAVAILABLE,
    /** The contributor callback failed and its data was discarded. */
    FAILED,
    /** The selected renderer cannot display this contributor namespace. */
    UNSUPPORTED_BY_RENDERER
}
