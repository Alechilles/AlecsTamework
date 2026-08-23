package com.alechilles.alecstamework.api.commandui;

/** Reasons a command UI session can leave the open state. */
public enum CommandUiCloseReason {
    /** The player dismissed or replaced the page. */
    DISMISSED,
    /** The provider registration generation was closed. */
    PROVIDER_UNREGISTERED,
    /** The player disconnected. */
    PLAYER_DISCONNECTED,
    /** The required command item or player authority was lost. */
    AUTHORITY_LOST,
    /** The command configuration generation changed. */
    CONFIG_INVALIDATED,
    /** The host or provider failed while the page was open. */
    FAILURE,
    /** Tamework is shutting down. */
    SHUTDOWN,
    /** The session was replaced by another page. */
    REPLACED,
    /** A caller closed the session without a more specific reason. */
    UNKNOWN
}
