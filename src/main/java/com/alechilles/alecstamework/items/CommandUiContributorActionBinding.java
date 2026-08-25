package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Server-owned binding for one contributor action and registration generation. */
final class CommandUiContributorActionBinding {
    private final CommandUiContributorId contributorId;
    private final long contributorGeneration;
    private final CommandUiContributorAction action;
    private final CommandUiContributorAction.Scope scope;
    @Nullable
    private final UUID rowId;
    private final String effectiveId;

    CommandUiContributorActionBinding(
            @Nonnull CommandUiContributorId contributorId,
            long contributorGeneration,
            @Nonnull CommandUiContributorAction action,
            @Nonnull CommandUiContributorAction.Scope scope,
            @Nullable UUID rowId
    ) {
        this.contributorId = Objects.requireNonNull(contributorId,
                "contributorId");
        if (contributorGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Contributor generation cannot be negative.");
        }
        this.contributorGeneration = contributorGeneration;
        this.action = Objects.requireNonNull(action, "action");
        this.scope = Objects.requireNonNull(scope, "scope");
        if (scope == CommandUiContributorAction.Scope.ROW) {
            Objects.requireNonNull(rowId, "rowId");
        } else if (rowId != null) {
            throw new IllegalArgumentException(
                    "Only row-scoped contributor actions may have a row ID.");
        }
        this.rowId = rowId;
        this.effectiveId = action.effectiveId(contributorId);
    }

    @Nonnull
    CommandUiContributorId contributorId() {
        return contributorId;
    }

    long contributorGeneration() {
        return contributorGeneration;
    }

    @Nonnull
    CommandUiContributorAction action() {
        return action;
    }

    @Nonnull
    CommandUiContributorAction.Scope scope() {
        return scope;
    }

    @Nullable
    UUID rowId() {
        return rowId;
    }

    @Nonnull
    String effectiveId() {
        return effectiveId;
    }

    @Nonnull
    CommandUiContributorActionHandler handler() {
        return action.handler();
    }

    /** Builds only the detached view that may cross the renderer boundary. */
    @Nullable
    CommandUiActionView view(@Nullable CommandUiActionHandle handle) {
        return action.view(handle);
    }
}
