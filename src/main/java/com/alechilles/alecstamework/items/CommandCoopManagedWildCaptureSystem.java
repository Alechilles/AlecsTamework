package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
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
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Managed runtime for coop wild-capture intake (Tamework-authoritative path).
 */
public final class CommandCoopManagedWildCaptureSystem extends TickingSystem<ChunkStore> {
    private static final long SWEEP_INTERVAL_MS = 250L;

    private final CommandLinkedNpcCoopService coopService;
    @Nullable
    private final CommandLinkedNpcCaptureService captureService;
    @Nullable
    private final CommandNpcRelocationService relocationService;
    @Nullable
    private final CommandLinkedNpcLostService lostService;
    @Nullable
    private final CoopResidentStateSnapshotService stateSnapshotService;

    private long nextSweepAtMs;

    @Nullable
    private volatile ComponentType<ChunkStore, ?> blockStateInfoType;
    private volatile boolean blockStateInfoResolved;

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
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || world.getEntityStore() == null || world.getChunkStore() == null) {
            return;
        }
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        if (entityStore == null) {
            return;
        }
        WorldTimeResource worldTime = entityStore.getResource(WorldTimeResource.getResourceType());
        if (worldTime == null) {
            return;
        }

        ArrayList<ManagedCoopTarget> coopTargets = collectManagedCoops(store, worldTime);
        if (coopTargets.isEmpty()) {
            return;
        }
        ArrayList<NpcCandidate> npcCandidates = collectNpcCandidates(entityStore);
        if (npcCandidates.isEmpty()) {
            return;
        }

        String worldName = world.getName();
        HashSet<UUID> consumedNpcs = new HashSet<>();
        for (ManagedCoopTarget target : coopTargets) {
            NpcCandidate candidate = selectCaptureCandidate(target, npcCandidates, consumedNpcs);
            if (candidate == null) {
                continue;
            }
            boolean inserted = target.coopBlock().tryPutWildResidentFromWild(
                    entityStore,
                    candidate.reference(),
                    worldTime,
                    target.location().block()
            );
            if (!inserted) {
                continue;
            }
            consumedNpcs.add(candidate.npcUuid());

            // Immediately put residents into housed state so sync system treats this as capture, not release.
            target.coopBlock().ensureNoResidentsInWorld(entityStore);

            int residentSlot = CoopResidentSlotResolver.resolveMostRecentResidentSlot(target.coopBlock());
            CommandLinkedNpcCoopService.CoopSlotContext slotContext = CommandLinkedNpcCoopService.CoopSlotContext.of(
                    worldName,
                    target.coopId(),
                    target.location().block().x,
                    target.location().block().y,
                    target.location().block().z,
                    residentSlot
            );
            CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot =
                    stateSnapshotService != null
                            ? stateSnapshotService.captureSnapshotForLedger(
                            candidate.reference(),
                            entityStore,
                            candidate.npcUuid(),
                            target.coopId(),
                            residentSlot,
                            candidate.roleId()
                    )
                            : null;
            TameworkCommandLinksComponent links = entityStore.getComponent(
                    candidate.reference(),
                    TameworkCommandLinksComponent.getComponentType()
            );
            TameworkOwnerComponent owner = entityStore.getComponent(
                    candidate.reference(),
                    TameworkOwnerComponent.getComponentType()
            );
            TameworkNpcNameComponent npcName = entityStore.getComponent(
                    candidate.reference(),
                    TameworkNpcNameComponent.getComponentType()
            );
            UUID ownerId = links != null && links.getOwnerId() != null
                    ? links.getOwnerId()
                    : owner != null
                    ? owner.getOwnerId()
                    : null;
            String[] toolIds = links != null ? links.getToolIds() : null;
            String displayName = npcName != null ? npcName.getName() : null;
            coopService.captureResident(
                    candidate.npcUuid(),
                    candidate.roleId(),
                    slotContext,
                    ownerId,
                    toolIds,
                    displayName,
                    stateSnapshot
            );
            clearTransientState(candidate.npcUuid());

            CoopDebugLogger.log(
                    "managed wild capture npc=" + candidate.npcUuid()
                            + " role=" + candidate.roleId()
                            + " coop=" + target.coopId()
                            + " slot=" + residentSlot
                            + " pos=" + target.location().block().x
                            + "," + target.location().block().y
                            + "," + target.location().block().z
            );
        }
    }

    @Nonnull
    private ArrayList<ManagedCoopTarget> collectManagedCoops(@Nonnull Store<ChunkStore> chunkStore,
                                                              @Nonnull WorldTimeResource worldTime) {
        ArrayList<ManagedCoopTarget> out = new ArrayList<>();
        ComponentType<ChunkStore, CoopBlock> coopType = CoopBlock.getComponentType();
        if (coopType == null) {
            return out;
        }
        chunkStore.forEachChunk(
                Query.and(coopType),
                (ArchetypeChunk<ChunkStore> chunk, CommandBuffer<ChunkStore> commandBuffer) ->
                        collectManagedCoopsFromChunk(chunkStore, chunk, coopType, worldTime, out)
        );
        return out;
    }

    private void collectManagedCoopsFromChunk(@Nonnull Store<ChunkStore> chunkStore,
                                              @Nonnull ArchetypeChunk<ChunkStore> chunk,
                                              @Nonnull ComponentType<ChunkStore, CoopBlock> coopType,
                                              @Nonnull WorldTimeResource worldTime,
                                              @Nonnull ArrayList<ManagedCoopTarget> out) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<ChunkStore> reference = chunk.getReferenceTo(i);
            if (reference == null || !reference.isValid()) {
                continue;
            }
            CoopBlock coopBlock = chunk.getComponent(i, coopType);
            if (coopBlock == null) {
                continue;
            }
            CoopLocation location = resolveCoopLocation(reference, chunkStore);
            if (location == null) {
                continue;
            }
            FarmingCoopAsset coopAsset = coopBlock.getCoopAsset();
            String coopId = coopAsset != null ? coopAsset.getId() : null;
            TwCoopConfig config = TwCoopConfig.resolveForCoop(coopId);
            if (config == null || !config.isEnabled()) {
                continue;
            }
            TwCoopConfig.LifecycleRules lifecycleRules = config.getLifecycleRules();
            if (!lifecycleRules.isCaptureWildNPCsInRange()) {
                continue;
            }
            if (!coopBlock.shouldResidentsBeInCoop(worldTime)) {
                continue;
            }
            double radius = lifecycleRules.getWildCaptureRadius();
            if (radius <= 0.0) {
                continue;
            }
            out.add(new ManagedCoopTarget(
                    coopBlock,
                    normalizeIdentifier(coopId),
                    location,
                    radius * radius,
                    normalizeRoleSet(lifecycleRules.getAcceptedRoleIds())
            ));
        }
    }

    @Nonnull
    private ArrayList<NpcCandidate> collectNpcCandidates(@Nonnull Store<EntityStore> entityStore) {
        ArrayList<NpcCandidate> out = new ArrayList<>();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        if (npcType == null || transformType == null) {
            return out;
        }
        ComponentType<EntityStore, CoopResidentComponent> coopResidentType = CoopResidentComponent.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        entityStore.forEachChunk(
                Query.and(npcType, transformType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        collectCandidatesFromChunk(chunk, npcType, transformType, coopResidentType, uuidType, out)
        );
        return out;
    }

    private void collectCandidatesFromChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                            @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                            @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                            @Nullable ComponentType<EntityStore, CoopResidentComponent> coopResidentType,
                                            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
                                            @Nonnull ArrayList<NpcCandidate> out) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<EntityStore> reference = chunk.getReferenceTo(i);
            if (reference == null || !reference.isValid()) {
                continue;
            }
            if (coopResidentType != null) {
                CoopResidentComponent coopResident = chunk.getComponent(i, coopResidentType);
                if (coopResident != null) {
                    continue;
                }
            }
            NPCEntity npc = chunk.getComponent(i, npcType);
            TransformComponent transform = chunk.getComponent(i, transformType);
            if (npc == null || transform == null) {
                continue;
            }
            String roleId = normalizeIdentifier(npc.getRoleName());
            if (roleId == null) {
                continue;
            }
            UUID npcUuid = npc.getUuid();
            if (npcUuid == null && uuidType != null) {
                UUIDComponent uuidComponent = chunk.getComponent(i, uuidType);
                npcUuid = uuidComponent != null ? uuidComponent.getUuid() : null;
            }
            if (npcUuid == null) {
                continue;
            }
            Vector3d position = transform.getPosition();
            if (position == null || !isFinite(position.x) || !isFinite(position.y) || !isFinite(position.z)) {
                continue;
            }
            out.add(new NpcCandidate(reference, npcUuid, roleId, position.x, position.y, position.z));
        }
    }

    @Nullable
    private NpcCandidate selectCaptureCandidate(@Nonnull ManagedCoopTarget target,
                                                @Nonnull ArrayList<NpcCandidate> candidates,
                                                @Nonnull Set<UUID> consumedNpcs) {
        if (target.coopId() == null || target.radiusSq() <= 0.0) {
            return null;
        }
        double centerX = target.location().block().x + 0.5;
        double centerY = target.location().block().y + 0.5;
        double centerZ = target.location().block().z + 0.5;

        NpcCandidate best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        for (NpcCandidate candidate : candidates) {
            if (candidate == null || candidate.npcUuid() == null || consumedNpcs.contains(candidate.npcUuid())) {
                continue;
            }
            if (!target.acceptsRole(candidate.roleId())) {
                continue;
            }
            if (!target.coopBlock().getCoopAcceptsNPC(candidate.roleId())) {
                continue;
            }
            double dx = candidate.x() - centerX;
            double dy = candidate.y() - centerY;
            double dz = candidate.z() - centerZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distanceSq) || distanceSq > target.radiusSq()) {
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
    private CoopLocation resolveCoopLocation(@Nonnull Ref<ChunkStore> reference,
                                             @Nonnull Store<ChunkStore> chunkStore) {
        ComponentType<ChunkStore, ?> infoType = resolveBlockStateInfoType();
        if (infoType == null) {
            return null;
        }
        Object info = chunkStore.getComponent(reference, castComponentType(infoType));
        if (info == null) {
            return null;
        }
        Object chunkRefObject = invokeNoArg(info, "getChunkRef");
        if (!(chunkRefObject instanceof Ref<?> rawChunkRef)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Ref<ChunkStore> chunkRef = (Ref<ChunkStore>) rawChunkRef;
        WorldChunk worldChunk = chunkStore.getComponent(chunkRef, WorldChunk.getComponentType());
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
        return new CoopLocation(new Vector3i(worldX, localY, worldZ));
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveBlockStateInfoType() {
        if (blockStateInfoResolved) {
            return blockStateInfoType;
        }
        synchronized (this) {
            if (blockStateInfoResolved) {
                return blockStateInfoType;
            }
            blockStateInfoType = resolveBlockStateInfoTypeReflective();
            blockStateInfoResolved = true;
            return blockStateInfoType;
        }
    }

    @Nullable
    private ComponentType<ChunkStore, ?> resolveBlockStateInfoTypeReflective() {
        try {
            Object blockModule = BlockModule.class.getMethod("get").invoke(null);
            Object componentType = BlockModule.class
                    .getMethod("getBlockStateInfoComponentType")
                    .invoke(blockModule);
            if (componentType instanceof ComponentType<?, ?> resolvedType) {
                return castComponentTypeUnchecked(resolvedType);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
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

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
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
    private String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
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

    private record CoopLocation(@Nonnull Vector3i block) {
    }

    private record ManagedCoopTarget(@Nonnull CoopBlock coopBlock,
                                     @Nullable String coopId,
                                     @Nonnull CoopLocation location,
                                     double radiusSq,
                                     @Nonnull Set<String> acceptedRoleIds) {
        boolean acceptsRole(@Nullable String roleId) {
            if (acceptedRoleIds.isEmpty()) {
                return true;
            }
            if (roleId == null || roleId.isBlank()) {
                return false;
            }
            return acceptedRoleIds.contains(roleId);
        }
    }

    private record NpcCandidate(@Nonnull Ref<EntityStore> reference,
                                @Nonnull UUID npcUuid,
                                @Nullable String roleId,
                                double x,
                                double y,
                                double z) {
    }
}
