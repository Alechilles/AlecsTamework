package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Bounded operational diagnostics for the capture channel and durable capture flow. */
final class SpawnerCaptureOperationalLog {
    private final HytaleLogger logger;

    SpawnerCaptureOperationalLog(HytaleLogger logger) {
        this.logger = logger;
    }

    void capture(String message) {
        logger.at(Level.INFO).log("Spawner capture flow: " + message);
    }

    void channelBeginRuntimeDenied(UUID playerUuid, ItemStack source) {
        capture("channel-begin status=denied reason=runtime-context-unavailable"
                + " player=" + playerUuid + " item=" + itemId(source));
    }

    void channelBeginSessionDenied(
            @Nullable UUID playerUuid,
            @Nullable UUID targetUuid,
            ItemStack source,
            @Nullable CaptureAttemptHandle attempt) {
        capture("channel-begin status=denied reason=identity-or-vfx-session-unavailable"
                + " player=" + playerUuid + " target=" + targetUuid
                + " item=" + itemId(source)
                + " attempt=" + (attempt == null ? null : attempt.attemptId()));
    }

    void channelBeginAccepted(
            UUID playerUuid, UUID targetUuid, ItemStack source, CaptureAttemptHandle attempt) {
        capture("channel-begin status=accepted attempt=" + attempt.attemptId()
                + " player=" + playerUuid + " target=" + targetUuid
                + " item=" + itemId(source));
    }

    void channelCompleteMissing(@Nullable UUID playerUuid, @Nullable ItemStack source) {
        capture("channel-complete status=denied reason=missing-channel-attempt-identity"
                + " player=" + playerUuid + " item=" + itemId(source));
    }

    void channelComplete(
            boolean scheduled,
            @Nullable UUID playerUuid,
            @Nullable ItemStack source,
            CaptureAttemptHandle attempt,
            @Nullable Ref<EntityStore> targetRef) {
        capture("channel-complete status=" + (scheduled ? "scheduled" : "denied")
                + " attempt=" + attempt.attemptId() + " player=" + playerUuid
                + " item=" + itemId(source)
                + " targetValid=" + (targetRef != null && targetRef.isValid()));
    }

    private static String itemId(@Nullable ItemStack source) {
        return source == null ? null : source.getItemId();
    }
}
