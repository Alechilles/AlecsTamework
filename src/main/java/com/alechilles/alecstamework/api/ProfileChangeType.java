package com.alechilles.alecstamework.api;

public enum ProfileChangeType {
    CREATED,
    CURRENT_NPC_UUID,
    OWNER,
    ROLE,
    DISPLAY_NAME,
    CUSTOM_NAME,
    TAMED,
    COOP_ASSIGNMENT,
    TOOL_LINKS,
    ACTIVE_SNAPSHOTS,
    /** The companion moved between location kinds, for example {@code LIVE} to {@code STORED}. */
    LOCATION,
    /** The companion was released or culled and no longer counts for its owner. */
    RELEASED
}
