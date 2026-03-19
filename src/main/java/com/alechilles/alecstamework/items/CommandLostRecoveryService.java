package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import it.unimi.dsi.fastutil.Pair;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Recovers linked companions marked as lost by creating a strict replacement.
 */
final class CommandLostRecoveryService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    private final CommandCompanionPlacementService companionPlacementService;
    private final CommandNpcExistenceService existenceService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandLinkedNpcLostService lostService;

    CommandLostRecoveryService(CommandCompanionPlacementService companionPlacementService,
                               CommandNpcExistenceService existenceService,
                               CommandLinkPolicyService linkPolicyService,
                               CommandLinkMutationService linkMutationService,
                               CommandNpcNameResolver npcNameResolver,
                               CommandStepExecutionService stepExecutionService,
                               CommandLinkedNpcLostService lostService) {
        this.companionPlacementService = companionPlacementService;
        this.existenceService = existenceService;
        this.linkPolicyService = linkPolicyService;
        this.linkMutationService = linkMutationService;
        this.npcNameResolver = npcNameResolver;
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

        String roleId = resolveRoleId(record);
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

        CommandLinkedNpcLostService.LostLinkedNpcSnapshot lostSnapshot = lostService.getLostSnapshot(record.npcUuid);
        Vector3d sourceHint = record.lastKnownPosition != null
                ? record.lastKnownPosition
                : lostSnapshot != null && lostSnapshot.lastKnownPosition() != null
                ? lostSnapshot.lastKnownPosition()
                : record.homePosition != null
                ? record.homePosition
                : lostSnapshot != null
                ? lostSnapshot.homePosition()
                : null;
        Vector3d destination = companionPlacementService.computeSafeRespawnPosition(
                playerRef,
                store,
                safeSpawnDistance,
                roleId,
                sourceHint
        );
        if (destination == null) {
            return Result.fail("Unable to find a safe recovery position.");
        }

        Vector3f rotation = resolveSpawnRotation(store, playerRef, destination);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, destination, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return Result.fail("Failed to spawn replacement companion.");
        }

        Ref<EntityStore> spawnedRef = spawned.first();
        NPCEntity spawnedNpc = spawned.second();
        UUID ownerId = player.getUuid();
        Vector3d homePosition = record.homePosition != null
                ? record.homePosition
                : lostSnapshot != null
                ? lostSnapshot.homePosition()
                : null;
        String[] toolIds = linkPolicyService.mergeToolIds(null, toolId);

        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            store.putComponent(spawnedRef, linksType, new TameworkCommandLinksComponent(ownerId, toolIds, homePosition));
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            store.putComponent(spawnedRef, ownerType, new TameworkOwnerComponent(ownerId, player.getDisplayName()));
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(spawnedRef, tamedType, new TameworkTamedComponent(true));
        }

        applyFollowBootstrap(spawnedRef, spawnedNpc, playerRef, store);

        ItemStack updatedStack = linkMutationService.removeLinkedNpcRecord(stack, record.npcUuid);
        updatedStack = linkMutationService.upsertLinkedNpcRecord(
                updatedStack,
                spawnedNpc.getUuid(),
                destination,
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

        String recoveredName = npcNameResolver.resolveNpcDisplayName(spawnedRef, store, spawnedNpc);
        return Result.success(updatedStack, recoveredName);
    }

    @Nullable
    private String resolveRoleId(LinkedNpcRecord record) {
        if (record == null) {
            return null;
        }
        if (record.cachedRoleId != null && !record.cachedRoleId.isBlank()) {
            return record.cachedRoleId;
        }
        if (record.cachedNameKey != null && !record.cachedNameKey.isBlank()) {
            String nameKey = record.cachedNameKey.trim();
            String[] prefixes = {
                    "server.npcRole.",
                    "npcRole.",
                    "server.npcRoles.",
                    "npcRoles."
            };
            for (String prefix : prefixes) {
                if (!nameKey.startsWith(prefix)) {
                    continue;
                }
                String remainder = nameKey.substring(prefix.length());
                if (remainder.endsWith(".name")) {
                    remainder = remainder.substring(0, remainder.length() - ".name".length());
                }
                if (!remainder.isBlank()) {
                    return remainder;
                }
            }
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

    private Vector3f resolveSpawnRotation(Store<EntityStore> store,
                                          Ref<EntityStore> playerRef,
                                          Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Vector3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Vector3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                    playerPos.x - spawnPosition.x,
                    0.0,
                    playerPos.z - spawnPosition.z
            );
            if (relative.squaredLength() > 0.0001) {
                return Vector3f.lookAt(relative);
            }
        }
        return new Vector3f(transform.getRotation());
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
