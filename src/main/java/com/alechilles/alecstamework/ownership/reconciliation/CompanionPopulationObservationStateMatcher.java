package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compares the durable state shape of coalesced population observations. */
final class CompanionPopulationObservationStateMatcher {
    private CompanionPopulationObservationStateMatcher() {
    }

    static boolean matches(@Nullable CompanionPopulationObservation first,
                           @Nonnull CompanionPopulationObservation second) {
        return first != null
                && first.profileId().equals(second.profileId())
                && first.currentNpcUuid().equals(second.currentNpcUuid())
                && Objects.equals(first.ownerUuid(), second.ownerUuid())
                && Objects.equals(first.ownershipWorldName(), second.ownershipWorldName())
                && first.lifecycleState() == second.lifecycleState()
                && Objects.equals(first.physicalWorldName(), second.physicalWorldName())
                && Objects.equals(first.physicalChunkX(), second.physicalChunkX())
                && Objects.equals(first.physicalChunkZ(), second.physicalChunkZ());
    }
}
