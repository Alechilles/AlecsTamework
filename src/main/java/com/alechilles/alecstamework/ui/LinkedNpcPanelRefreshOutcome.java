package com.alechilles.alecstamework.ui;

/** Result of evaluating one linked-panel refresh packet. */
record LinkedNpcPanelRefreshOutcome(boolean sent, boolean progressionIncluded,
                                   long shortestCountdownRemainingMs) {
    static LinkedNpcPanelRefreshOutcome sent(boolean progressionIncluded, long countdown) {
        return new LinkedNpcPanelRefreshOutcome(true, progressionIncluded, countdown);
    }

    static LinkedNpcPanelRefreshOutcome notSent(long countdown) {
        return new LinkedNpcPanelRefreshOutcome(false, false, countdown);
    }

    static LinkedNpcPanelRefreshOutcome evaluated(boolean progressionIncluded, long countdown) {
        return new LinkedNpcPanelRefreshOutcome(false, progressionIncluded, countdown);
    }
}
