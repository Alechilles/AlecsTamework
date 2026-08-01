package com.alechilles.alecstamework.ui;

/**
 * Identifies the reason a linked-panel refresh was requested.
 */
public final class LinkedPanelRefreshSignal {

    private LinkedPanelRefreshSignal() {
    }

    /**
     * The supported linked-panel refresh priorities.
     */
    public enum Kind {
        IMMEDIATE,
        PROGRESSION
    }
}
