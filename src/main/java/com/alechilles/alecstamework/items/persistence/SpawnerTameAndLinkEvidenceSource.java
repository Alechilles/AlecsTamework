package com.alechilles.alecstamework.items.persistence;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Supplies one complete authoritative snapshot for a successful tame/link capture intent.
 *
 * <p>Implementations run synchronously on the capture world thread and must not retain the input's
 * entity/store references. They must read canonical roster, lease, owner-population, and
 * group-population authorities. The intent factory never invents absent counts or revisions.</p>
 */
@FunctionalInterface
public interface SpawnerTameAndLinkEvidenceSource {

    /** Returns exact evidence, or null when every required authority cannot be proven. */
    @Nullable
    SpawnerTameAndLinkIntentEvidence freeze(
            @Nonnull SpawnerTameAndLinkIntentFactory.Input input
    );

    /**
     * Returns the bounded reason for the current-thread failed freeze, when the
     * implementation can provide one. This is diagnostic-only and never
     * participates in capture authorization.
     */
    @Nullable
    default String lastFailureReason() {
        return null;
    }

    /** Fail-closed source used until production composition supplies every authority. */
    @Nonnull
    static SpawnerTameAndLinkEvidenceSource unavailable() {
        return input -> null;
    }
}
