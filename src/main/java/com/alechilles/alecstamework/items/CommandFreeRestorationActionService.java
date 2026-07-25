package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Executes the legacy item-metadata free restoration path. */
final class CommandFreeRestorationActionService {
    @Nullable
    private final CommandCompanionRestorationService restoration;
    private final CommandLinkMutationService links;
    private final CommandFeedbackService feedback;
    private final double fallbackSafeSpawnDistance;

    CommandFreeRestorationActionService(
            @Nullable CommandCompanionRestorationService restoration,
            @Nonnull CommandLinkMutationService links,
            @Nonnull CommandFeedbackService feedback,
            double fallbackSafeSpawnDistance
    ) {
        this.restoration = restoration;
        this.links = Objects.requireNonNull(links, "Links are required");
        this.feedback = Objects.requireNonNull(
                feedback, "Feedback is required"
        );
        this.fallbackSafeSpawnDistance = fallbackSafeSpawnDistance;
    }

    void request(
            @Nullable Player player,
            @Nullable String toolId,
            @Nullable UUID npcUuid
    ) {
        if (player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
            return;
        }
        if (restoration == null) {
            feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.trackingUnavailable"
            );
            return;
        }
        Context context = context(player);
        if (context == null) {
            feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.unavailable"
            );
            return;
        }
        restore(player, toolId, npcUuid, context);
    }

    @Nullable
    private Context context(Player player) {
        Inventory inventory = player.getInventory();
        World world = player.getWorld();
        Ref<EntityStore> playerRef = player.getReference();
        Store<EntityStore> store = world == null
                ? null
                : world.getEntityStore().getStore();
        ItemContainer hotbar = inventory == null
                ? null
                : inventory.getHotbar();
        if (hotbar == null || store == null || playerRef == null
                || !playerRef.isValid()) {
            return null;
        }
        return new Context(hotbar, playerRef, store);
    }

    private void restore(
            Player player,
            String toolId,
            UUID npcUuid,
            Context context
    ) {
        for (short slot = 0; slot < context.hotbar().getCapacity(); slot++) {
            ItemStack stack = context.hotbar().getItemStack(slot);
            if (!matchesTool(stack, toolId)) {
                continue;
            }
            LinkedNpcRecord record = links.findLinkedNpcRecord(
                    links.readLinkedNpcRecords(stack), npcUuid
            );
            if (record == null) {
                feedback.showWarningKey(
                        player,
                        "tamework.ui.notifications.command.shared.notLinkedToTool"
                );
                return;
            }
            double safeDistance = safeDistance(record);
            CommandCompanionRestorationService.RequestStatus status =
                    restoration.request(
                            player,
                            context.playerRef(),
                            context.store(),
                            toolId,
                            record,
                            safeDistance
                    );
            feedback.emitRestorationRequestFeedback(player, status);
            return;
        }
        feedback.showWarningKey(
                player,
                "tamework.ui.notifications.command.shared.itemNotFound"
        );
    }

    private boolean matchesTool(ItemStack stack, String toolId) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String candidate = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING
        );
        return toolId.equals(candidate);
    }

    private double safeDistance(LinkedNpcRecord record) {
        double configured = TwCompanionConfig.resolveEffectiveForRole(
                record.cachedRoleId
        ).getRecallSafeSpawnDistance();
        return Double.isFinite(configured) && configured > 0.0
                ? configured
                : fallbackSafeSpawnDistance;
    }

    private record Context(
            @Nonnull ItemContainer hotbar,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Store<EntityStore> store
    ) {
    }
}
