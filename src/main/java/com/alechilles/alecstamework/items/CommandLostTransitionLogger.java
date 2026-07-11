package com.alechilles.alecstamework.items;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Focused admin logging for commit-visible lost transitions. */
final class CommandLostTransitionLogger {
    private CommandLostTransitionLogger() {
    }

    static void committed(@Nullable HytaleLogger logger,
                          @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
                          @Nullable UUID ownerUuid) {
        if (logger != null) {
            logger.at(Level.INFO).log(
                    "Marked linked companion as lost after relocation retries (npc="
                            + snapshot.npcUuid() + ", owner=" + ownerUuid
                            + ", retries=" + snapshot.relocationRetryAttempts() + ")."
            );
        }
    }

    static void rejected(@Nullable HytaleLogger logger,
                         @Nonnull UUID npcUuid,
                         @Nonnull CommandLostTransitionPersistenceService.PersistStatus status) {
        if (logger != null) {
            logger.at(Level.WARNING).log(
                    "Did not publish linked companion as lost because complete persistence failed (npc="
                            + npcUuid + ", status=" + status + ")."
            );
        }
    }

    static void cancelled(@Nullable HytaleLogger logger,
                          @Nonnull UUID npcUuid,
                          @Nonnull CommandLostTransitionPersistenceService.CancelStatus status) {
        if (logger != null
                && status == CommandLostTransitionPersistenceService.CancelStatus.COMPENSATION_REJECTED) {
            logger.at(Level.SEVERE).log(
                    "Failed to queue durable lost-transition cancellation (npc=" + npcUuid + ")."
            );
        }
    }
}
