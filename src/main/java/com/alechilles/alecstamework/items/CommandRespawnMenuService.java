package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
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

/** Owns the asynchronous command-menu dead and lost replacement workflow. */
final class CommandRespawnMenuService {
    private static final double DEFAULT_SAFE_SPAWN_DISTANCE = 20.0;
    private static final long DEFAULT_FOLLOW_RETRY_DELAY_MS = 1250L;

    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandRespawnService respawnService;
    private final CommandLostRecoveryService lostRecoveryService;
    private final CommandFeedbackService feedbackService;

    CommandRespawnMenuService(CommandLinkedNpcDeathService deathService,
                              CommandLinkedNpcLostService lostService,
                              CommandLinkMutationService linkMutationService,
                              CommandRespawnService respawnService,
                              CommandLostRecoveryService lostRecoveryService,
                              CommandFeedbackService feedbackService) {
        this.deathService = deathService;
        this.lostService = lostService;
        this.linkMutationService = linkMutationService;
        this.respawnService = respawnService;
        this.lostRecoveryService = lostRecoveryService;
        this.feedbackService = feedbackService;
    }

    void respawn(Player player, String toolId, UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        if (deathService == null && lostService == null) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.trackingUnavailable"
            );
            return;
        }
        Inventory inventory = player.getInventory();
        World world = player.getWorld();
        ItemContainer hotbar = inventory == null ? null : inventory.getHotbar();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        if (hotbar == null || store == null || playerRef == null || !playerRef.isValid()) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.unavailable");
            return;
        }
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (!matchesTool(stack, toolId)) {
                continue;
            }
            LinkedNpcRecord record = linkMutationService.findLinkedNpcRecord(
                    linkMutationService.readLinkedNpcRecords(stack),
                    npcUuid
            );
            if (record == null) {
                feedbackService.showWarningKey(
                        player,
                        "tamework.ui.notifications.command.shared.notLinkedToTool"
                );
                return;
            }
            startDeadOrLostRespawn(player, playerRef, store, hotbar, slot, toolId, stack, record);
            return;
        }
        feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.itemNotFound");
    }

    private void startDeadOrLostRespawn(Player player,
                                        Ref<EntityStore> playerRef,
                                        Store<EntityStore> store,
                                        ItemContainer hotbar,
                                        short slot,
                                        String toolId,
                                        ItemStack stack,
                                        LinkedNpcRecord record) {
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot = deathService == null
                ? null
                : deathService.getDeadSnapshotForTool(record.npcUuid, toolId, player.getUuid());
        if (snapshot != null) {
            startDeadRespawn(player, playerRef, store, hotbar, slot, toolId, stack, record, snapshot);
            return;
        }
        if (lostService == null || !lostService.isLost(record.npcUuid)) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.notDeadOrLost"
            );
            return;
        }
        startLostRecovery(player, playerRef, store, hotbar, slot, toolId, stack, record);
    }

    private void startDeadRespawn(
            Player player,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            ItemContainer hotbar,
            short slot,
            String toolId,
            ItemStack stack,
            LinkedNpcRecord record,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        String roleId = firstNonBlank(snapshot.roleId(), record.cachedRoleId);
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
        if (!CompanionRevivePolicy.featureEnabled(roleId)) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.disabled");
            return;
        }
        long remainingMs = Math.max(0L, snapshot.respawnAvailableAtMs() - System.currentTimeMillis());
        if (remainingMs > 0L) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.cooldownRemaining",
                    formatDuration(player, remainingMs)
            );
            return;
        }
        double safeDistance = positive(settings.getRecallSafeSpawnDistance(), DEFAULT_SAFE_SPAWN_DISTANCE);
        long retryDelay = positive(settings.getDeadRespawnFollowRetryDelayMs(), DEFAULT_FOLLOW_RETRY_DELAY_MS);
        String displayName = firstNonBlank(
                snapshot.displayName(),
                LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultCompanionName")
        );
        boolean started = respawnService.respawnDeadLinkedNpc(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                snapshot,
                safeDistance,
                retryDelay,
                deadCompletion(player.getWorld(), player.getUuid(), slot, stack, displayName)
        );
        if (!started) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.respawn.failed");
        }
    }

    @Nonnull
    private CommandRespawnService.Completion deadCompletion(World world,
                                                            UUID playerUuid,
                                                            short slot,
                                                            ItemStack expected,
                                                            String displayName) {
        return new CommandRespawnService.Completion() {
            @Override
            public boolean onApplied(@Nonnull CommandRespawnService.AppliedRespawn result) {
                WorldPlayerResolver.ResolvedPlayer resolved =
                        WorldPlayerResolver.resolve(world, playerUuid);
                ItemContainer hotbar = hotbar(resolved);
                if (resolved == null || hotbar == null) {
                    return false;
                }
                if (!replaceItem(hotbar, slot, expected, result.updatedStack())) {
                    feedbackService.showWarningKey(
                            resolved.player(), "tamework.ui.notifications.command.respawn.failed"
                    );
                    return false;
                }
                feedbackService.showSuccessKey(
                        resolved.player(),
                        "tamework.ui.notifications.command.respawn.success",
                        displayName
                );
                return true;
            }

            @Override
            public void onDenied(@Nonnull String reason) {
                showWarning(world, playerUuid, "tamework.ui.notifications.command.respawn.failed");
            }

            @Override
            public void onDurabilityDegraded(@Nonnull String reason) {
                showWarning(world, playerUuid, "tamework.ui.notifications.command.respawn.failed");
            }
        };
    }

    private void startLostRecovery(Player player,
                                   Ref<EntityStore> playerRef,
                                   Store<EntityStore> store,
                                   ItemContainer hotbar,
                                   short slot,
                                   String toolId,
                                   ItemStack stack,
                                   LinkedNpcRecord record) {
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.resolveEffectiveForRole(record.cachedRoleId);
        double safeDistance = positive(settings.getRecallSafeSpawnDistance(), DEFAULT_SAFE_SPAWN_DISTANCE);
        boolean started = lostRecoveryService.recoverLostLinkedNpc(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                safeDistance,
                lostCompletion(player.getWorld(), player.getUuid(), slot, stack, record)
        );
        if (!started) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.recoverFailed"
            );
        }
    }

    @Nonnull
    private CommandLostRecoveryService.Completion lostCompletion(World world,
                                                                 UUID playerUuid,
                                                                 short slot,
                                                                 ItemStack expected,
                                                                 LinkedNpcRecord record) {
        return new CommandLostRecoveryService.Completion() {
            @Override
            public boolean onApplied(@Nonnull CommandLostRecoveryService.Result result) {
                WorldPlayerResolver.ResolvedPlayer resolved =
                        WorldPlayerResolver.resolve(world, playerUuid);
                ItemContainer hotbar = hotbar(resolved);
                if (resolved == null || hotbar == null) {
                    return false;
                }
                if (result.updatedStack() == null
                        || !replaceItem(hotbar, slot, expected, result.updatedStack())) {
                    feedbackService.showWarningKey(
                            resolved.player(),
                            "tamework.ui.notifications.command.respawn.recoverFailed"
                    );
                    return false;
                }
                String recoveredName = firstNonBlank(
                        result.recoveredName(),
                        record.cachedDisplayName,
                        LocalizedText.resolve(
                                resolved.player(),
                                "tamework.ui.notifications.command.shared.defaultCompanionName"
                        )
                );
                feedbackService.showSuccessKey(
                        resolved.player(),
                        "tamework.ui.notifications.command.respawn.recovered",
                        recoveredName
                );
                return true;
            }

            @Override
            public void onDenied(@Nonnull String reason) {
                WorldPlayerResolver.ResolvedPlayer resolved =
                        WorldPlayerResolver.resolve(world, playerUuid);
                if (resolved == null) {
                    return;
                }
                feedbackService.showWarning(
                        resolved.player(),
                        reason == null || reason.isBlank()
                                ? LocalizedText.resolve(
                                        resolved.player(),
                                        "tamework.ui.notifications.command.respawn.recoverFailed"
                                )
                                : reason
                );
            }

            @Override
            public void onDurabilityDegraded(@Nonnull String reason) {
                showWarning(
                        world, playerUuid,
                        "tamework.ui.notifications.command.respawn.recoverFailed"
                );
            }
        };
    }

    private boolean matchesTool(ItemStack stack, String toolId) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        return toolId.equals(stackToolId);
    }

    private boolean replaceItem(ItemContainer hotbar,
                                short slot,
                                ItemStack expected,
                                ItemStack replacement) {
        ItemStack current = hotbar.getItemStack(slot);
        if (Objects.equals(current, replacement)) {
            return true;
        }
        if (!Objects.equals(current, expected)) {
            return false;
        }
        hotbar.setItemStackForSlot(slot, replacement);
        return true;
    }

    private void showWarning(World world, UUID playerUuid, String key) {
        WorldPlayerResolver.ResolvedPlayer resolved =
                WorldPlayerResolver.resolve(world, playerUuid);
        if (resolved != null) {
            feedbackService.showWarningKey(resolved.player(), key);
        }
    }

    private static ItemContainer hotbar(WorldPlayerResolver.ResolvedPlayer resolved) {
        Inventory inventory = resolved == null ? null : resolved.player().getInventory();
        return inventory == null ? null : inventory.getHotbar();
    }

    private String formatDuration(Player player, long durationMs) {
        long totalSeconds = Math.max(1L, (durationMs + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes > 0L) {
            return LocalizedText.format(
                    player,
                    "tamework.ui.shared.duration.minutesSeconds",
                    minutes,
                    seconds
            );
        }
        return LocalizedText.format(
                player,
                "tamework.ui.shared.duration.seconds",
                seconds
        );
    }

    private static double positive(double configured, double fallback) {
        return Double.isFinite(configured) && configured > 0.0 ? configured : fallback;
    }

    private static long positive(long configured, long fallback) {
        return configured > 0L ? configured : fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
