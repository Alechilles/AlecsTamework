package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import javax.annotation.Nullable;

/** Mirrors legacy death snapshots into profiles when the durable death repository is unavailable. */
final class CommandLinkedNpcDeathProfileWriter {
    @Nullable
    private final NpcProfileRepository profileRepository;
    private final boolean legacyFallbackEnabled;

    CommandLinkedNpcDeathProfileWriter(@Nullable NpcProfileRepository profileRepository,
                                       boolean legacyFallbackEnabled) {
        this.profileRepository = profileRepository;
        this.legacyFallbackEnabled = legacyFallbackEnabled;
    }

    void enqueue(@Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
                 @Nullable String coopId,
                 @Nullable Integer coopSlot) {
        if (!legacyFallbackEnabled
                || profileRepository == null
                || snapshot == null
                || snapshot.npcUuid() == null) {
            return;
        }
        profileRepository.upsertSnapshotAsync(new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                snapshot.ownerId(),
                snapshot.ownerName(),
                snapshot.roleId(),
                snapshot.displayName(),
                snapshot.customName(),
                snapshot.tamed(),
                coopId,
                coopSlot,
                null,
                snapshot.toolIds()
        ));
    }
}
