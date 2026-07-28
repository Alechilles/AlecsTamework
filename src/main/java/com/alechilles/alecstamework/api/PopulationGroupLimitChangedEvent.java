package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Notification published after a valid group definition is reconciled and activated. */
public record PopulationGroupLimitChangedEvent(@Nonnull UUID operationId,
                                               @Nonnull String groupId,
                                               long oldConfigRevision,
                                               long newConfigRevision,
                                               long oldMaxOwned,
                                               long newMaxOwned,
                                               long oldMaxActive,
                                               long newMaxActive,
                                               @Nonnull PopulationGroupScope scope,
                                               boolean recovered,
                                               long changedAtMs,
                                               long emittedAtMs) implements TameworkEvent {
    public PopulationGroupLimitChangedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        groupId = Objects.requireNonNull(groupId, "groupId").trim();
        scope = Objects.requireNonNull(scope, "scope");
        if (groupId.isEmpty()) throw new IllegalArgumentException("groupId is required.");
        if (oldConfigRevision < 0L || newConfigRevision < oldConfigRevision
                || oldMaxOwned < 0L || newMaxOwned < 0L || oldMaxActive < 0L || newMaxActive < 0L) {
            throw new IllegalArgumentException("Population-group revisions and limits are invalid.");
        }
    }
}
