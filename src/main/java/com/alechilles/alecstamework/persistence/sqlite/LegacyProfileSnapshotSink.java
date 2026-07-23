package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CompanionProfileSnapshotSink;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Temporary legacy translation for live profile snapshot publication. */
public final class LegacyProfileSnapshotSink
        implements CompanionProfileSnapshotSink {
    private final NpcProfileRepository profiles;

    public LegacyProfileSnapshotSink(
            @Nonnull NpcProfileRepository profiles
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public void publish(
            @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        profiles.upsertSnapshotAsync(new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                snapshot.ownerId(),
                snapshot.ownerName(),
                snapshot.roleId(),
                snapshot.displayName(),
                snapshot.customName(),
                snapshot.tamed(),
                null,
                null,
                null,
                snapshot.toolIds()
        ));
    }
}
