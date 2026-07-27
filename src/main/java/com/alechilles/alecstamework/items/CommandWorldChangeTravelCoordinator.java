package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Resolves and queues generic linked-record companion travel after a player
 * enters a destination world.
 */
final class CommandWorldChangeTravelCoordinator {
    private final CommandNpcRelocationService relocationService;
    private final CommandResolutionService resolutionService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandCanonicalRecordCommitGate canonicalRecordCommitGate;
    private final CommandCompanionPlacementService placementService;
    @Nullable
    private final CommandNpcProfileActionResolver profileActionResolver;
    private final double defaultSafeSpawnDistance;

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

    private boolean queueRecord(
            Player player,
            Ref<EntityStore> playerRef,
            World destinationWorld,
            Store<EntityStore> destinationStore,
            LinkedNpcRecord record,
            @Nullable String roleId,
            TwCompanionConfig.EffectiveSettings settings
    ) {
        RelocationState state = resolveTravelRelocationState(record);
        Vector3d sourceHint = record.lastKnownPosition != null
                ? record.lastKnownPosition : record.homePosition;
        double safeSpawnDistance = settings.getRecallSafeSpawnDistance() > 0.0
                ? settings.getRecallSafeSpawnDistance()
                : defaultSafeSpawnDistance;
        Vector3d destination = placementService.computeSafeRecallPosition(
                playerRef, destinationStore, safeSpawnDistance, roleId, sourceHint);
        if (destination == null) return false;
        relocationService.queueRelocation(
                destinationWorld, record.npcUuid, destination, player.getUuid(),
                true, true, state.state, state.subState, 0L, sourceHint,
                record.homePosition, true, settings.getOnTransferFailure(),
                settings.getFollowMasterOnWorldChangeStateFilter());
        return true;
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
