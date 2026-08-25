package com.alechilles.alecstamework.api.commandhud;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable hotswap update containing the complete current view. */
public final class CommandHotswapHudUpdate {
    @Nonnull
    private final CommandHotswapHudView view;
    @Nullable
    private final CommandHotswapHudView previousView;
    @Nonnull
    private final CommandHotswapHudChangeSet changeSet;

    /** Creates an update without a previous view. */
    public CommandHotswapHudUpdate(
            @Nonnull CommandHotswapHudView view,
            @Nonnull CommandHotswapHudChangeSet changeSet
    ) {
        this(view, null, changeSet);
    }

    /** Creates an update with optional previous state for diff-aware renderers. */
    public CommandHotswapHudUpdate(
            @Nonnull CommandHotswapHudView view,
            @Nullable CommandHotswapHudView previousView,
            @Nonnull CommandHotswapHudChangeSet changeSet
    ) {
        this.view = Objects.requireNonNull(view, "view");
        this.previousView = previousView;
        this.changeSet = Objects.requireNonNull(changeSet, "changeSet");
    }

    /** Creates an initial full update. */
    @Nonnull
    public static CommandHotswapHudUpdate initial(@Nonnull CommandHotswapHudView view) {
        return new CommandHotswapHudUpdate(view, null, CommandHotswapHudChangeSet.full());
    }

    @Nonnull
    public CommandHotswapHudView view() {
        return view;
    }

    @Nonnull
    public CommandHotswapHudView currentView() {
        return view;
    }

    @Nonnull
    public CommandHotswapHudSnapshot snapshot() {
        return view.snapshot();
    }

    @Nullable
    public CommandHotswapHudView previousView() {
        return previousView;
    }

    @Nonnull
    public CommandHotswapHudChangeSet changeSet() {
        return changeSet;
    }

    public boolean fullRefresh() {
        return changeSet.fullRefresh();
    }
}
