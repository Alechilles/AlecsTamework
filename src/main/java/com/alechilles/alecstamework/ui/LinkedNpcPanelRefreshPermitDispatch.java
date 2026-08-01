package com.alechilles.alecstamework.ui;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Completes an admitted refresh permit only when its world-thread dispatch is
 * rejected; accepted work owns its normal completion on the world thread.
 */
final class LinkedNpcPanelRefreshPermitDispatch {
    private LinkedNpcPanelRefreshPermitDispatch() {
    }

    static void dispatch(
            LinkedPanelRefreshCoordinator.RenderPermit permit,
            Admission dispatchAdmission,
            Runnable admittedWork,
            Consumer<LinkedPanelRefreshCoordinator.RenderPermit> rejectedCompletion
    ) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(dispatchAdmission, "dispatchAdmission");
        Objects.requireNonNull(admittedWork, "admittedWork");
        Objects.requireNonNull(rejectedCompletion, "rejectedCompletion");

        if (dispatchAdmission.dispatch(admittedWork)) {
            return;
        }
        rejectedCompletion.accept(permit);
    }

    @FunctionalInterface
    interface Admission {
        boolean dispatch(Runnable task);
    }
}
