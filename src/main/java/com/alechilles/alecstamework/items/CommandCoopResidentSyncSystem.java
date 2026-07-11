package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authoritative coop runtime sync.
 */
public final class CommandCoopResidentSyncSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 0L;

    private final CommandLinkedNpcCoopService coopService;
    private final CoopPopulationMutationService populationMutationService;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandNpcRelocationService relocationService;
    @Nullable
    private final CommandLinkedNpcLostService lostService;
    @Nullable
    private final CoopResidentStateSnapshotService coopStateSnapshotService;
    @Nullable
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType;
    @Nullable
    private final ComponentType<EntityStore, CoopResidentComponent> coopResidentType;
    @Nullable
    private final ComponentType<EntityStore, NPCEntity> npcType;
    @Nullable
    private final ComponentType<EntityStore, UUIDComponent> uuidType;

    private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);
    private final Set<UUID> releaseInFlight = ConcurrentHashMap.newKeySet();

    public CommandCoopResidentSyncSystem(@Nonnull CommandLinkedNpcCoopService coopService,
                                         @Nullable CommandLinkedNpcCaptureService captureService,
                                         @Nullable CommandNpcRelocationService relocationService,
                                         @Nullable CommandLinkedNpcLostService lostService,
                                         @Nullable CoopResidentStateSnapshotService coopStateSnapshotService,
                                         @Nullable ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType,
                                         @Nullable ComponentType<EntityStore, CoopResidentComponent> coopResidentType,
                                         @Nullable ComponentType<EntityStore, NPCEntity> npcType,
                                         @Nullable ComponentType<EntityStore, UUIDComponent> uuidType) {
        this.coopService = coopService;
        this.populationMutationService = new CoopPopulationMutationService(coopService);
        this.captureService = captureService;
        this.relocationService = relocationService;
        this.lostService = lostService;
        this.coopStateSnapshotService = coopStateSnapshotService;
        this.commandLinksType = commandLinksType;
        this.coopResidentType = coopResidentType;
        this.npcType = npcType;
        this.uuidType = uuidType;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (!hasEnabledManagedCoopConfigs()) {
            return;
        }
        TickState tickState = statesByStore.get(store);
        long nowMs = System.currentTimeMillis();
        if (nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;
        if (coopResidentType == null) {
            return;
        }
        store.forEachChunk(
                Query.and(coopResidentType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        syncChunk(chunk, store, commandBuffer)
        );
    }

    private void syncChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<EntityStore> reference = chunk.getReferenceTo(i);
            if (reference == null || !reference.isValid()) {
                continue;
            }
            CoopResidentComponent coopResident = chunk.getComponent(i, coopResidentType);
            if (coopResident == null) {
                continue;
            }
            NPCEntity npc = npcType != null ? chunk.getComponent(i, npcType) : null;
            UUID currentUuid = resolveSnapshotUuid(reference, store, npc);
            if (currentUuid == null) {
                continue;
            }
            String roleId = normalizeRoleId(npc);

            CoopResidentContext context = resolveCoopContext(store, coopResident);
            if (context == null) {
                continue;
            }
            if (isManagedCoop(context)) {
                continue;
            }
            int residentSlot = context.resolveResidentSlot(currentUuid, resolveUuidComponent(reference, store));

            if (coopResident.getMarkedForDespawn()) {
                CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot =
                        coopStateSnapshotService != null
                                ? coopStateSnapshotService.captureSnapshotForLedger(
                                reference,
                                store,
                                currentUuid,
                                context.coopId(),
                                residentSlot,
                                roleId
                        )
                                : null;
                TameworkCommandLinksComponent links = commandLinksType != null
                        ? chunk.getComponent(i, commandLinksType)
                        : null;
                if (residentSlot >= 0) {
                    CommandLinkedNpcCoopService.CoopSlotContext slotContext =
                            CommandLinkedNpcCoopService.CoopSlotContext.of(
                                    context.worldName(),
                                    context.coopId(),
                                    context.coopLocation().x,
                                    context.coopLocation().y,
                                    context.coopLocation().z,
                                    residentSlot
                            );
                    coopService.captureResident(
                            currentUuid,
                            roleId,
                            slotContext,
                            links != null ? links.getOwnerId() : null,
                            links != null ? links.getToolIds() : null,
                            null,
                            stateSnapshot
                    );
                    clearTransientState(currentUuid);
                } else {
                    boolean recaptured = coopService.recaptureResidentFromReleasedUuid(
                            currentUuid,
                            roleId,
                            context.worldName(),
                            context.coopId(),
                            context.coopLocation().x,
                            context.coopLocation().y,
                            context.coopLocation().z,
                            links != null ? links.getOwnerId() : null,
                            links != null ? links.getToolIds() : null,
                            null,
                            stateSnapshot
                    );
                    if (recaptured) {
                        clearTransientState(currentUuid);
                    } else {
                        debugCoop(
                                "capture miss unresolved slot npc=" + currentUuid
                                        + " coop=" + context.coopId()
                                        + " coords=" + context.coopLocation().x
                                        + "," + context.coopLocation().y
                                        + "," + context.coopLocation().z
                                        + " role=" + roleId
                        );
                    }
                }
                continue;
            }

            if (residentSlot < 0) {
                continue;
            }
            CommandLinkedNpcCoopService.CoopSlotContext slotContext =
                    CommandLinkedNpcCoopService.CoopSlotContext.of(
                            context.worldName(),
                            context.coopId(),
                            context.coopLocation().x,
                            context.coopLocation().y,
                            context.coopLocation().z,
                            residentSlot
                    );
            handleReleasedResident(reference, commandBuffer, store, currentUuid, roleId, slotContext, npc);
        }
    }

    private void handleReleasedResident(@Nonnull Ref<EntityStore> reference,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull UUID currentUuid,
                                        @Nullable String roleId,
                                        @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
                                        @Nullable NPCEntity npc) {
        TwCoopConfig config = TwCoopConfig.resolveForCoop(slotContext.coopId());
        boolean requireSnapshotOnRelease = config == null || config.getIdentityRules().isRequireSnapshotOnRelease();
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot ledger =
                coopService.getLedgerSlotSnapshot(slotContext);
        if ((ledger == null || ledger.housedNpcUuid() == null)
                && requireSnapshotOnRelease
                && bootstrapLegacyReleasedResident(reference, store, currentUuid, roleId, slotContext)) {
            ledger = coopService.getLedgerSlotSnapshot(slotContext);
        }
        if (ledger == null || ledger.housedNpcUuid() == null || roleId == null
                || !releaseInFlight.add(currentUuid)) {
            return;
        }
        populationMutationService.scheduleRelease(
                reference,
                store,
                currentUuid,
                roleId,
                slotContext,
                requireSnapshotOnRelease,
                new CoopPopulationMutationService.ReleaseCallbacks() {
                    @Override
                    public void onReleased(
                            @Nonnull CommandLinkedNpcCoopService.ReleaseResolution resolution,
                            @Nonnull String profileId
                    ) {
                        releaseInFlight.remove(currentUuid);
                        CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot =
                                resolution.stateSnapshot();
                        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot linkedSnapshot =
                                resolution.linkedSnapshot();
                        UUID previousUuid = resolution.previousNpcUuid();
                        if (previousUuid != null && !previousUuid.equals(currentUuid)) {
                            UUID ownerId = linkedSnapshot != null
                                    ? linkedSnapshot.ownerId()
                                    : stateSnapshot != null && stateSnapshot.owner() != null
                                    ? stateSnapshot.owner().getOwnerId()
                                    : null;
                            TameworkCommandLinksComponent links = stateSnapshot == null
                                    ? null
                                    : stateSnapshot.commandLinks();
                            remapLinkedRecords(store, previousUuid, currentUuid, ownerId, links);
                            clearTransientState(previousUuid);
                        }
                        debugCoop("release owner admission applied profile=" + profileId
                                + " current=" + currentUuid);
                    }

                    @Override
                    public void onDenied(@Nonnull String reason) {
                        releaseInFlight.remove(currentUuid);
                        despawnReleasedResident(store, currentUuid, npc);
                        debugCoop("release owner admission denied current=" + currentUuid
                                + " reason=" + reason);
                    }

                    @Override
                    public void onReconciled() {
                        releaseInFlight.remove(currentUuid);
                    }

                    @Override
                    public void onCompensated(@Nonnull String reason) {
                        releaseInFlight.remove(currentUuid);
                        despawnReleasedResident(store, currentUuid, npc);
                        debugCoop("release owner admission compensated current=" + currentUuid
                                + " reason=" + reason);
                    }
                }
        );
    }

    private static void despawnReleasedResident(@Nonnull Store<EntityStore> originalStore,
                                                @Nonnull UUID currentUuid,
                                                @Nullable NPCEntity fallbackNpc) {
        NPCEntity liveNpc = null;
        try {
            World world = originalStore.getExternalData() == null
                    ? null
                    : originalStore.getExternalData().getWorld();
            Store<EntityStore> liveStore = world == null || world.getEntityStore() == null
                    ? null
                    : world.getEntityStore().getStore();
            Ref<EntityStore> liveRef = world == null ? null : world.getEntityRef(currentUuid);
            if (liveStore != null && liveRef != null && liveRef.isValid()) {
                liveNpc = liveStore.getComponent(liveRef, NPCEntity.getComponentType());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The captured NPC remains a best-effort fallback for compensation cleanup.
        }
        NPCEntity target = liveNpc == null ? fallbackNpc : liveNpc;
        try {
            if (target != null) {
                target.setToDespawn();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The restored coop ledger remains canonical; lifecycle reconciliation handles residue.
        }
    }

    private boolean bootstrapLegacyReleasedResident(@Nonnull Ref<EntityStore> reference,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nonnull UUID currentUuid,
                                                    @Nullable String roleId,
                                                    @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext) {
        if (coopStateSnapshotService == null) {
            return false;
        }
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                coopStateSnapshotService.captureSnapshotForLedger(
                        reference,
                        store,
                        currentUuid,
                        slotContext.coopId(),
                        slotContext.residentSlot(),
                        roleId
                );
        if (snapshot == null) {
            return false;
        }

        TameworkCommandLinksComponent links = commandLinksType != null
                ? store.getComponent(reference, commandLinksType)
                : null;
        coopService.captureResident(
                currentUuid,
                roleId,
                slotContext,
                links != null ? links.getOwnerId() : null,
                links != null ? links.getToolIds() : null,
                null,
                snapshot
        );
        debugCoop(
                "release legacy bootstrap adopted current=" + currentUuid
                        + " coop=" + slotContext.coopId()
                        + " slot=" + slotContext.residentSlot()
        );
        return true;
    }

    @Nullable
    private UUID resolveSnapshotUuid(@Nonnull Ref<EntityStore> reference,
                                     @Nonnull Store<EntityStore> store,
                                     @Nullable NPCEntity npc) {
        UUID fromComponent = resolveUuidComponent(reference, store);
        if (fromComponent != null) {
            return fromComponent;
        }
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid();
        }
        return null;
    }

    @Nullable
    private UUID resolveUuidComponent(@Nonnull Ref<EntityStore> reference, @Nonnull Store<EntityStore> store) {
        if (uuidType == null) {
            return null;
        }
        UUIDComponent uuidComponent = store.getComponent(reference, uuidType);
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    @Nullable
    private String normalizeRoleId(@Nullable NPCEntity npc) {
        if (npc == null || npc.getRoleName() == null || npc.getRoleName().isBlank()) {
            return null;
        }
        return npc.getRoleName().trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private Player resolveOnlineOwner(@Nonnull Store<EntityStore> store, @Nullable UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(ownerId);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        try {
            return store.getComponent(playerRef, Player.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void remapLinkedRecords(@Nonnull Store<EntityStore> store,
                                    @Nullable UUID previousUuid,
                                    @Nullable UUID currentUuid,
                                    @Nullable UUID ownerId,
                                    @Nullable TameworkCommandLinksComponent links) {
        if (previousUuid == null || currentUuid == null || previousUuid.equals(currentUuid)) {
            return;
        }
        boolean remapped = false;
        Player ownerPlayer = resolveOnlineOwner(store, ownerId);
        if (ownerPlayer != null) {
            remapped = CommandLinkedNpcRecordRemapService.remapLinkedNpcRecordsInHotbar(ownerPlayer, previousUuid, currentUuid);
        }
        if (!remapped && links != null && links.getOwnerId() != null) {
            Player linkedOwner = resolveOnlineOwner(store, links.getOwnerId());
            if (linkedOwner != null) {
                remapped = CommandLinkedNpcRecordRemapService.remapLinkedNpcRecordsInHotbar(linkedOwner, previousUuid, currentUuid);
            }
        }
    }

    private void clearTransientState(@Nullable UUID uuid) {
        if (uuid == null) {
            return;
        }
        if (captureService != null) {
            captureService.clearCapturedSnapshot(uuid);
        }
        if (relocationService != null) {
            relocationService.cancelPendingRelocation(uuid);
        }
        if (lostService != null) {
            lostService.clearLostSnapshot(uuid);
        }
    }

    @Nullable
    private CoopResidentContext resolveCoopContext(@Nonnull Store<EntityStore> store,
                                                   @Nonnull CoopResidentComponent coopResident) {
        Vector3i coopLocation = coopResident.getCoopLocation();
        if (coopLocation == null) {
            return null;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || world.getChunkStore() == null) {
            return null;
        }
        WorldChunk worldChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(coopLocation.x, coopLocation.z));
        if (worldChunk == null) {
            return null;
        }
        Ref<ChunkStore> blockRef = worldChunk.getBlockComponentEntity(coopLocation.x, coopLocation.y, coopLocation.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        if (chunkStore == null) {
            return null;
        }
        CoopBlock coopBlock = chunkStore.getComponent(blockRef, CoopBlock.getComponentType());
        if (coopBlock == null) {
            return null;
        }
        FarmingCoopAsset coopAsset = coopBlock.getCoopAsset();
        return new CoopResidentContext(
                world.getName(),
                coopAsset != null ? coopAsset.getId() : null,
                coopLocation,
                coopBlock
        );
    }

    private void debugCoop(@Nonnull String message) {
        CoopDebugLogger.log(message);
    }

    private boolean isManagedCoop(@Nonnull CoopResidentContext context) {
        String coopId = context.coopId();
        if (coopId == null || coopId.isBlank()) {
            return false;
        }
        TwCoopConfig config = TwCoopConfig.resolveForCoop(coopId);
        return config != null && config.isEnabled();
    }

    private boolean hasEnabledManagedCoopConfigs() {
        DefaultAssetMap<String, TwCoopConfig> assetMap = TwCoopConfig.getAssetMap();
        if (assetMap == null) {
            return false;
        }
        Map<String, TwCoopConfig> configs = assetMap.getAssetMap();
        if (configs == null || configs.isEmpty()) {
            return false;
        }
        for (TwCoopConfig config : configs.values()) {
            if (config != null && config.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private record CoopResidentContext(@Nullable String worldName,
                                       @Nullable String coopId,
                                       @Nonnull Vector3i coopLocation,
                                       @Nonnull CoopBlock coopBlock) {
        int resolveResidentSlot(@Nullable UUID primaryUuid, @Nullable UUID secondaryUuid) {
            int primarySlot = CoopResidentSlotResolver.resolveResidentSlotByUuid(coopBlock, primaryUuid);
            if (primarySlot >= 0) {
                return primarySlot;
            }
            return CoopResidentSlotResolver.resolveResidentSlotByUuid(coopBlock, secondaryUuid);
        }
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}
