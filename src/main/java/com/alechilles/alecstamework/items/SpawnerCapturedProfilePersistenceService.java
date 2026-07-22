package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CaptureRepository;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Persists the canonical profile identity needed to summon a captured NPC later. */
final class SpawnerCapturedProfilePersistenceService {
    private final HytaleLogger logger;
    @Nullable
    private final CaptureRepository repository;

    SpawnerCapturedProfilePersistenceService(
            HytaleLogger logger,
            @Nullable CaptureRepository repository) {
        this.logger = logger;
        this.repository = repository;
    }

    boolean persist(
            @Nullable UUID npcUuid,
            @Nullable UUID ownerUuid,
            @Nullable String roleId,
            @Nullable String displayName) {
        if (repository == null || npcUuid == null || roleId == null || roleId.isBlank()) {
            return false;
        }
        boolean queued = repository.upsertAsync(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid, ownerUuid, new String[0], roleId, displayName,
                        null, null, System.currentTimeMillis()));
        if (!queued) {
            logger.at(Level.SEVERE).log(
                    "Spawner capture could not enqueue its canonical profile snapshot "
                            + "(npc=" + npcUuid + ", role=" + roleId + ").");
        }
        return queued;
    }
}
