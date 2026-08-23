package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable snapshot delivery passed to a provider update callback. */
public final class CommandUiUpdate {
    private final CommandUiSnapshot snapshot;
    @Nullable
    private final CommandUiSnapshot previousSnapshot;
    private final CommandUiChangeSet changeSet;

    public CommandUiUpdate(
            @Nonnull CommandUiSnapshot snapshot,
            @Nonnull CommandUiChangeSet changeSet
    ) {
        this(snapshot, null, changeSet);
    }

    public CommandUiUpdate(
            @Nonnull CommandUiSnapshot snapshot,
            @Nullable CommandUiSnapshot previousSnapshot,
            @Nonnull CommandUiChangeSet changeSet
    ) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.previousSnapshot = previousSnapshot;
        this.changeSet = Objects.requireNonNull(changeSet, "changeSet");
    }

    /** Creates an initial full update. */
    @Nonnull
    public static CommandUiUpdate initial(@Nonnull CommandUiSnapshot snapshot) {
        return new CommandUiUpdate(snapshot, null, CommandUiChangeSet.full());
    }

    @Nonnull
    public CommandUiSnapshot snapshot() {
        return snapshot;
    }

    @Nullable
    public CommandUiSnapshot previousSnapshot() {
        return previousSnapshot;
    }

    @Nonnull
    public CommandUiChangeSet changeSet() {
        return changeSet;
    }

    public boolean fullRefresh() {
        return changeSet.fullRefresh();
    }
}
