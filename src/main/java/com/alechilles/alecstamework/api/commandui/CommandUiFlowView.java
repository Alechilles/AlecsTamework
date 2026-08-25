package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/** Detached view for a Tamework-managed command UI subflow. */
public interface CommandUiFlowView {
    /** Returns the stable flow kind or namespaced custom flow type. */
    @Nonnull
    String kind();

    /** Returns whether this view uses the generic contributor flow envelope. */
    default boolean isCustom() {
        return false;
    }
}
