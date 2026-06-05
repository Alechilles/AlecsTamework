package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import it.unimi.dsi.fastutil.Pair;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Recovers linked companions marked as lost by creating a strict replacement.
 */
final class CommandLostRecoveryService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final long LOST_RECOVERY_FOLLOW_RETRY_DELAY_MS = 1250L;

    private final CommandCompanionPlacementService companionPlacementService;
    private final CommandNpcExistenceService existenceService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandRespawnService respawnService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandLinkedNpcLostService lostService;

    CommandLostRecoveryService(CommandCompanionPlacementService companionPlacementService,
                               CommandNpcExistenceService existenceService,
                               CommandLinkPolicyService linkPolicyService,
                               CommandLinkMutationService linkMutationService,
                               CommandNpcNameResolver npcNameResolver,
                               CommandRespawnService respawnService,
                               CommandStepExecutionService stepExecutionService,
                               CommandLinkedNpcLostService lostService) {
        this.companionPlacementService = companionPlacementService;
        this.existenceService = existenceService;
        this.linkPolicyService = linkPolicyService;
        this.linkMutationService = linkMutationService;
        this.npcNameResolver = npcNameResolver;
        this.respawnService = respawnService;
        this.stepExecutionService = stepExecutionService;
        this.lostService = lostService;
    }

    Result recoverLostLinkedNpc(Player player,
                                Ref<EntityStore> playerRef,
                                Store<EntityStore> store,
                                String toolId,
                                ItemStack stack,
                                LinkedNpcRecord record,
                                double safeSpawnDistance) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null
                || toolId == null || toolId.isBlank()
                || stack == null || stack.isEmpty() || record == null || record.npcUuid == null) {
            return Result.fail("Unable to recover right now.");
        }
        if (lostService == null || !lostService.isLost(record.npcUuid)) {
            return Result.fail("That companion is not marked as lost.");
        }
        CommandNpcExistenceService.LiveNpcMatch liveNpc = existenceService.findLiveNpc(record.npcUuid);
        if (liveNpc != null) {
            return Result.fail("That companion is still alive in world '" + liveNpc.worldName() + "'.");
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
        Result snapshotRecovery = tryRecoverFromSnapshot(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                recoverySnapshot,
                sourceHint,
                homePosition,
                safeSpawnDistance
        );
        if (snapshotRecovery != null && snapshotRecovery.isSuccess()) {
            RespawnTraceLogSupport.log(
                    null,
                    "lost_recovery_snapshot_success original=" + record.npcUuid
                            + " sourceHint=" + sourceHint
                            + " home=" + homePosition
            );
            return snapshotRecovery;
        }
        RespawnTraceLogSupport.log(
                null,
                "lost_recovery_fallback_start original=" + record.npcUuid
                        + " snapshotResult=" + (snapshotRecovery == null ? "<none>" : snapshotRecovery.errorMessage())
                        + " sourceHint=" + sourceHint
                        + " home=" + homePosition
        );

        String roleId = resolveRoleId(record, recoverySnapshot);
        if (roleId == null || roleId.isBlank()) {
            return Result.fail("Unable to recover: missing role metadata.");
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return Result.fail("Unable to recover right now.");
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return Result.fail("Unable to recover: unknown role '" + roleId + "'.");
        }
        RecentRespawnTraceService.Trace respawnTrace = RespawnTraceLogSupport.startTrace(
                "lost_fallback_recovery",
                record.npcUuid,
                recoverySnapshot != null ? recoverySnapshot.ownerId() : player.getUuid(),
                roleId,
                toolId
        );

        Vector3d destination = companionPlacementService.computeSafeRespawnPosition(
                playerRef,
                store,
                safeSpawnDistance,
                roleId,
                sourceHint
        );
        if (destination == null) {
            RespawnTraceLogSupport.warn(respawnTrace, "failed stage=safe_position reason=safe_position_not_found");
            return Result.fail("Unable to find a safe recovery position.");
        }

        Rotation3f rotation = resolveSpawnRotation(store, playerRef, destination);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, destination, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            RespawnTraceLogSupport.warn(respawnTrace, "failed stage=spawn reason=spawn_entity_failed destination=" + destination);
            return Result.fail("Failed to spawn replacement companion.");
        }

        Ref<EntityStore> spawnedRef = spawned.first();
        NPCEntity spawnedNpc = spawned.second();
        String physicsReset = CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                spawnedRef,
                spawnedNpc,
                store
        );
        respawnTrace = RespawnTraceLogSupport.recordReplacement(respawnTrace, spawnedNpc.getUuid());
        RespawnTraceLogSupport.log(respawnTrace, "spawn_physics_reset " + physicsReset);
        RespawnTraceLogSupport.log(
                respawnTrace,
                "spawned destination=" + destination
                        + " recoveryStateApplied=false "
                        + RespawnTraceLogSupport.describeNpcState(spawnedRef, store)
        );
        UUID ownerId = recoverySnapshot != null && recoverySnapshot.ownerId() != null
                ? recoverySnapshot.ownerId()
                : player.getUuid();
        String ownerName = recoverySnapshot != null && recoverySnapshot.ownerName() != null
                ? recoverySnapshot.ownerName()
                : OwnerNameUtil.resolve(player);
        String[] toolIds = linkPolicyService.mergeToolIds(null, toolId);

        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            store.putComponent(spawnedRef, linksType, new TameworkCommandLinksComponent(ownerId, toolIds, homePosition));
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            store.putComponent(spawnedRef, ownerType, new TameworkOwnerComponent(ownerId, ownerName));
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            boolean tamed = recoverySnapshot == null || recoverySnapshot.tamed();
            store.putComponent(spawnedRef, tamedType, new TameworkTamedComponent(tamed));
        }
        if (recoverySnapshot != null && recoverySnapshot.customName() != null && !recoverySnapshot.customName().isBlank()) {
            ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
            if (nameType != null) {
                store.putComponent(
                        spawnedRef,
                        nameType,
                        new TameworkNpcNameComponent(
                                recoverySnapshot.customName(),
                                ownerId,
                                System.currentTimeMillis(),
                                TameworkNpcNameComponent.NameSource.System
                        )
                );
            }
            EntitySupport.setDisplayName(spawnedRef, recoverySnapshot.customName(), store);
        }

        applyFollowBootstrap(spawnedRef, spawnedNpc, playerRef, store);
        RespawnTraceLogSupport.log(
                respawnTrace,
                "post_components recoveryStateApplied=false " + RespawnTraceLogSupport.describeNpcState(spawnedRef, store)
        );
        RespawnTraceLogSupport.scheduleProbe(player.getWorld(), spawnedNpc.getUuid(), respawnTrace, 250L, "lost_after_250ms");
        RespawnTraceLogSupport.scheduleProbe(player.getWorld(), spawnedNpc.getUuid(), respawnTrace, 1000L, "lost_after_1000ms");

        ItemStack updatedStack = linkMutationService.removeLinkedNpcRecord(stack, record.npcUuid);
        updatedStack = linkMutationService.upsertLinkedNpcRecord(
                updatedStack,
                spawnedNpc.getUuid(),
                destination,
                linkMutationService.resolveWorldName(store, player.getWorld()),
                homePosition,
                npcNameResolver.resolveNpcDisplayNameFromComponents(spawnedRef, store),
                npcNameResolver.resolveNpcNameKey(spawnedNpc),
                npcNameResolver.resolveNpcRoleId(spawnedNpc)
        );
        updatedStack = linkMutationService.setLinkedNpcBreedingEnabled(
                updatedStack,
                spawnedNpc.getUuid(),
                record.breedingEnabled
        );

        lostService.markRecovered(record.npcUuid, spawnedNpc.getUuid(), sourceHint, homePosition);
        existenceService.despawnIfPresent(record.npcUuid);
        RespawnTraceLogSupport.log(respawnTrace, "lost_recovery_linked_record_updated oldNpc=" + record.npcUuid
                + " newNpc=" + spawnedNpc.getUuid());

        String recoveredName = npcNameResolver.resolveNpcDisplayName(spawnedRef, store, spawnedNpc);
        return Result.success(updatedStack, recoveredName);
    }

    @Nullable
    private Result tryRecoverFromSnapshot(Player player,
                                          Ref<EntityStore> playerRef,
                                          Store<EntityStore> store,
                                          String toolId,
                                          ItemStack stack,
                                          LinkedNpcRecord record,
                                          @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot,
                                          @Nullable Vector3d sourceHint,
                                          @Nullable Vector3d homePosition,
                                          double safeSpawnDistance) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null
                || toolId == null || toolId.isBlank()
                || stack == null || stack.isEmpty() || record == null || record.npcUuid == null
                || recoverySnapshot == null
                || respawnService == null
                || recoverySnapshot.roleId() == null
                || recoverySnapshot.roleId().isBlank()) {
            return null;
        }
        List<LinkedNpcRecord> beforeRecords = linkMutationService.readLinkedNpcRecords(stack);
        ItemStack updatedStack = respawnService.respawnDeadLinkedNpc(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                recoverySnapshot,
                safeSpawnDistance,
                LOST_RECOVERY_FOLLOW_RETRY_DELAY_MS,
                "lost_snapshot_recovery"
        );
        if (updatedStack == null || updatedStack.isEmpty()) {
            return null;
        }
        List<LinkedNpcRecord> afterRecords = linkMutationService.readLinkedNpcRecords(updatedStack);
        UUID replacementNpcUuid = resolveReplacementUuid(beforeRecords, afterRecords, record.npcUuid);
        if (replacementNpcUuid != null) {
            lostService.markRecovered(record.npcUuid, replacementNpcUuid, sourceHint, homePosition);
        } else {
            lostService.clearLostSnapshot(record.npcUuid);
        }
        existenceService.despawnIfPresent(record.npcUuid);
        String recoveredName = firstNonBlank(
                recoverySnapshot.customName(),
                recoverySnapshot.displayName(),
                record.cachedDisplayName
        );
        return Result.success(updatedStack, recoveredName);
    }

    @Nullable
    private UUID resolveReplacementUuid(@Nullable List<LinkedNpcRecord> beforeRecords,
                                        @Nullable List<LinkedNpcRecord> afterRecords,
                                        @Nullable UUID originalNpcUuid) {
        if (afterRecords == null || afterRecords.isEmpty()) {
            return null;
        }
        Set<UUID> beforeIds = new HashSet<>();
        if (beforeRecords != null) {
            for (LinkedNpcRecord record : beforeRecords) {
                if (record == null || record.npcUuid == null) {
                    continue;
                }
                beforeIds.add(record.npcUuid);
            }
        }
        for (LinkedNpcRecord record : afterRecords) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (originalNpcUuid != null && originalNpcUuid.equals(record.npcUuid)) {
                continue;
            }
            if (!beforeIds.contains(record.npcUuid)) {
                return record.npcUuid;
            }
        }
        return null;
    }

    @Nullable
    private String resolveRoleId(LinkedNpcRecord record,
                                 @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot) {
        if (recoverySnapshot != null && recoverySnapshot.roleId() != null && !recoverySnapshot.roleId().isBlank()) {
            return recoverySnapshot.roleId();
        }
        if (record == null) {
            return null;
        }
        if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            return record.cachedRoleId;
        }
        if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
            String roleId = RoleNameResolver.extractRoleIdFromNameKey(record.cachedNameKey);
            if (roleId != null && !roleId.isBlank()) {
                return roleId;
            }
        }
        return null;
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

    private void applyFollowBootstrap(Ref<EntityStore> npcRef,
                                      NPCEntity npc,
                                      Ref<EntityStore> playerRef,
                                      Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null) {
            return;
        }
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            if (playerRef != null && playerRef.isValid()) {
                role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, playerRef);
            }
        }
        if (!stepExecutionService.applyState(npcRef, npc, store, "Follow", null)) {
            stepExecutionService.applyState(npcRef, npc, store, "Idle", null);
        }
    }

    private Rotation3f resolveSpawnRotation(Store<EntityStore> store,
                                            Ref<EntityStore> playerRef,
                                            Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Rotation3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Rotation3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                    playerPos.x - spawnPosition.x,
                    0.0,
                    playerPos.z - spawnPosition.z
            );
            if (relative.lengthSquared() > 0.0001) {
                return Rotation3f.lookAt(relative);
            }
        }
        return new Rotation3f(transform.getRotation());
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
