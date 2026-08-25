package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/** Detached view for a Tamework-managed command UI subflow. */
public interface CommandUiFlowView {
    /** Returns the stable flow kind used for provider-side presentation. */
    @Nonnull
    String kind();
}
