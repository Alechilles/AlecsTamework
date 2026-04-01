package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.spawning.ISpawnableWithModel;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import com.hypixel.hytale.server.spawning.SpawningContext;
import it.unimi.dsi.fastutil.Pair;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fully Tamework-owned coop runtime.
 *
 * <p>Capture/release decisions are based on configured coop block types and authoritative coop ledger state.
 */
public final class CommandCoopManagedWildCaptureSystem extends TickingSystem<ChunkStore> {
    private static final Logger LOGGER = Logger.getLogger("Alec's Tamework!");
    private static final long SWEEP_INTERVAL_MS = 250L;
    private static final long DEBUG_STATUS_INTERVAL_MS = 2_000L;
    private static final long CAPTURE_INTERVAL_MS = 350L;
    private static final long RELEASE_INTERVAL_MS = 350L;
    private static final long PRODUCE_CHECK_INTERVAL_MS = 2_000L;
    private static final long GAME_MILLIS_PER_HOUR = 3_600_000L;
    private static final double RELEASE_CONE_DEGREES = 100.0;
    private static final double RELEASE_MIN_DISTANCE = 1.0;
    private static final double RELEASE_MAX_DISTANCE = 3.0;
    private static final int RELEASE_SPAWN_ATTEMPTS = 16;
    private static final String COOP_INTERACTION_STATE_DEFAULT = "default";
    private static final String COOP_INTERACTION_STATE_PRODUCE_READY = "Produce_Ready";

    private static final String MODERN_BLOCK_MODULE_CLASS =
            "com.hypixel.hytale.server.core.modules.block.BlockModule";
    private static final String MODERN_ITEM_CONTAINER_BLOCK_CLASS =
            "com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock";
    private static final String MODERN_COOP_BLOCK_CLASS =
            "com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock";

    private final CommandLinkedNpcCoopService coopService;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandNpcRelocationService relocationService;
    @Nullable
    private final CommandLinkedNpcLostService lostService;
    @Nullable
    private final CoopResidentStateSnapshotService stateSnapshotService;

    private final HashMap<String, Long> nextCaptureAtByCoopKey = new HashMap<>();
    private final HashMap<String, Long> nextReleaseAtByCoopKey = new HashMap<>();
    private final HashMap<String, Long> nextProduceCheckAtByCoopKey = new HashMap<>();
    private final HashMap<String, Long> lastProduceAtGameMsBySlotKey = new HashMap<>();
    private final HashMap<String, Boolean> lastRoamingStateByCoopKey = new HashMap<>();
    private final HashSet<String> inFlightSlots = new HashSet<>();

    private long nextSweepAtMs;
    private long nextDebugStatusAtMs;

    @Nullable
    private volatile ComponentType<ChunkStore, ?> itemContainerComponentType;
    @Nullable
    private volatile ComponentType<ChunkStore, ?> coopBlockComponentType;
    @Nullable
    private volatile ComponentType<ChunkStore, ?> blockStateInfoComponentType;
    private volatile boolean apiResolved;
    private volatile boolean missingScanComponentTypeWarningLogged;

    public CommandCoopManagedWildCaptureSystem(@Nonnull CommandLinkedNpcCoopService coopService,
                                               @Nullable CommandLinkedNpcCaptureService captureService,
                                               @Nullable CommandNpcRelocationService relocationService,
                                               @Nullable CommandLinkedNpcLostService lostService,
                                               @Nullable CoopResidentStateSnapshotService stateSnapshotService) {
        this.coopService = coopService;
        this.captureService = captureService;
        this.relocationService = relocationService;
        this.lostService = lostService;
        this.stateSnapshotService = stateSnapshotService;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> chunkStore) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        World world = chunkStore.getExternalData() != null ? chunkStore.getExternalData().getWorld() : null;
        if (world == null || world.getEntityStore() == null || world.getChunkStore() == null) {
            maybeLogStatus(nowMs, "status skip=world_or_store_missing");
            return;
        }
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        if (entityStore == null) {
            maybeLogStatus(
                    nowMs,
                    "world=" + firstNonBlank(world.getName(), "<unknown>") + " status skip=entity_store_missing"
            );
            return;
        }
        WorldTimeResource worldTime = entityStore.getResource(WorldTimeResource.getResourceType());
        if (worldTime == null) {
            maybeLogStatus(
                    nowMs,
                    "world=" + firstNonBlank(world.getName(), "<unknown>") + " status skip=world_time_missing"
            );
            return;
        }

        CoopScanDiagnostics scanDiagnostics = new CoopScanDiagnostics();
        ArrayList<ManagedCoopContext> managedCoops = collectManagedCoops(chunkStore, world, scanDiagnostics);
        if (managedCoops.isEmpty()) {
            maybeLogStatus(
                    nowMs,
                    "world=" + firstNonBlank(world.getName(), "<unknown>")
                            + " status coops=0 candidates=0 scan=" + scanDiagnostics.toSummary()
            );
            return;
        }

        HashSet<String> activeCoopKeys = new HashSet<>();
        for (ManagedCoopContext coop : managedCoops) {
            activeCoopKeys.add(coop.coopKey());
        }
        pruneRuntimeState(activeCoopKeys);

        ArrayList<NpcCandidate> npcCandidates = collectNpcCandidates(entityStore);
        HashSet<UUID> consumedNpcs = new HashSet<>();
        int roamingCoops = 0;

