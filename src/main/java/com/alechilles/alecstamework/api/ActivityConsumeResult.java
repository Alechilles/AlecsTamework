package com.alechilles.alecstamework.api;

/** Result returned after a consumer handles one durable successful activity. */
public enum ActivityConsumeResult {
    /** The consumer applied the activity and can advance its checkpoint. */
    APPLIED,
    /** The consumer already persisted this activity and can advance its checkpoint. */
    DUPLICATE,
    /** The consumer could not persist the activity and needs another delivery. */
    RETRY
}
