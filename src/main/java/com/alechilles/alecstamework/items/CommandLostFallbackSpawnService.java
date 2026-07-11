package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
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
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restores a lost companion from minimal cached metadata when no full death snapshot is usable. */
final class CommandLostFallbackSpawnService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    private final CommandCompanionPlacementService placementService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandNpcExistenceService existenceService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandPreparedRestoreSpawnService preparedSpawnService =
            new CommandPreparedRestoreSpawnService();

    CommandLostFallbackSpawnService(
            CommandCompanionPlacementService placementService,
            CommandLinkPolicyService linkPolicyService,
            CommandLinkMutationService linkMutationService,
            CommandNpcNameResolver npcNameResolver,
            CommandStepExecutionService stepExecutionService,
            CommandNpcExistenceService existenceService,
            CommandLinkedNpcLostService lostService
    ) {
        this.placementService = placementService;
        this.linkPolicyService = linkPolicyService;
        this.linkMutationService = linkMutationService;
        this.npcNameResolver = npcNameResolver;
        this.stepExecutionService = stepExecutionService;
        this.existenceService = existenceService;
        this.lostService = lostService;
    }

    boolean schedule(
            Player player,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            String toolId,
            ItemStack stack,
            LinkedNpcRecord record,
            @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot,
            @Nullable Vector3d sourceHint,
            @Nullable Vector3d homePosition,
            double safeSpawnDistance,
            CommandLostRecoveryService.Completion completion
    ) {
        String roleId = resolveRoleId(record, recoverySnapshot);
        if (roleId == null || roleId.isBlank()) {
            completion.onDenied("Unable to recover: missing role metadata.");
            return true;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin == null ? -1 : npcPlugin.getIndex(roleId);
        if (npcPlugin == null || roleIndex < 0) {
            completion.onDenied(roleIndex < 0
                    ? "Unable to recover: unknown role '" + roleId + "'."
                    : "Unable to recover right now.");
            return true;
        }
        RecentRespawnTraceService.Trace trace = RespawnTraceLogSupport.startTrace(
                "lost_fallback_recovery", record.npcUuid,
                recoverySnapshot != null ? recoverySnapshot.ownerId() : player.getUuid(),
                roleId, toolId
        );
        Vector3d destination = placementService.computeSafeRespawnPosition(
                playerRef, store, safeSpawnDistance, roleId, sourceHint
        );
        if (destination == null) {
            RespawnTraceLogSupport.warn(trace, "failed stage=safe_position reason=safe_position_not_found");
            completion.onDenied("Unable to find a safe recovery position.");
            return true;
        }
        UUID ownerId = recoverySnapshot != null && recoverySnapshot.ownerId() != null
                ? recoverySnapshot.ownerId() : player.getUuid();
        String ownerName = recoverySnapshot != null && recoverySnapshot.ownerName() != null
                ? recoverySnapshot.ownerName() : OwnerNameUtil.resolve(player);
        String[] toolIds = linkPolicyService.mergeToolIds(null, toolId);
        Tamework plugin = Tamework.getInstance();
        CompanionIdentityResolver identities = plugin == null ? null : plugin.getCompanionIdentityResolver();
        String profileId = identities == null
                ? null : identities.resolveProfileId(record.npcUuid).orElse(null);
        if (profileId == null) {
            completion.onDenied("Unable to recover: canonical companion profile is unavailable.");
            return true;
        }
        UUID playerUuid = player.getUuid();
        CompanionSpawnAdmissionRequest request = new CompanionSpawnAdmissionRequest(
                profileId, record.npcUuid, CompanionLifecycleState.LOST, false,
                ownerId, ownerName, player.getWorld().getName(),
                ChunkUtil.chunkCoordinate(destination.x), ChunkUtil.chunkCoordinate(destination.z),
                OwnerPopulationOperation.RESTORE, "lost_restore",
                "command-lost-recovery:" + record.npcUuid, false,
                CompanionSpawnSourceFinalizationContext.extensionJson(
                        CompanionSpawnSourceFinalizationContext.Kind.LOST_RECORD,
                        "command-lost-source:" + record.npcUuid,
                        record.npcUuid,
                        playerUuid,
                        null,
                        toolId + "|" + Integer.toUnsignedString(stack.hashCode(), 16),
                        null
                )
        );
        Rotation3f rotation = resolveSpawnRotation(store, playerRef, destination);
        return preparedSpawnService.schedule(
                player.getWorld(), store, npcPlugin, roleIndex, destination, rotation, request,
                new CommandPreparedRestoreSpawnService.Callbacks() {
                    @Nullable
                    private FinishedSpawn pending;

                    @Override
                    public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        WorldPlayerResolver.ResolvedPlayer resolved =
                                WorldPlayerResolver.resolve(live.world(), playerUuid);
                        if (resolved == null) {
                            throw new IllegalStateException(
                                    "Lost-recovery owner is unavailable after population commit."
                            );
                        }
                        pending = finishSpawn(
                                resolved.player(), resolved.ref(), live.store(),
                                stack, record, recoverySnapshot,
                                sourceHint, homePosition, destination, ownerId, toolIds, trace, live
                        );
                    }

                    @Override
                    public boolean finalizeSource(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        FinishedSpawn finished = pending;
                        if (finished == null || !completion.onApplied(finished.result())) {
                            return false;
                        }
                        lostService.markRecovered(
                                record.npcUuid, finished.replacementUuid(), sourceHint, homePosition
                        );
                        existenceService.despawnIfPresent(record.npcUuid);
                        RespawnTraceLogSupport.log(
                                finished.trace(), "lost_recovery_linked_record_updated oldNpc="
                                        + record.npcUuid + " newNpc=" + finished.replacementUuid()
                        );
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

    private FinishedSpawn finishSpawn(
            Player player,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            ItemStack stack,
            LinkedNpcRecord record,
            @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot recoverySnapshot,
            @Nullable Vector3d sourceHint,
            @Nullable Vector3d homePosition,
            Vector3d destination,
            UUID ownerId,
            String[] toolIds,
            RecentRespawnTraceService.Trace trace,
            CompanionPreparedSpawnService.SpawnedCompanion live
    ) {
        Ref<EntityStore> ref = live.ref();
        NPCEntity npc = live.npc();
        String reset = CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(ref, npc, store);
        RecentRespawnTraceService.Trace finalized =
                RespawnTraceLogSupport.recordReplacement(trace, live.plannedNpcUuid());
        RespawnTraceLogSupport.log(finalized, "spawn_physics_reset " + reset);
        applyFallbackState(ref, npc, playerRef, store, ownerId, homePosition, toolIds, recoverySnapshot);
        ItemStack updated = buildUpdatedStack(player, store, stack, record, destination,
                homePosition, ref, npc);
        RespawnTraceLogSupport.scheduleProbe(
                player.getWorld(), live.plannedNpcUuid(), finalized, 250L, "lost_after_250ms");
        RespawnTraceLogSupport.scheduleProbe(
                player.getWorld(), live.plannedNpcUuid(), finalized, 1000L, "lost_after_1000ms");
        String name = npcNameResolver.resolveNpcDisplayName(ref, store, npc);
        return new FinishedSpawn(
                CommandLostRecoveryService.Result.success(updated, name),
                live.plannedNpcUuid(),
                finalized
        );
    }

    private void applyFallbackState(
            Ref<EntityStore> ref, NPCEntity npc, Ref<EntityStore> playerRef,
            Store<EntityStore> store, UUID ownerId, @Nullable Vector3d home,
            String[] toolIds,
            @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        ComponentType<EntityStore, TameworkCommandLinksComponent> links =
                TameworkCommandLinksComponent.getComponentType();
        if (links != null) store.putComponent(ref, links,
                new TameworkCommandLinksComponent(ownerId, toolIds, home));
        ComponentType<EntityStore, TameworkTamedComponent> tamed = TameworkTamedComponent.getComponentType();
        if (tamed != null) store.putComponent(ref, tamed,
                new TameworkTamedComponent(snapshot == null || snapshot.tamed()));
        applyFallbackName(ref, store, ownerId, snapshot);
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            if (playerRef != null && playerRef.isValid()) {
                role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, playerRef);
            }
        }
        if (!stepExecutionService.applyState(ref, npc, store, "Follow", null)) {
            stepExecutionService.applyState(ref, npc, store, "Idle", null);
        }
    }

    private void applyFallbackName(
            Ref<EntityStore> ref, Store<EntityStore> store, UUID ownerId,
            @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.customName() == null || snapshot.customName().isBlank()) return;
        ComponentType<EntityStore, TameworkNpcNameComponent> type = TameworkNpcNameComponent.getComponentType();
        if (type != null) store.putComponent(ref, type, new TameworkNpcNameComponent(
                snapshot.customName(), ownerId, System.currentTimeMillis(),
                TameworkNpcNameComponent.NameSource.System
        ));
        EntitySupport.setDisplayName(ref, snapshot.customName(), store);
    }

    private ItemStack buildUpdatedStack(
            Player player, Store<EntityStore> store, ItemStack stack, LinkedNpcRecord record,
            Vector3d destination, @Nullable Vector3d home, Ref<EntityStore> ref, NPCEntity npc
    ) {
        ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, record.npcUuid);
        updated = linkMutationService.upsertLinkedNpcRecord(
                updated, npc.getUuid(), destination,
                linkMutationService.resolveWorldName(store, player.getWorld()), home,
                npcNameResolver.resolveNpcDisplayNameFromComponents(ref, store),
                npcNameResolver.resolveNpcNameKey(npc), npcNameResolver.resolveNpcRoleId(npc)
        );
        return linkMutationService.setLinkedNpcBreedingEnabled(
                updated, npc.getUuid(), record.breedingEnabled
        );
    }

    @Nullable
    private String resolveRoleId(LinkedNpcRecord record,
                                 @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (snapshot != null && snapshot.roleId() != null && !snapshot.roleId().isBlank()) return snapshot.roleId();
        if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) return record.cachedRoleId;
        return record.cachedNameKey == null ? null
                : RoleNameResolver.extractRoleIdFromNameKey(record.cachedNameKey);
    }

    private Rotation3f resolveSpawnRotation(Store<EntityStore> store,
                                            Ref<EntityStore> playerRef,
                                            Vector3d position) {
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) return new Rotation3f();
        Vector3d playerPos = new Vector3d(transform.getPosition());
        Vector3d relative = new Vector3d(
                playerPos.x - position.x, 0.0, playerPos.z - position.z
        );
        return relative.lengthSquared() > 0.0001
                ? Rotation3f.lookAt(relative) : new Rotation3f(transform.getRotation());
    }

    private record FinishedSpawn(
            CommandLostRecoveryService.Result result,
            UUID replacementUuid,
            RecentRespawnTraceService.Trace trace
    ) {
    }
}