        for (ManagedCoopContext coop : managedCoops) {
            TwCoopConfig.LifecycleRules lifecycleRules = coop.config().getLifecycleRules();
            boolean residentsRoaming = shouldResidentsRoam(worldTime, lifecycleRules);
            boolean wasRoaming = lastRoamingStateByCoopKey.getOrDefault(coop.coopKey(), false);
            lastRoamingStateByCoopKey.put(coop.coopKey(), residentsRoaming);
            if (residentsRoaming) {
                roamingCoops++;
                maybeReleaseOneResident(world, entityStore, coop, nowMs);
                if (!wasRoaming) {
                    maybeProduce(worldTime, coop, nowMs);
                }
                syncCoopInteractionState(world, coop);
                continue;
            }
            maybeCaptureOneResident(entityStore, coop, npcCandidates, consumedNpcs, nowMs);
            syncCoopInteractionState(world, coop);
        }
        maybeLogStatus(
                nowMs,
                "world=" + firstNonBlank(world.getName(), "<unknown>")
                        + " status coops=" + managedCoops.size()
                        + " candidates=" + npcCandidates.size()
                        + " roaming=" + roamingCoops
                        + " enclosed=" + (managedCoops.size() - roamingCoops)
                        + " capturedThisSweep=" + consumedNpcs.size()
                        + " scan=" + scanDiagnostics.toSummary()
        );
    }

    private void pruneRuntimeState(@Nonnull Set<String> activeCoopKeys) {
        nextCaptureAtByCoopKey.keySet().retainAll(activeCoopKeys);
        nextReleaseAtByCoopKey.keySet().retainAll(activeCoopKeys);
        nextProduceCheckAtByCoopKey.keySet().retainAll(activeCoopKeys);
        lastRoamingStateByCoopKey.keySet().retainAll(activeCoopKeys);
        inFlightSlots.removeIf(slotKey -> !isActiveSlotKey(slotKey, activeCoopKeys));
        lastProduceAtGameMsBySlotKey.keySet().removeIf(slotKey -> !isActiveSlotKey(slotKey, activeCoopKeys));
    }

    private boolean isActiveSlotKey(@Nullable String slotKey, @Nonnull Set<String> activeCoopKeys) {
        if (slotKey == null) {
            return false;
        }
        int separator = slotKey.lastIndexOf("|slot=");
        if (separator <= 0) {
            return false;
        }
        return activeCoopKeys.contains(slotKey.substring(0, separator));
    }

    @Nonnull
    private ArrayList<ManagedCoopContext> collectManagedCoops(@Nonnull Store<ChunkStore> chunkStore,
                                                               @Nonnull World world,
                                                               @Nonnull CoopScanDiagnostics diagnostics) {
        ArrayList<ManagedCoopContext> out = new ArrayList<>();
        ComponentType<ChunkStore, ?> itemContainerType = resolveItemContainerComponentType();
        ComponentType<ChunkStore, ?> coopType = resolveCoopBlockComponentType();
        if (itemContainerType == null && coopType == null) {
            diagnostics.containerTypeMissing = true;
            if (!missingScanComponentTypeWarningLogged) {
                missingScanComponentTypeWarningLogged = true;
                LOGGER.log(
                        Level.WARNING,
                        "Managed coop runtime could not resolve coop block component type; "
                                + "capture/release scans are disabled."
                );
            }
            return out;
        }
        HashSet<String> seen = new HashSet<>();
        if (itemContainerType != null) {
            scanManagedCoopsByComponentType(chunkStore, itemContainerType, world, seen, out, diagnostics);
        }
        if (coopType != null && coopType != itemContainerType) {
            scanManagedCoopsByComponentType(chunkStore, coopType, world, seen, out, diagnostics);
        }
        if (itemContainerType != null || coopType != null) {
            missingScanComponentTypeWarningLogged = false;
        }
        return out;
    }

    private void scanManagedCoopsByComponentType(@Nonnull Store<ChunkStore> chunkStore,
                                                 @Nonnull ComponentType<ChunkStore, ?> blockStateType,
                                                 @Nonnull World world,
                                                 @Nonnull Set<String> seen,
                                                 @Nonnull ArrayList<ManagedCoopContext> out,
                                                 @Nonnull CoopScanDiagnostics diagnostics) {
        chunkStore.forEachChunk(
                Query.and(castComponentTypeUnchecked(blockStateType)),
                (ArchetypeChunk<ChunkStore> chunk, CommandBuffer<ChunkStore> commandBuffer) ->
                        collectManagedCoopsFromChunk(chunkStore, chunk, blockStateType, world, seen, out, diagnostics)
        );
    }

    private void collectManagedCoopsFromChunk(@Nonnull Store<ChunkStore> chunkStore,
                                              @Nonnull ArchetypeChunk<ChunkStore> chunk,
                                              @Nonnull ComponentType<ChunkStore, ?> blockStateType,
                                              @Nonnull World world,
                                              @Nonnull Set<String> seen,
                                              @Nonnull ArrayList<ManagedCoopContext> out,
                                              @Nonnull CoopScanDiagnostics diagnostics) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            diagnostics.scanned++;
            Ref<ChunkStore> reference = chunk.getReferenceTo(i);
            if (reference == null || !reference.isValid()) {
                diagnostics.invalidRef++;
                continue;
            }
            Object state = chunk.getComponent(i, castComponentType(blockStateType));
            if (state == null) {
                diagnostics.missingState++;
                continue;
            }
            diagnostics.withContainerState++;
            CoopLocation location = resolveCoopLocation(reference, chunkStore);
            if (location == null || location.blockTypeId() == null) {
                diagnostics.missingLocation++;
                continue;
            }
                diagnostics.withLocation++;
            String coopAssetId = resolveCoopAssetId(state);
            TwCoopConfig config = resolveCoopConfig(location.blockTypeId(), coopAssetId);
            if (config == null || !config.isEnabled()) {
                diagnostics.configMiss++;
                continue;
            }
            diagnostics.withConfig++;
            String coopId = normalizeIdentifier(firstNonBlank(config.getCoopId(), coopAssetId, location.blockTypeId()));
            if (coopId == null) {
                diagnostics.missingCoopId++;
                continue;
            }
            ItemContainer container = FeedTroughContainerCompat.getItemContainer(state);
            String coopKey = buildCoopKey(world.getName(), location.block(), coopId);
            if (!seen.add(coopKey)) {
                diagnostics.duplicateKey++;
                continue;
            }
            diagnostics.added++;
            out.add(new ManagedCoopContext(
                    normalizeIdentifier(world.getName()),
                    coopId,
                    location.block(),
                    config,
                    container,
                    coopKey
            ));
        }
    }

    @Nonnull
    private ArrayList<NpcCandidate> collectNpcCandidates(@Nonnull Store<EntityStore> entityStore) {
        ArrayList<NpcCandidate> out = new ArrayList<>();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (npcType == null || transformType == null) {
            return out;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType =
                TameworkNpcNameComponent.getComponentType();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                TameworkTamedComponent.getComponentType();

        entityStore.forEachChunk(
                Query.and(npcType, transformType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> reference = chunk.getReferenceTo(i);
                        if (reference == null || !reference.isValid()) {
                            continue;
                        }
                        NPCEntity npc = chunk.getComponent(i, npcType);
                        TransformComponent transform = chunk.getComponent(i, transformType);
                        if (npc == null || transform == null) {
                            continue;
                        }
                        UUID npcUuid = npc.getUuid();
                        if (npcUuid == null && uuidType != null) {
                            UUIDComponent uuidComponent = chunk.getComponent(i, uuidType);
                            npcUuid = uuidComponent != null ? uuidComponent.getUuid() : null;
                        }
                        if (npcUuid == null || coopService.getCoopSnapshot(npcUuid) != null) {
                            continue;
                        }
                        Vector3d position = transform.getPosition();
                        if (position == null || !isFinite(position.x) || !isFinite(position.y) || !isFinite(position.z)) {
                            continue;
                        }
                        String roleId = normalizeIdentifier(npc.getRoleName());
                        if (roleId == null) {
                            continue;
                        }
                        TameworkCommandLinksComponent links = linksType != null ? chunk.getComponent(i, linksType) : null;
                        TameworkOwnerComponent owner = ownerType != null ? chunk.getComponent(i, ownerType) : null;
                        TameworkNpcNameComponent npcName = nameType != null ? chunk.getComponent(i, nameType) : null;
                        TameworkTamedComponent tamed = tamedType != null ? chunk.getComponent(i, tamedType) : null;
                        UUID ownerId = links != null && links.getOwnerId() != null
                                ? links.getOwnerId()
                                : owner != null
                                ? owner.getOwnerId()
                                : null;
                        String[] toolIds = links != null ? links.getToolIds() : null;
                        String displayName = npcName != null ? npcName.getName() : null;
                        out.add(new NpcCandidate(
                                reference,
                                npcUuid,
                                roleId,
                                position.x,
                                position.y,
                                position.z,
                                ownerId,
                                toolIds,
                                displayName,
                                tamed != null && tamed.isTamed()
                        ));
                    }
                }
        );
        return out;
    }

    private void maybeCaptureOneResident(@Nonnull Store<EntityStore> entityStore,
                                         @Nonnull ManagedCoopContext coop,
                                         @Nonnull ArrayList<NpcCandidate> candidates,
                                         @Nonnull Set<UUID> consumedNpcs,
                                         long nowMs) {
        long nextAllowedAt = nextCaptureAtByCoopKey.getOrDefault(coop.coopKey(), 0L);
        if (nowMs < nextAllowedAt) {
            return;
        }

        TwCoopConfig.LifecycleRules lifecycleRules = coop.config().getLifecycleRules();
        if (!lifecycleRules.isCaptureWildNPCsInRange()) {
            nextCaptureAtByCoopKey.put(coop.coopKey(), nowMs + CAPTURE_INTERVAL_MS);
            return;
        }
        int slot = findFirstEmptySlot(coop);
        if (slot < 0) {
            nextCaptureAtByCoopKey.put(coop.coopKey(), nowMs + CAPTURE_INTERVAL_MS);
            return;
        }
        NpcCandidate candidate = selectNearestCaptureCandidate(coop, lifecycleRules, candidates, consumedNpcs);
        if (candidate == null || !candidate.reference().isValid()) {
            nextCaptureAtByCoopKey.put(coop.coopKey(), nowMs + CAPTURE_INTERVAL_MS);
            return;
        }

        CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot = stateSnapshotService != null
                ? stateSnapshotService.captureSnapshotForLedger(
                candidate.reference(),
                entityStore,
                candidate.npcUuid(),
                coop.coopId(),
                slot,
                candidate.roleId()
        )
                : null;

        CommandLinkedNpcCoopService.CoopSlotContext slotContext = coop.slotContext(slot);
        coopService.captureResident(
                candidate.npcUuid(),
                candidate.roleId(),
                slotContext,
                candidate.ownerId(),
                candidate.toolIds(),
                candidate.displayName(),
                stateSnapshot
        );
        clearTransientState(candidate.npcUuid());
        consumedNpcs.add(candidate.npcUuid());

        NPCEntity npcEntity = safeGetEntityComponent(entityStore, candidate.reference(), NPCEntity.getComponentType());
        if (npcEntity != null) {
            npcEntity.setToDespawn();
        }

        CoopDebugLogger.log(
                "managed coop capture npc=" + candidate.npcUuid()
                        + " role=" + candidate.roleId()
                        + " coop=" + coop.coopId()
                        + " slot=" + slot
                        + " pos=" + coop.block().x + "," + coop.block().y + "," + coop.block().z
        );
        nextCaptureAtByCoopKey.put(coop.coopKey(), nowMs + CAPTURE_INTERVAL_MS);
    }

    private void maybeReleaseOneResident(@Nonnull World world,
                                         @Nonnull Store<EntityStore> entityStore,
                                         @Nonnull ManagedCoopContext coop,
                                         long nowMs) {
        long nextAllowedAt = nextReleaseAtByCoopKey.getOrDefault(coop.coopKey(), 0L);
        if (nowMs < nextAllowedAt) {
            return;
        }
        int slot = findFirstHousedSlot(coop);
        if (slot < 0) {
            nextReleaseAtByCoopKey.put(coop.coopKey(), nowMs + RELEASE_INTERVAL_MS);
            return;
        }
        String inFlightKey = coop.coopKey() + "|slot=" + slot;
        if (!inFlightSlots.add(inFlightKey)) {
            return;
        }
        try {
            releaseResident(world, entityStore, coop, slot);
        } finally {
            inFlightSlots.remove(inFlightKey);
            nextReleaseAtByCoopKey.put(coop.coopKey(), nowMs + RELEASE_INTERVAL_MS);
        }
    }

    private boolean releaseResident(@Nonnull World world,
                                    @Nonnull Store<EntityStore> entityStore,
                                    @Nonnull ManagedCoopContext coop,
                                    int slot) {
        CommandLinkedNpcCoopService.CoopSlotContext slotContext = coop.slotContext(slot);
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot ledgerSnapshot =
                coopService.getLedgerSlotSnapshot(slotContext);
        if (ledgerSnapshot == null || ledgerSnapshot.housedNpcUuid() == null) {
            return false;
        }
        String roleId = normalizeIdentifier(firstNonBlank(
                ledgerSnapshot.roleId(),
                ledgerSnapshot.stateSnapshot() != null ? ledgerSnapshot.stateSnapshot().roleId() : null
        ));
        if (roleId == null) {
            return false;
        }

        UUID housedUuid = ledgerSnapshot.housedNpcUuid();
        Ref<EntityStore> existingRef = world.getEntityRef(housedUuid);
        if (existingRef != null && existingRef.isValid()) {
            NPCEntity existingNpc = safeGetEntityComponent(entityStore, existingRef, NPCEntity.getComponentType());
            if (existingNpc != null) {
                return resolveReleaseAndRestore(
                        world,
                        entityStore,
                        existingRef,
                        existingNpc,
                        housedUuid,
                        roleId,
                        slotContext,
                        coop.config().getIdentityRules().isRequireSnapshotOnRelease()
                );
            }
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return false;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return false;
        }
        Builder<Role> roleBuilder = npcPlugin.tryGetCachedValidRole(roleIndex);
        if (roleBuilder == null) {
            return false;
        }

        Vector3d spawnPosition = resolveSpawnPosition(
                world,
                roleBuilder,
                coop.block(),
                coop.config().getLifecycleRules().getResidentSpawnOffset()
        );
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                entityStore,
                roleIndex,
                spawnPosition,
                new Vector3f(),
                null,
                null
        );
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return false;
        }
        Ref<EntityStore> spawnedRef = spawned.first();
        NPCEntity spawnedNpc = spawned.second();
        UUID currentUuid = resolveEntityUuid(spawnedNpc, spawnedRef, entityStore);
        if (currentUuid == null) {
            spawnedNpc.setToDespawn();
            return false;
        }
        return resolveReleaseAndRestore(
                world,
                entityStore,
                spawnedRef,
                spawnedNpc,
                currentUuid,
                roleId,
                slotContext,
                coop.config().getIdentityRules().isRequireSnapshotOnRelease()
        );
    }

    private boolean resolveReleaseAndRestore(@Nonnull World world,
                                             @Nonnull Store<EntityStore> entityStore,
                                             @Nonnull Ref<EntityStore> targetRef,
                                             @Nonnull NPCEntity targetNpc,
                                             @Nonnull UUID currentUuid,
                                             @Nullable String roleId,
                                             @Nonnull CommandLinkedNpcCoopService.CoopSlotContext slotContext,
                                             boolean requireSnapshotOnRelease) {
        CommandLinkedNpcCoopService.ReleaseResolution releaseResolution =
                coopService.resolveRelease(currentUuid, roleId, slotContext, requireSnapshotOnRelease);
        if (releaseResolution.isFailure() && requireSnapshotOnRelease) {
            // Never leave residents in limbo: fallback release path without strict snapshot requirement.
            releaseResolution = coopService.resolveRelease(currentUuid, roleId, slotContext, false);
        }
        if (releaseResolution.isFailure()) {
            targetNpc.setToDespawn();
            return false;
        }
        if (releaseResolution.alreadyReconciled()) {
            return true;
        }

        CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot = releaseResolution.stateSnapshot();
        if (stateSnapshot != null) {
            queueSnapshotApply(world, targetRef, stateSnapshot, null);
        } else {
            queueSnapshotApply(world, targetRef, null, releaseResolution.linkedSnapshot());
        }

        UUID previousUuid = releaseResolution.previousNpcUuid();
        if (previousUuid != null && !previousUuid.equals(currentUuid)) {
            UUID ownerId = resolveOwnerIdForRemap(releaseResolution.linkedSnapshot(), stateSnapshot);
            TameworkCommandLinksComponent links =
                    stateSnapshot != null ? stateSnapshot.commandLinks() : null;
            remapLinkedRecords(previousUuid, currentUuid, ownerId, links);
            clearTransientState(previousUuid);
        }
        clearTransientState(currentUuid);
        return true;
    }

    private void applySnapshotDirect(@Nonnull Ref<EntityStore> reference,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        safePutComponent(store, reference, TameworkCommandLinksComponent.getComponentType(), snapshot.commandLinks());
        safePutComponent(store, reference, TameworkOwnerComponent.getComponentType(), snapshot.owner());
        safePutComponent(store, reference, TameworkTamedComponent.getComponentType(), snapshot.tamed());
        safePutComponent(store, reference, TameworkNpcNameComponent.getComponentType(), snapshot.npcName());
        safePutComponent(store, reference, com.alechilles.alecstamework.npc.components.TameworkHappinessComponent.getComponentType(), snapshot.happiness());
        safePutComponent(store, reference, com.alechilles.alecstamework.npc.components.TameworkNeedsComponent.getComponentType(), snapshot.needs());
        safePutComponent(store, reference, com.alechilles.alecstamework.npc.components.TameworkBreedingComponent.getComponentType(), snapshot.breeding());
        safePutComponent(store, reference, com.alechilles.alecstamework.npc.components.TameworkTraitsComponent.getComponentType(), snapshot.traits());
        safePutComponent(store, reference, com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent.getComponentType(), snapshot.lifeStage());
        safePutComponent(store, reference, com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent.getComponentType(), snapshot.attachments());
        if (snapshot.npcName() != null && snapshot.npcName().getName() != null && !snapshot.npcName().getName().isBlank()) {
            safePutComponent(
                    store,
                    reference,
                    DisplayNameComponent.getComponentType(),
                    new DisplayNameComponent(Message.raw(snapshot.npcName().getName()))
            );
        }
    }

    private void applyLinkedSnapshotFallback(@Nonnull Ref<EntityStore> reference,
                                             @Nonnull Store<EntityStore> store,
                                             @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String[] toolIds = snapshot.toolIds();
        if (toolIds != null && toolIds.length > 0) {
            safePutComponent(
                    store,
                    reference,
                    TameworkCommandLinksComponent.getComponentType(),
                    new TameworkCommandLinksComponent(snapshot.ownerId(), toolIds)
            );
        }
        if (snapshot.ownerId() != null) {
            safePutComponent(
                    store,
                    reference,
                    TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(snapshot.ownerId(), null)
            );
        }
        if (snapshot.displayName() != null && !snapshot.displayName().isBlank()) {
            safePutComponent(
                    store,
                    reference,
                    TameworkNpcNameComponent.getComponentType(),
                    new TameworkNpcNameComponent(
                            snapshot.displayName(),
                            snapshot.ownerId(),
                            System.currentTimeMillis(),
                            TameworkNpcNameComponent.NameSource.System
                    )
            );
            safePutComponent(
                    store,
                    reference,
                    DisplayNameComponent.getComponentType(),
                    new DisplayNameComponent(Message.raw(snapshot.displayName()))
            );
        }
    }

    private void queueSnapshotApply(@Nonnull World world,
                                    @Nonnull Ref<EntityStore> reference,
                                    @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot,
                                    @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot linkedSnapshot) {
        try {
            world.execute(() -> {
                if (!reference.isValid()) {
                    return;
                }
                Store<EntityStore> deferredStore =
                        world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
                if (deferredStore == null) {
                    return;
                }
                if (stateSnapshot != null) {
                    applySnapshotDirect(reference, deferredStore, stateSnapshot);
                    String displayName = stateSnapshot.npcName() != null ? stateSnapshot.npcName().getName() : null;
                    applyEntitySupportDisplayNameIfPresent(reference, deferredStore, displayName);
                } else {
                    applyLinkedSnapshotFallback(reference, deferredStore, linkedSnapshot);
                    String displayName = linkedSnapshot != null ? linkedSnapshot.displayName() : null;
                    applyEntitySupportDisplayNameIfPresent(reference, deferredStore, displayName);
                }
            });
        } catch (RuntimeException ignored) {
            // World may be shutting down; skip deferred restore safely.
        }
    }

    private void applyEntitySupportDisplayNameIfPresent(@Nonnull Ref<EntityStore> reference,
                                                         @Nonnull Store<EntityStore> store,
                                                         @Nullable String displayName) {
        if (displayName == null || displayName.isBlank() || !reference.isValid()) {
            return;
        }
        try {
            EntitySupport.setDisplayName(reference, displayName, store);
        } catch (IllegalStateException ignored) {
            // DisplayNameComponent fallback is already applied; this path is best-effort.
        }
    }

    private void maybeProduce(@Nonnull WorldTimeResource worldTime,
                              @Nonnull ManagedCoopContext coop,
                              long nowMs) {
        long nextCheckAt = nextProduceCheckAtByCoopKey.getOrDefault(coop.coopKey(), 0L);
        if (nowMs < nextCheckAt) {
            return;
        }
        nextProduceCheckAtByCoopKey.put(coop.coopKey(), nowMs + PRODUCE_CHECK_INTERVAL_MS);

        ItemContainer container = coop.container();
        if (container == null) {
            return;
        }
        TwCoopConfig.ProduceRules produceRules = coop.config().getProduceRules();
        Map<String, String> dropsByRole = normalizeDropsByRole(produceRules.getDropsByRole());
        if (dropsByRole.isEmpty()) {
            return;
        }
        long nowGameMs = resolveGameTimeMs(worldTime);
        long intervalHours = Math.max(WorldTimeResource.HOURS_PER_DAY, produceRules.getIntervalGameHours());
        long intervalMs = intervalHours * GAME_MILLIS_PER_HOUR;
        int itemsPerTick = Math.max(1, produceRules.getItemsPerTick());
        DefaultAssetMap<String, ItemDropList> dropListAssetMap = ItemDropList.getAssetMap();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int maxResidents = coop.config().getLifecycleRules().getMaxResidents();
        for (int slot = 0; slot < maxResidents; slot++) {
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot slotSnapshot = coopService.getLedgerSlotSnapshot(coop.slotContext(slot));
            if (slotSnapshot == null || slotSnapshot.housedNpcUuid() == null) {
                continue;
            }
            String roleId = normalizeIdentifier(firstNonBlank(
                    slotSnapshot.roleId(),
                    slotSnapshot.stateSnapshot() != null ? slotSnapshot.stateSnapshot().roleId() : null
            ));
            if (roleId == null) {
                continue;
            }
            String dropItemId = dropsByRole.get(roleId);
            if (dropItemId == null || dropItemId.isBlank()) {
                continue;
            }
            String slotKey = coop.coopKey() + "|slot=" + slot;
            long lastProducedAt = lastProduceAtGameMsBySlotKey.getOrDefault(slotKey, nowGameMs);
            if (lastProducedAt <= 0L || lastProducedAt > nowGameMs) {
                // Seed unknown slots as one interval elapsed so established residents can
                // produce on the next valid roaming-cycle trigger (matches vanilla expectations).
                lastProducedAt = nowGameMs - intervalMs;
                lastProduceAtGameMsBySlotKey.put(slotKey, lastProducedAt);
            }
            long elapsedHours = (nowGameMs - lastProducedAt) / GAME_MILLIS_PER_HOUR;
            if (elapsedHours < intervalHours) {
                continue;
            }
            int produceCycles = (int) Math.max(
                    1L,
                    (long) Math.ceil((double) elapsedHours / (double) intervalHours)
            );
            ItemDropList dropList = resolveProduceDropList(dropListAssetMap, dropItemId);
            boolean containerSaturated = false;
            for (int cycle = 0; cycle < produceCycles && !containerSaturated; cycle++) {
                for (int i = 0; i < itemsPerTick; i++) {
                    if (!generateProduceIntoContainer(container, dropList, dropItemId, random)) {
                        containerSaturated = true;
                        break;
                    }
                }
            }
            lastProduceAtGameMsBySlotKey.put(slotKey, nowGameMs);
            if (containerSaturated) {
                break;
            }
        }
    }

    private boolean generateProduceIntoContainer(@Nonnull ItemContainer container,
                                                 @Nullable ItemDropList dropList,
                                                 @Nonnull String dropReferenceId,
                                                 @Nonnull ThreadLocalRandom random) {
        if (dropList == null || dropList.getContainer() == null) {
            return tryAddItemStack(container, new ItemStack(dropReferenceId, 1));
        }
        ArrayList<ItemDrop> drops = new ArrayList<>();
        dropList.getContainer().populateDrops(drops, random::nextDouble, dropReferenceId);
        for (ItemDrop drop : drops) {
            if (drop == null || drop.getItemId() == null || drop.getItemId().isBlank()) {
                continue;
            }
            int amount = drop.getRandomQuantity(random);
            if (amount <= 0) {
                continue;
            }
            ItemStack stack = new ItemStack(drop.getItemId(), amount, drop.getMetadata());
            if (!tryAddItemStack(container, stack)) {
                return false;
            }
        }
        return true;
    }

    private boolean tryAddItemStack(@Nonnull ItemContainer container, @Nonnull ItemStack itemStack) {
        ItemStackTransaction transaction = container.addItemStack(itemStack);
        if (transaction == null) {
            return false;
        }
        ItemStack remainder = transaction.getRemainder();
        return remainder == null || remainder.isEmpty();
    }

    private void syncCoopInteractionState(@Nonnull World world, @Nonnull ManagedCoopContext coop) {
        ItemContainer container = coop.container();
        if (container == null) {
            return;
        }
        WorldChunk worldChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(coop.block().x, coop.block().z));
        if (worldChunk == null) {
            return;
        }
        BlockType blockType = worldChunk.getBlockType(coop.block().x, coop.block().y, coop.block().z);
        if (blockType == null) {
            return;
        }
        String interactionState = container.isEmpty()
                ? COOP_INTERACTION_STATE_DEFAULT
                : COOP_INTERACTION_STATE_PRODUCE_READY;
        try {
            worldChunk.setBlockInteractionState(coop.block(), blockType, interactionState);
        } catch (RuntimeException ignored) {
            // Avoid hard failures if a world/chunk update races this sync.
        }
    }

    @Nullable
    private ItemDropList resolveProduceDropList(@Nullable DefaultAssetMap<String, ItemDropList> dropListAssetMap,
                                                @Nonnull String dropReferenceId) {
        if (dropListAssetMap == null) {
            return null;
        }
        ItemDropList dropList = dropListAssetMap.getAsset(dropReferenceId);
        if (dropList != null) {
            return dropList;
        }
        Map<String, ItemDropList> assets = dropListAssetMap.getAssetMap();
        if (assets == null || assets.isEmpty()) {
            return null;
        }
        ItemDropList direct = assets.get(dropReferenceId);
        if (direct != null) {
            return direct;
        }
        String normalizedReference = normalizeIdentifier(dropReferenceId);
        if (normalizedReference == null) {
            return null;
        }
        for (Map.Entry<String, ItemDropList> entry : assets.entrySet()) {
            if (normalizedReference.equals(normalizeIdentifier(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Nonnull
    private Map<String, String> normalizeDropsByRole(@Nullable Map<String, String> dropsByRole) {
        if (dropsByRole == null || dropsByRole.isEmpty()) {
            return Map.of();
        }
        HashMap<String, String> normalized = new HashMap<>();
        for (Map.Entry<String, String> entry : dropsByRole.entrySet()) {
            String key = normalizeIdentifier(entry.getKey());
            String value = entry.getValue();
            if (key == null || value == null || value.isBlank()) {
                continue;
            }
            normalized.put(key, value);
        }
        return normalized;
    }

    private int findFirstEmptySlot(@Nonnull ManagedCoopContext coop) {
        int maxResidents = coop.config().getLifecycleRules().getMaxResidents();
        for (int slot = 0; slot < maxResidents; slot++) {
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot slotSnapshot =
                    coopService.getLedgerSlotSnapshot(coop.slotContext(slot));
            if (slotSnapshot == null || slotSnapshot.housedNpcUuid() == null) {
                return slot;
            }
        }
        return -1;
    }

    private int findFirstHousedSlot(@Nonnull ManagedCoopContext coop) {
        int maxResidents = coop.config().getLifecycleRules().getMaxResidents();
        for (int slot = 0; slot < maxResidents; slot++) {
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot slotSnapshot =
                    coopService.getLedgerSlotSnapshot(coop.slotContext(slot));
            if (slotSnapshot != null && slotSnapshot.housedNpcUuid() != null) {
                return slot;
            }
        }
        return -1;
    }

    @Nullable
    private NpcCandidate selectNearestCaptureCandidate(@Nonnull ManagedCoopContext coop,
                                                       @Nonnull TwCoopConfig.LifecycleRules lifecycleRules,
                                                       @Nonnull ArrayList<NpcCandidate> candidates,
                                                       @Nonnull Set<UUID> consumedNpcs) {
        double radius = lifecycleRules.getWildCaptureRadius();
        if (radius <= 0.0) {
            return null;
        }
        double radiusSq = radius * radius;
        double centerX = coop.block().x + 0.5;
        double centerY = coop.block().y + 0.5;
        double centerZ = coop.block().z + 0.5;

        Set<String> acceptedRoles = normalizeRoleSet(lifecycleRules.getAcceptedRoleIds());
        boolean requireTamed = coop.config().getCapturePolicy().isRequireTamed();

        NpcCandidate best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        for (NpcCandidate candidate : candidates) {
            if (candidate == null || consumedNpcs.contains(candidate.npcUuid())) {
                continue;
            }
            if (!acceptedRoles.isEmpty() && !acceptedRoles.contains(candidate.roleId())) {
                continue;
            }
            if (requireTamed && !candidate.tamed()) {
                continue;
            }
            double dx = candidate.x() - centerX;
            double dy = candidate.y() - centerY;
            double dz = candidate.z() - centerZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distanceSq) || distanceSq > radiusSq) {
                continue;
            }
            if (distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = candidate;
        }
        return best;
    }

    @Nonnull
    private Vector3d resolveSpawnPosition(@Nonnull World world,
                                          @Nonnull Builder<Role> roleBuilder,
                                          @Nonnull Vector3i block,
                                          @Nonnull TwCoopConfig.SpawnOffsetSettings offset) {
        Vector3d fallback = new Vector3d(
                block.x + 0.5 + offset.getX(),
                block.y + offset.getY(),
                block.z + 0.5 + offset.getZ()
        );
        Vector3d validatedFallback = validateSpawnPosition(world, roleBuilder, fallback);
        if (validatedFallback != null) {
            fallback = validatedFallback;
        }
        double forwardX = offset.getX();
        double forwardZ = offset.getZ();
        double forwardLength = Math.sqrt((forwardX * forwardX) + (forwardZ * forwardZ));
        if (!Double.isFinite(forwardLength) || forwardLength < 0.001) {
            forwardX = 0.0;
            forwardZ = 1.0;
            forwardLength = 1.0;
        }
        forwardX /= forwardLength;
        forwardZ /= forwardLength;

        double centerX = block.x + 0.5;
        double centerY = block.y + offset.getY();
        double centerZ = block.z + 0.5;
        double halfConeRadians = Math.toRadians(RELEASE_CONE_DEGREES * 0.5);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < RELEASE_SPAWN_ATTEMPTS; attempt++) {
            double angle = random.nextDouble(-halfConeRadians, halfConeRadians);
            double distance = random.nextDouble(RELEASE_MIN_DISTANCE, RELEASE_MAX_DISTANCE + 0.0001);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double rotatedX = (forwardX * cos) - (forwardZ * sin);
            double rotatedZ = (forwardX * sin) + (forwardZ * cos);
            Vector3d candidate = new Vector3d(
                    centerX + (rotatedX * distance),
                    centerY,
                    centerZ + (rotatedZ * distance)
            );
            Vector3d validated = validateSpawnPosition(world, roleBuilder, candidate);
            if (validated != null) {
                return validated;
            }
        }
        return fallback;
    }

    @Nullable
    private Vector3d validateSpawnPosition(@Nonnull World world,
                                           @Nonnull Builder<Role> roleBuilder,
                                           @Nonnull Vector3d position) {
        if (!(roleBuilder instanceof ISpawnableWithModel spawnable)) {
            return position;
        }
        SpawningContext spawningContext = new SpawningContext();
        spawningContext.setSpawnable(spawnable);
        if (!spawningContext.set(world, position.x, position.y, position.z)) {
            return null;
        }
        if (spawningContext.canSpawn() != SpawnTestResult.TEST_OK) {
            return null;
        }
        Vector3d adjusted = spawningContext.newPosition();
        return adjusted != null ? new Vector3d(adjusted) : position;
    }

    private boolean shouldResidentsRoam(@Nonnull WorldTimeResource worldTime,
                                        @Nonnull TwCoopConfig.LifecycleRules lifecycleRules) {
        int hour = resolveGameHour(worldTime);
        int startHour = lifecycleRules.getResidentRoamStartHour();
        int endHour = lifecycleRules.getResidentRoamEndHour();
        if (startHour == endHour) {
            return true;
        }
        if (startHour < endHour) {
            return hour >= startHour && hour < endHour;
        }
        return hour >= startHour || hour < endHour;
    }

    private int resolveGameHour(@Nonnull WorldTimeResource worldTime) {
        Instant now = worldTime.getGameTime();
        if (now == null) {
            now = Instant.now();
        }
        return now.atZone(ZoneOffset.UTC).getHour();
    }

    private long resolveGameTimeMs(@Nonnull WorldTimeResource worldTime) {
        Instant now = worldTime.getGameTime();
        return now != null ? now.toEpochMilli() : System.currentTimeMillis();
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
    private UUID resolveOwnerIdForRemap(@Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot linkedSnapshot,
                                        @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
        if (linkedSnapshot != null && linkedSnapshot.ownerId() != null) {
            return linkedSnapshot.ownerId();
        }
        if (stateSnapshot != null && stateSnapshot.owner() != null && stateSnapshot.owner().getOwnerId() != null) {
            return stateSnapshot.owner().getOwnerId();
        }
        return null;
    }

    @Nullable
    private Player resolveOnlineOwner(@Nullable UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        PlayerRef playerRef = universe.getPlayer(ownerId);
        if (playerRef == null) {
            return null;
        }
        return playerRef.getComponent(Player.getComponentType());
    }

    private void remapLinkedRecords(@Nullable UUID previousUuid,
                                    @Nullable UUID currentUuid,
                                    @Nullable UUID ownerId,
                                    @Nullable TameworkCommandLinksComponent links) {
        if (previousUuid == null || currentUuid == null || previousUuid.equals(currentUuid)) {
            return;
        }
        boolean remapped = false;
        Player ownerPlayer = resolveOnlineOwner(ownerId);
        if (ownerPlayer != null) {
            remapped = CommandLinkedNpcRecordRemapService.remapLinkedNpcRecordsInHotbar(ownerPlayer, previousUuid, currentUuid);
        }
        if (!remapped && links != null && links.getOwnerId() != null) {
            Player linkedOwner = resolveOnlineOwner(links.getOwnerId());
            if (linkedOwner != null) {
                remapped = CommandLinkedNpcRecordRemapService.remapLinkedNpcRecordsInHotbar(linkedOwner, previousUuid, currentUuid);
            }
        }
        if (!remapped) {
            CommandLinkedNpcRecordRemapService.remapLinkedNpcRecordsForOnlinePlayers(previousUuid, currentUuid);
        }
    }

    @Nullable
    private CoopLocation resolveCoopLocation(@Nonnull Ref<ChunkStore> reference,
                                             @Nonnull Store<ChunkStore> chunkStore) {
        if (!reference.isValid()) {
            return null;
        }
        ComponentType<ChunkStore, ?> infoType = resolveBlockStateInfoComponentType();
        if (infoType == null) {
            return null;
        }
        Object info = safeGetChunkComponent(chunkStore, reference, castComponentType(infoType));
        if (info == null) {
            return null;
        }
        Object chunkRefObject = invokeNoArg(info, "getChunkRef");
        if (!(chunkRefObject instanceof Ref<?> rawChunkRef)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Ref<ChunkStore> chunkRef = (Ref<ChunkStore>) rawChunkRef;
        WorldChunk worldChunk = safeGetChunkComponent(chunkStore, chunkRef, WorldChunk.getComponentType());
        if (worldChunk == null) {
            return null;
        }
        Integer blockIndex = invokeIntMethod(info, "getIndex");
        if (blockIndex == null) {
            return null;
        }
        int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
        int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
        int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
        int worldX = ChunkUtil.worldCoordFromLocalCoord(worldChunk.getX(), localX);
        int worldZ = ChunkUtil.worldCoordFromLocalCoord(worldChunk.getZ(), localZ);
        BlockType blockType = worldChunk.getBlockType(worldX, localY, worldZ);
        String blockTypeId = normalizeBlockTypeId(blockType != null ? blockType.getId() : null);
        return new CoopLocation(new Vector3i(worldX, localY, worldZ), blockTypeId);
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveItemContainerComponentType() {
        if (apiResolved) {
            return itemContainerComponentType;
        }
        synchronized (this) {
            if (apiResolved) {
                return itemContainerComponentType;
            }
            itemContainerComponentType = resolveModernItemContainerComponentType();
            coopBlockComponentType = resolveModernCoopBlockComponentType();
            blockStateInfoComponentType = resolveModernBlockStateInfoComponentType();
            apiResolved = true;
            return itemContainerComponentType;
        }
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveCoopBlockComponentType() {
        if (!apiResolved) {
            resolveItemContainerComponentType();
        }
        return coopBlockComponentType;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveBlockStateInfoComponentType() {
        if (!apiResolved) {
            resolveItemContainerComponentType();
        }
        return blockStateInfoComponentType;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveModernItemContainerComponentType() {
        try {
            Class<?> itemContainerBlockClass = Class.forName(MODERN_ITEM_CONTAINER_BLOCK_CLASS);
            Method getComponentTypeMethod = itemContainerBlockClass.getMethod("getComponentType");
            Object componentType = getComponentTypeMethod.invoke(null);
            if (componentType instanceof ComponentType<?, ?> resolvedType) {
                return castComponentTypeUnchecked(resolvedType);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveModernCoopBlockComponentType() {
        try {
            Class<?> coopBlockClass = Class.forName(MODERN_COOP_BLOCK_CLASS);
            Method getComponentTypeMethod = coopBlockClass.getMethod("getComponentType");
            Object componentType = getComponentTypeMethod.invoke(null);
            if (componentType instanceof ComponentType<?, ?> resolvedType) {
                return castComponentTypeUnchecked(resolvedType);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveModernBlockStateInfoComponentType() {
        try {
            Class<?> blockModuleClass = Class.forName(MODERN_BLOCK_MODULE_CLASS);
            Object module = blockModuleClass.getMethod("get").invoke(null);
            Object componentType = blockModuleClass
                    .getMethod("getBlockStateInfoComponentType")
                    .invoke(module);
            if (componentType instanceof ComponentType<?, ?> resolvedType) {
                return castComponentTypeUnchecked(resolvedType);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    @Nullable
    private <T extends Component<ChunkStore>> T safeGetChunkComponent(
            @Nonnull Store<ChunkStore> chunkStore,
            @Nonnull Ref<ChunkStore> reference,
            @Nonnull ComponentType<ChunkStore, T> componentType
    ) {
        try {
            return chunkStore.getComponent(reference, componentType);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Nullable
    private <T extends Component<EntityStore>> T safeGetEntityComponent(
            @Nonnull Store<EntityStore> entityStore,
            @Nonnull Ref<EntityStore> reference,
            @Nonnull ComponentType<EntityStore, T> componentType
    ) {
        if (!reference.isValid()) {
            return null;
        }
        try {
            return entityStore.getComponent(reference, componentType);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private <T extends Component<EntityStore>> void safePutComponent(@Nonnull Store<EntityStore> store,
                                                                      @Nonnull Ref<EntityStore> reference,
                                                                      @Nullable ComponentType<EntityStore, T> type,
                                                                      @Nullable T component) {
        if (type == null || component == null || !reference.isValid()) {
            return;
        }
        try {
            store.putComponent(reference, type, copyComponent(component));
        } catch (IllegalStateException ignored) {
            // Skip noisy failures so coop runtime cannot crash world ticks.
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <T extends Component<EntityStore>> T copyComponent(@Nullable T component) {
        if (component == null) {
            return null;
        }
        try {
            return (T) component.clone();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private UUID resolveEntityUuid(@Nullable NPCEntity npc,
                                   @Nullable Ref<EntityStore> reference,
                                   @Nullable Store<EntityStore> store) {
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid();
        }
        if (reference == null || store == null || !reference.isValid()) {
            return null;
        }
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (uuidType == null) {
            return null;
        }
        UUIDComponent uuidComponent = safeGetEntityComponent(store, reference, uuidType);
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    @Nullable
    private Object invokeNoArg(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private Integer invokeIntMethod(@Nonnull Object target, @Nonnull String methodName) {
        Object value = invokeNoArg(target, methodName);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    @Nonnull
    private String buildCoopKey(@Nullable String worldName, @Nonnull Vector3i block, @Nonnull String coopId) {
        return (worldName != null && !worldName.isBlank() ? worldName.toLowerCase(Locale.ROOT) : "<unknown>")
                + "|" + block.x + "," + block.y + "," + block.z
                + "|" + coopId;
    }

    @Nonnull
    private Set<String> normalizeRoleSet(@Nullable String[] roleIds) {
        if (roleIds == null || roleIds.length == 0) {
            return Set.of();
        }
        HashSet<String> normalized = new HashSet<>();
        for (String roleId : roleIds) {
            String value = normalizeIdentifier(roleId);
            if (value != null) {
                normalized.add(value);
            }
        }
        return normalized.isEmpty() ? Set.of() : normalized;
    }

    @Nullable
    private TwCoopConfig resolveCoopConfig(@Nullable String rawBlockTypeId,
                                           @Nullable String rawCoopAssetId) {
        String coopAssetId = normalizeIdentifier(rawCoopAssetId);
        if (coopAssetId != null) {
            TwCoopConfig config = resolveCoopConfigByIdentifier(coopAssetId);
            if (config != null) {
                return config;
            }
        }
        String normalized = normalizeBlockTypeId(rawBlockTypeId);
        if (normalized == null) {
            return null;
        }
        return resolveCoopConfigByIdentifier(normalized);
    }

    @Nullable
    private TwCoopConfig resolveCoopConfigByIdentifier(@Nonnull String normalized) {
        TwCoopConfig config = TwCoopConfig.resolveForBlockType(normalized);
        if (config != null) {
            return config;
        }
        config = TwCoopConfig.resolveForCoop(normalized);
        if (config != null) {
            return config;
        }

        String trailing = extractTrailingIdentifier(normalized);
        if (trailing == null || trailing.equals(normalized)) {
            return null;
        }
        config = TwCoopConfig.resolveForBlockType(trailing);
        if (config != null) {
            return config;
        }
        return TwCoopConfig.resolveForCoop(trailing);
    }

    @Nullable
    private String resolveCoopAssetId(@Nullable Object state) {
        if (state == null) {
            return null;
        }
        Object coopAsset = invokeNoArg(state, "getCoopAsset");
        if (coopAsset != null) {
            Object coopAssetId = invokeNoArg(coopAsset, "getId");
            if (coopAssetId instanceof String assetId) {
                String normalized = normalizeIdentifier(assetId);
                if (normalized != null) {
                    return normalized;
                }
            }
        }
        Object coopAssetId = invokeNoArg(state, "getCoopAssetId");
        if (coopAssetId instanceof String assetId) {
            String normalized = normalizeIdentifier(assetId);
            if (normalized != null) {
                return normalized;
            }
        }
        Object fieldValue = readField(state, "coopAssetId");
        if (fieldValue instanceof String assetId) {
            return normalizeIdentifier(assetId);
        }
        return null;
    }

    @Nullable
    private Object readField(@Nonnull Object target, @Nonnull String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private String extractTrailingIdentifier(@Nonnull String value) {
        int slash = value.lastIndexOf('/');
        int colon = value.lastIndexOf(':');
        int dot = value.lastIndexOf('.');
        int separator = Math.max(slash, Math.max(colon, dot));
        if (separator < 0 || separator + 1 >= value.length()) {
            return null;
        }
        String tail = value.substring(separator + 1);
        return tail.isBlank() ? null : tail;
    }

    @Nullable
    private String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String normalizeBlockTypeId(@Nullable String value) {
        String normalized = normalizeIdentifier(value);
        if (normalized == null) {
            return null;
        }
        while (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    @Nullable
    private String firstNonBlank(@Nullable String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private void maybeLogStatus(long nowMs, @Nonnull String message) {
        if (!CoopDebugLogger.isEnabled()) {
            return;
        }
        if (nowMs < nextDebugStatusAtMs) {
            return;
        }
        nextDebugStatusAtMs = nowMs + DEBUG_STATUS_INTERVAL_MS;
        CoopDebugLogger.log(message);
    }

    @SuppressWarnings("unchecked")
    private <T extends Component<ChunkStore>> ComponentType<ChunkStore, T> castComponentType(
            @Nonnull ComponentType<ChunkStore, ?> type
    ) {
        return (ComponentType<ChunkStore, T>) type;
    }

    @SuppressWarnings("unchecked")
    private ComponentType<ChunkStore, ? extends Component<ChunkStore>> castComponentTypeUnchecked(
            @Nonnull ComponentType<?, ?> type
    ) {
        return (ComponentType<ChunkStore, ? extends Component<ChunkStore>>) type;
    }

    private record CoopLocation(@Nonnull Vector3i block, @Nullable String blockTypeId) {
    }

    private record ManagedCoopContext(@Nullable String worldName,
                                      @Nonnull String coopId,
                                      @Nonnull Vector3i block,
                                      @Nonnull TwCoopConfig config,
                                      @Nullable ItemContainer container,
                                      @Nonnull String coopKey) {
        @Nonnull
        CommandLinkedNpcCoopService.CoopSlotContext slotContext(int slot) {
            return CommandLinkedNpcCoopService.CoopSlotContext.of(
                    worldName,
                    coopId,
                    block.x,
                    block.y,
                    block.z,
                    slot
            );
        }
    }

    private record NpcCandidate(@Nonnull Ref<EntityStore> reference,
                                @Nonnull UUID npcUuid,
                                @Nonnull String roleId,
                                double x,
                                double y,
                                double z,
                                @Nullable UUID ownerId,
                                @Nullable String[] toolIds,
                                @Nullable String displayName,
                                boolean tamed) {
    }

    private static final class CoopScanDiagnostics {
        private int scanned;
        private int invalidRef;
        private int missingState;
        private int withContainerState;
        private int missingLocation;
        private int withLocation;
        private int configMiss;
        private int withConfig;
        private int missingCoopId;
        private int duplicateKey;
        private int added;
        private boolean containerTypeMissing;

        @Nonnull
        private String toSummary() {
            return "scanned=" + scanned
                    + ",invalidRef=" + invalidRef
                    + ",missingState=" + missingState
                    + ",withState=" + withContainerState
                    + ",missingLocation=" + missingLocation
                    + ",withLocation=" + withLocation
                    + ",configMiss=" + configMiss
                    + ",withConfig=" + withConfig
                    + ",missingCoopId=" + missingCoopId
                    + ",duplicate=" + duplicateKey
                    + ",added=" + added
                    + ",containerTypeMissing=" + containerTypeMissing;
        }
    }
}
