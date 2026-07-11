package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Recovers linked companions marked as lost by creating a strict replacement.
 */
final class CommandLostRecoveryService {
    private static final long LOST_RECOVERY_FOLLOW_RETRY_DELAY_MS = 1250L;

    private final CommandNpcExistenceService existenceService;
    private final CommandRespawnService respawnService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandLostFallbackSpawnService fallbackSpawnService;

    CommandLostRecoveryService(CommandCompanionPlacementService companionPlacementService,
                               CommandNpcExistenceService existenceService,
                               CommandLinkPolicyService linkPolicyService,
                               CommandLinkMutationService linkMutationService,
                               CommandNpcNameResolver npcNameResolver,
                               CommandRespawnService respawnService,
                               CommandStepExecutionService stepExecutionService,
                               CommandLinkedNpcLostService lostService) {
        this.existenceService = existenceService;
        this.respawnService = respawnService;
        this.lostService = lostService;
        this.fallbackSpawnService = new CommandLostFallbackSpawnService(
                companionPlacementService, linkPolicyService, linkMutationService,
                npcNameResolver, stepExecutionService, existenceService, lostService
        );
    }

    boolean recoverLostLinkedNpc(Player player,
                                 Ref<EntityStore> playerRef,
                                 Store<EntityStore> store,
                                 String toolId,
                                 ItemStack stack,
                                 LinkedNpcRecord record,
                                 double safeSpawnDistance,
                                 Completion completion) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null
                || toolId == null || toolId.isBlank()
                || stack == null || stack.isEmpty() || record == null || record.npcUuid == null
                || completion == null) {
            return false;
        }
        if (lostService == null || !lostService.isLost(record.npcUuid)) {
            completion.onDenied("That companion is not marked as lost.");
            return true;
        }
        CommandNpcExistenceService.LiveNpcMatch liveNpc = existenceService.findLiveNpc(record.npcUuid);
        if (liveNpc != null) {
            completion.onDenied("That companion is still alive in world '" + liveNpc.worldName() + "'.");
            return true;
        }

        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot = lostService.getRecoverySnapshot(record.npcUuid);
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot lostSnapshot = lostService.getLostSnapshot(record.npcUuid);
        RespawnTraceLogSupport.log(
                null,
                "lost_recovery_start original=" + record.npcUuid
                        + " hasRecoverySnapshot=" + (recoverySnapshot != null)
                        + " hasLostSnapshot=" + (lostSnapshot != null)
                        + " tool=" + toolId
        );
        Vector3d sourceHint = record.lastKnownPosition != null
                ? record.lastKnownPosition
                : lostSnapshot != null && lostSnapshot.lastKnownPosition() != null
                ? lostSnapshot.lastKnownPosition()
                : record.homePosition != null
                ? record.homePosition
                : lostSnapshot != null
                ? lostSnapshot.homePosition()
                : null;
        Vector3d homePosition = record.homePosition != null
                ? record.homePosition
                : recoverySnapshot != null && recoverySnapshot.homePosition() != null
                ? recoverySnapshot.homePosition()
                : lostSnapshot != null
                ? lostSnapshot.homePosition()
                : null;
        boolean snapshotRecoveryStarted = tryRecoverFromSnapshot(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                recoverySnapshot,
                sourceHint,
                homePosition,
                safeSpawnDistance,
                completion
        );
        if (snapshotRecoveryStarted) {
            RespawnTraceLogSupport.log(
                    null,
                    "lost_recovery_snapshot_scheduled original=" + record.npcUuid
                            + " sourceHint=" + sourceHint
                            + " home=" + homePosition
            );
            return true;
        }
        RespawnTraceLogSupport.log(
                null,
                "lost_recovery_fallback_start original=" + record.npcUuid
                        + " snapshotResult=<not-started>"
                        + " sourceHint=" + sourceHint
                        + " home=" + homePosition
        );

        return fallbackSpawnService.schedule(
                player, playerRef, store, toolId, stack, record, recoverySnapshot,
                sourceHint, homePosition, safeSpawnDistance, completion
        );
    }

    private boolean tryRecoverFromSnapshot(Player player,
                                           Ref<EntityStore> playerRef,
                                           Store<EntityStore> store,
                                           String toolId,
                                           ItemStack stack,
                                           LinkedNpcRecord record,
                                           @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot,
                                           @Nullable Vector3d sourceHint,
                                           @Nullable Vector3d homePosition,
                                           double safeSpawnDistance,
                                           Completion completion) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null
                || toolId == null || toolId.isBlank()
                || stack == null || stack.isEmpty() || record == null || record.npcUuid == null
                || recoverySnapshot == null
                || respawnService == null
                || recoverySnapshot.roleId() == null
                || recoverySnapshot.roleId().isBlank()) {
            return false;
        }
        return respawnService.respawnDeadLinkedNpc(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                recoverySnapshot,
                safeSpawnDistance,
                LOST_RECOVERY_FOLLOW_RETRY_DELAY_MS,
                "lost_snapshot_recovery",
                new CommandRespawnService.Completion() {
                    @Override
                    public boolean onApplied(@Nonnull CommandRespawnService.AppliedRespawn result) {
                        String recoveredName = firstNonBlank(
                                recoverySnapshot.customName(),
                                recoverySnapshot.displayName(),
                                record.cachedDisplayName
                        );
                        boolean accepted = completion.onApplied(
                                Result.success(result.updatedStack(), recoveredName)
                        );
                        if (!accepted) {
                            return false;
                        }
                        lostService.markRecovered(
                                record.npcUuid,
                                result.replacementNpcUuid(),
                                sourceHint,
                                homePosition
                        );
                        existenceService.despawnIfPresent(record.npcUuid);
                        return true;
                    }

                    @Override
                    public void onDenied(@Nonnull String reason) {
                        completion.onDenied("Unable to recover: " + reason + ".");
                    }

                    @Override
                    public void onDurabilityDegraded(@Nonnull String reason) {
                        completion.onDurabilityDegraded(reason);
                    }
                }
        );
    }

    @Nullable
    private String firstNonBlank(@Nullable String first,
                                 @Nullable String second,
                                 @Nullable String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        if (third != null && !third.isBlank()) {
            return third;
        }
        return null;
    }

    interface Completion {
        boolean onApplied(@Nonnull Result result);

        void onDenied(@Nonnull String reason);

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }

    record Result(@Nullable ItemStack updatedStack,
                  @Nullable String recoveredName,
                  @Nullable String errorMessage) {
        static Result success(ItemStack updatedStack, @Nullable String recoveredName) {
            return new Result(updatedStack, recoveredName, null);
        }

        static Result fail(String errorMessage) {
            return new Result(null, null, errorMessage);
        }

        boolean isSuccess() {
            return updatedStack != null;
        }
    }
}
