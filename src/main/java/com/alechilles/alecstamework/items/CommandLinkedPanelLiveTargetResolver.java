package com.alechilles.alecstamework.items;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves a stale linked-panel record to its canonical live projection after direct lookup misses.
 *
 * <p>The direct UUID remains the fast path in the panel entry service. This collaborator performs
 * the persistence-backed profile lookup only for a miss, and fails closed when profile identity is
 * blocked, ambiguous, or unreadable.</p>
 */
final class CommandLinkedPanelLiveTargetResolver {
    private final CommandNpcProfileActionResolver profileActionResolver;

    CommandLinkedPanelLiveTargetResolver(
            @Nonnull CommandNpcProfileActionResolver profileActionResolver) {
        this.profileActionResolver = Objects.requireNonNull(
                profileActionResolver, "profileActionResolver");
    }

    /** Returns a distinct canonical record to probe, or {@code null} when no safe redirect exists. */
    @Nullable
    LinkedNpcRecord resolveRedirect(@Nullable LinkedNpcRecord historicalRecord) {
        if (historicalRecord == null || historicalRecord.npcUuid == null) {
            return null;
        }
        CommandNpcProfileActionResolver.ActionTarget target =
                profileActionResolver.resolveRelocation(historicalRecord);
        if (!target.isActionable() || !target.redirected()) {
            return null;
        }
        return target.resolvedRecord();
    }
}
