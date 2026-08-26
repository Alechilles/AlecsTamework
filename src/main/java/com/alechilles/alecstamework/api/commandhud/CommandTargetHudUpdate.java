package com.alechilles.alecstamework.api.commandhud;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable target-HUD update containing the complete current view. */
public final class CommandTargetHudUpdate {
    @Nonnull
    private final CommandTargetHudView view;
    @Nullable
    private final CommandTargetHudView previousView;
    @Nonnull
    private final CommandTargetHudChangeSet changeSet;

    /** Creates an update without a previous view. */
    public CommandTargetHudUpdate(
            @Nonnull CommandTargetHudView view,
            @Nonnull CommandTargetHudChangeSet changeSet
    ) {
        this(view, null, changeSet);
    }

    /** Creates an update with optional previous state for diff-aware renderers. */
    public CommandTargetHudUpdate(
            @Nonnull CommandTargetHudView view,
            @Nullable CommandTargetHudView previousView,
            @Nonnull CommandTargetHudChangeSet changeSet
    ) {
        this.view = Objects.requireNonNull(view, "view");
        this.previousView = previousView;
        this.changeSet = Objects.requireNonNull(changeSet, "changeSet");
    }

    /** Creates an initial full update. */
    @Nonnull
    public static CommandTargetHudUpdate initial(@Nonnull CommandTargetHudView view) {
        return new CommandTargetHudUpdate(view, null, CommandTargetHudChangeSet.full());
    }

    @Nonnull
    public CommandTargetHudView view() {
        return view;
    }

    @Nonnull
    public CommandTargetHudView currentView() {
        return view;
    }

    @Nonnull
    public CommandTargetHudSnapshot snapshot() {
        return view.snapshot();
    }

    @Nullable
    public CommandTargetHudView previousView() {
        return previousView;
    }

    @Nonnull
    public CommandTargetHudChangeSet changeSet() {
        return changeSet;
    }

    public boolean fullRefresh() {
        return changeSet.fullRefresh();
    }
}
