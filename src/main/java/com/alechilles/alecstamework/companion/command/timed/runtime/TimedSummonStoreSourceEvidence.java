package com.alechilles.alecstamework.companion.command.timed.runtime;

import java.util.UUID;
import javax.annotation.Nullable;

/** Evaluates the exact evidence required before a timed companion can be stored. */
final class TimedSummonStoreSourceEvidence {
    private TimedSummonStoreSourceEvidence() {
    }

    /** Returns the first failed proof label, or {@code null} when all evidence is exact. */
    @Nullable
    static String mismatch(
            UUID expectedAlias,
            @Nullable UUID entityUuid,
            @Nullable UUID npcUuid,
            @Nullable UUID snapshotUuid,
            @Nullable String snapshotRoleId,
            @Nullable String liveRoleId
    ) {
        if (expectedAlias == null || !expectedAlias.equals(entityUuid)) {
            return "entity-uuid";
        }
        if (!expectedAlias.equals(npcUuid)) {
            return "npc-uuid";
        }
        if (!expectedAlias.equals(snapshotUuid)) {
            return "snapshot-uuid";
        }
        return HytaleTimedSummonStoreGateway.sameRole(
                snapshotRoleId, liveRoleId
        ) ? null : "role";
    }
}
