package com.alechilles.alecstamework.api.commandui;

/**
 * Describes how an action result changes the active command UI flow.
 */
public enum CommandUiFlowOperation {
    /** Opens a flow when the session is on its retained main snapshot. */
    OPEN,

    /** Replaces the current flow with a new revision. */
    REPLACE,

    /** Updates the current flow without changing its instance. */
    UPDATE,

    /** Closes the active flow and returns to the retained main snapshot. */
    CLOSE
}
