package com.alechilles.alecstamework.ui;

import java.util.Objects;

/**
 * Immutable reason for refreshing an open linked panel.
 *
 * @param kind refresh reason
 */
public record LinkedPanelRefreshSignal(Kind kind) {

    /**
     * Validates the refresh reason.
     *
     * @param kind refresh reason
     */
    public LinkedPanelRefreshSignal {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * The supported linked-panel refresh priorities.
     */
    public enum Kind {
        IMMEDIATE,
        PROGRESSION
    }
}
