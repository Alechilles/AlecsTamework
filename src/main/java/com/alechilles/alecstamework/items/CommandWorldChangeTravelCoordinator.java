package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Brings following linked-record companions to a player who entered a destination world.
 *
 * <p>A companion whose body is loaded in another world is restored at the player through
 * {@link RestoreFlow} (reason RECALL) once its live state passes the follow state filter on its
 * own world thread. A companion whose body is already loaded in the destination world is moved by
 * the in-world relocation. A companion with no loaded body is skipped: it was not near its owner,
 * so it was not following. The old cross-world transfer is never used. Until
 * {@link #useRestoreFlow} supplies the flow and the index queries, nothing travels.</p>
 */
final class CommandWorldChangeTravelCoordinator {
    private static final Logger LOGGER =
            Logger.getLogger(CommandWorldChangeTravelCoordinator.class.getName());

    private final CommandNpcRelocationService relocationService;
    private final CommandResolutionService resolutionService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandCanonicalRecordCommitGate canonicalRecordCommitGate;
    private final CommandCompanionPlacementService placementService;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;
    private final double defaultSafeSpawnDistance;
    @Nullable
    private volatile RestoreFlow<Ref<EntityStore>> restoreFlow;
    @Nullable
    private volatile CompanionQueries companions;

    CommandWorldChangeTravelCoordinator(
            CommandNpcRelocationService relocationService,
            CommandResolutionService resolutionService,
            CommandLinkMutationService linkMutationService,
            CommandCanonicalRecordCommitGate canonicalRecordCommitGate,
            CommandCompanionPlacementService placementService,
            @Nullable CommandNpcProfileActionResolver profileActionResolver,
            double defaultSafeSpawnDistance
    ) {
        this.relocationService = relocationService;
        this.resolutionService = resolutionService;
        this.linkMutationService = linkMutationService;
        this.canonicalRecordCommitGate = canonicalRecordCommitGate;
        this.placementService = placementService;
        this.profileActionResolver = profileActionResolver;
        this.defaultSafeSpawnDistance = defaultSafeSpawnDistance;
    }

    /** Supplies the restore path; null for either leaves world-change travel off. */
    void useRestoreFlow(@Nullable RestoreFlow<Ref<EntityStore>> restoreFlow,
                        @Nullable CompanionQueries companions) {
        this.restoreFlow = restoreFlow;
        this.companions = companions;
    }

    void queueForPlayerUuid(@Nullable World destinationWorld,
                            @Nullable UUID playerUuid) {
        if (destinationWorld == null || playerUuid == null
                || relocationService == null) {
            return;
        }
        dismountAfterWorldJoin(destinationWorld, playerUuid);
        Store<EntityStore> store = worldStore(destinationWorld);
        if (store == null) return;
        Ref<EntityStore> playerRef = destinationWorld.getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) return;
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player != null) queue(player, destinationWorld);
    }

    void dismountAfterWorldJoin(@Nullable World world,
                                @Nullable UUID playerUuid) {
        if (world == null || playerUuid == null) return;
        Store<EntityStore> store = worldStore(world);
        if (store == null) return;
        Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) return;
        Player player = store.getComponent(playerRef, Player.getComponentType());
        if (player != null && player.getMountEntityId() != 0) {
            MountPlugin.checkDismountNpc(store, playerRef, player);
        }
    }

    private void queue(Player player, World destinationWorld) {
        if (player == null || destinationWorld == null
                || player.getWorld() != destinationWorld) return;
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) return;
        Store<EntityStore> destinationStore = worldStore(destinationWorld);
        Ref<EntityStore> playerRef = player.getReference();
        if (destinationStore == null || playerRef == null
                || !playerRef.isValid()) return;
        if (restoreFlow == null || companions == null) return;
        if (CompanionDestinationAdmissionPolicy.assess(destinationWorld)
                != CompanionDestinationAdmissionPolicy.Decision.ALLOWED) return;

        ItemContainer hotbar = inventory.getHotbar();
        Set<UUID> queuedNpcUuids = new HashSet<>();
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            TwCommandItemConfig config = resolutionService.resolveConfig(
                    stack.getItemId(), null);
            if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)) {
                continue;
            }
            String toolId = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (toolId == null || toolId.isBlank()) continue;
            List<LinkedNpcRecord> linkedRecords =
                    linkMutationService.readLinkedNpcRecords(stack);
            if (linkedRecords.isEmpty()) continue;
            linkedRecords = canonicalizeBeforeTravel(
                    hotbar, slot, stack, linkedRecords);
            if (linkedRecords == null) continue;
            queueRecords(player, playerRef, destinationWorld,
                    destinationStore, linkedRecords, queuedNpcUuids);
        }
    }

    @Nullable
    private List<LinkedNpcRecord> canonicalizeBeforeTravel(
            ItemContainer hotbar,
            short slot,
            ItemStack stack,
            List<LinkedNpcRecord> linkedRecords
    ) {
        if (profileActionResolver == null) return linkedRecords;
        CommandNpcProfileActionResolver.CanonicalRecords canonical =
                profileActionResolver.canonicalizeRecords(linkedRecords);
        if (!canonical.safeToPersist()) return null;
        if (!canonical.identityChanged()) return canonical.records();
        ItemStack canonicalStack = linkMutationService.writeLinkedNpcRecords(
                stack, canonical.records());
        boolean committed = canonicalRecordCommitGate.commitBeforeAction(
                true,
                () -> {
                    ItemStackSlotTransaction transaction =
                            hotbar.setItemStackForSlot(slot, canonicalStack);
                    return transaction != null && transaction.succeeded();
                });
        return committed ? canonical.records() : null;
    }

    private void queueRecords(
            Player player,
            Ref<EntityStore> playerRef,
            World destinationWorld,
            Store<EntityStore> destinationStore,
            List<LinkedNpcRecord> linkedRecords,
            Set<UUID> queuedNpcUuids
    ) {
        for (LinkedNpcRecord cachedRecord : linkedRecords) {
            LinkedNpcRecord record = resolveRelocationRecord(cachedRecord);
            if (record == null || record.npcUuid == null || !record.active
                    || queuedNpcUuids.contains(record.npcUuid)) continue;
            String roleId = resolveTravelRoleId(record);
            TwCompanionConfig.EffectiveSettings settings =
                    TwCompanionConfig.resolveEffectiveForRole(roleId);
            if (!settings.isFollowMasterOnWorldChange()
                    || !CommandWorldChangeEligibility.isEligible(record, settings)) {
                continue;
            }
            if (queueRecord(player, playerRef, destinationWorld,
                    destinationStore, record, roleId, settings)) {
                queuedNpcUuids.add(record.npcUuid);
            }
        }
    }

    /** Runs on the destination world thread. Returns true once the companion was handed off. */
    private boolean queueRecord(
            Player player,
            Ref<EntityStore> playerRef,
            World destinationWorld,
            Store<EntityStore> destinationStore,
            LinkedNpcRecord record,
            @Nullable String roleId,
            TwCompanionConfig.EffectiveSettings settings
    ) {
        RestoreFlow<Ref<EntityStore>> flow = restoreFlow;
        CompanionQueries queries = companions;
        if (flow == null || queries == null) return false;
        CompanionRecord companion = resolveCompanion(queries, record);
        if (companion == null
                || !player.getUuid().equals(companion.ownerUuid())) return false;
        Ref<EntityStore> body = queries.loadedBody(companion.profileId());
        World sourceWorld = body != null ? body.getStore().getExternalData().getWorld() : null;
        if (sourceWorld == null) return false;
        Vector3d sourceHint = record.lastKnownPosition != null
                ? record.lastKnownPosition : record.homePosition;
        double safeSpawnDistance = settings.getRecallSafeSpawnDistance() > 0.0
                ? settings.getRecallSafeSpawnDistance()
                : defaultSafeSpawnDistance;
        if (sourceWorld == destinationWorld) {
            Vector3d destination = placementService.computeSafeRecallPosition(
                    playerRef, destinationStore, safeSpawnDistance, roleId, sourceHint);
            if (destination == null) return false;
            RelocationState state = resolveTravelRelocationState(record);
            relocationService.queueRelocation(
                    destinationWorld, record.npcUuid, destination, player.getUuid(),
                    true, true, state.state, state.subState, 0L, sourceHint,
                    record.homePosition, false, settings.getOnTransferFailure(),
                    settings.getFollowMasterOnWorldChangeStateFilter());
            return true;
        }
        CompanionSpawnPlacement placement = placementService.computeRestorationPlacement(
                playerRef, destinationStore, safeSpawnDistance, roleId, sourceHint);
        if (placement == null) return false;
        RestoreFlow.Destination destination = new RestoreFlow.Destination(
                placement.worldKey(), placement.x(), placement.y(), placement.z(),
                placement.yawRadians(), placement.pitchRadians());
        UUID profileId = companion.profileId();
        // The follow state lives on the body, so it is read on the body's own world thread.
        sourceWorld.execute(() -> {
            if (!body.isValid()) return;
            Store<EntityStore> sourceStore = body.getStore();
            NPCEntity npc = sourceStore.getComponent(body, NPCEntity.getComponentType());
            if (npc == null || !settings.isWorldChangeStateAllowed(
                    currentStateName(body, npc, sourceStore))) return;
            flow.restore(profileId, RestoreRules.Reason.RECALL, destination)
                    .thenAccept(result -> {
                        if (result != RestoreFlow.Result.RESTORED) {
                            LOGGER.log(Level.INFO, "World-change follow restore for profile "
                                    + profileId + " to " + destination.world()
                                    + " ended with " + result);
                        }
                    });
        });
        return true;
    }

    @Nullable
    private static CompanionRecord resolveCompanion(CompanionQueries queries,
                                                    LinkedNpcRecord record) {
        if (record.profileId != null && !record.profileId.isBlank()) {
            try {
                CompanionRecord byProfile = queries.get(UUID.fromString(record.profileId.trim()));
                if (byProfile != null) return byProfile;
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; fall back to the linked NPC UUID.
            }
        }
        return queries.byNpcUuid(record.npcUuid);
    }

    @Nullable
    private static String currentStateName(Ref<EntityStore> ref, NPCEntity npc,
                                           Store<EntityStore> store) {
        if (npc.getRole() == null) return null;
        StateSupport stateSupport = NpcSupportAccess.state(npc.getRole(), ref, store);
        String state = stateSupport != null ? stateSupport.getStateName() : null;
        return state != null && !state.isBlank() ? state : null;
    }

    @Nullable
    private LinkedNpcRecord resolveRelocationRecord(
            @Nullable LinkedNpcRecord record) {
        if (record == null || record.npcUuid == null
                || profileActionResolver == null) return record;
        CommandNpcProfileActionResolver.ActionTarget target =
                profileActionResolver.resolveRelocation(record);
        return target.isActionable() ? target.resolvedRecord() : null;
    }

    private RelocationState resolveTravelRelocationState(
            LinkedNpcRecord record) {
        if (record == null || record.cachedCommandState == null
                || record.cachedCommandState.isBlank()) {
            return new RelocationState(null, null);
        }
        String cachedState = record.cachedCommandState.trim();
        int separator = cachedState.indexOf('.');
        if (separator < 0) return new RelocationState(cachedState, null);
        String state = cachedState.substring(0, separator).trim();
        String subState = separator + 1 < cachedState.length()
                ? cachedState.substring(separator + 1).trim() : null;
        return new RelocationState(
                state.isBlank() ? null : state,
                subState == null || subState.isBlank() ? null : subState);
    }

    @Nullable
    private String resolveTravelRoleId(@Nullable LinkedNpcRecord record) {
        return record != null && record.cachedRoleId != null
                && !record.cachedRoleId.isBlank()
                ? record.cachedRoleId : null;
    }

    @Nullable
    private Store<EntityStore> worldStore(@Nullable World world) {
        return world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
    }
}
