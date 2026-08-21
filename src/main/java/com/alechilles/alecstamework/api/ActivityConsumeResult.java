package com.alechilles.alecstamework.api;

/** Optional result returned after a consumer handles one live activity. */
public enum ActivityConsumeResult {
    /** The consumer applied the activity. */
    APPLIED,
    /** The consumer already handled this activity. */
    DUPLICATE,
    /** The consumer could not handle this activity. */
    RETRY
}
