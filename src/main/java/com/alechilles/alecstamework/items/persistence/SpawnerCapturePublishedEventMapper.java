package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.NpcCapturedEvent;
import com.alechilles.alecstamework.api.internal.CompanionProfileApiMapper;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import javax.annotation.Nonnull;

/** Maps canonical post-capture profile state and frozen facts to the released API event. */
public final class SpawnerCapturePublishedEventMapper {
    private SpawnerCapturePublishedEventMapper() {
    }

    @Nonnull
    public static NpcCapturedEvent map(
            @Nonnull CompanionProfileReadModel profile,
            @Nonnull SpawnerCapturePublishedEvidence evidence,
            long emittedAtMs
    ) {
        if (profile == null || evidence == null) {
            throw new IllegalArgumentException(
                    "Canonical profile and capture evidence are required"
            );
        }
        CompanionProfileProjectionState state =
                CompanionProfileProjectionState.compose(
                        profile.identity(),
                        profile.currentAlias(),
                        profile.lifecycle(),
                        profile.toolLinks(),
                        profile.currentSnapshots(),
                        profile.currentCoopSlot()
                );
        return new NpcCapturedEvent(
                CompanionProfileApiMapper.map(state),
                evidence.npcUuid(),
                evidence.ownerUuid(),
                evidence.toolIds(),
                evidence.roleId(),
                evidence.displayName(),
                null,
                evidence.homePosition(),
                evidence.capturedAtMs(),
                emittedAtMs
        );
    }
}
