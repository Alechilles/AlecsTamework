package com.alechilles.alecstamework.api.commandhud;

/** Stable reasons for closing a detached command HUD session. */
public enum CommandHudCloseReason {
    TARGET_LOST,
    TARGET_CHANGED,
    TOOL_CHANGED,
    WORLD_TRANSFER,
    PLAYER_UNLOADED,
    STORE_REMOVED,
    CONFIG_CHANGED,
    RENDERER_FAILED,
    RENDERER_UNREGISTERED,
    CONTRIBUTOR_UNREGISTERED,
    INVALID_REGISTRATION,
    SHUTDOWN,
    CLOSED
}
