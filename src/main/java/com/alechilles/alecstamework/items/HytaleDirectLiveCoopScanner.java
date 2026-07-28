package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Read-only world-thread scanner for the released configured coop and live-NPC boundaries.
 *
 * <p>The scanner discovers only loaded block entities and live NPC entities. It never infers a
 * removed coop from an unloaded chunk and never reads captured items.</p>
 */
final class HytaleDirectLiveCoopScanner {
    private static final String BLOCK_MODULE_CLASS =
            "com.hypixel.hytale.server.core.modules.block.BlockModule";
    private static final String ITEM_CONTAINER_BLOCK_CLASS =
            "com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock";
    private static final String COOP_BLOCK_CLASS =
            "com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock";

    @Nullable
    private volatile ComponentType<ChunkStore, ?> itemContainerType;
    @Nullable
    private volatile ComponentType<ChunkStore, ?> coopBlockType;
    @Nullable
    private volatile ComponentType<ChunkStore, ?> blockStateInfoType;
    private volatile boolean componentTypesResolved;
    /** Returns one immutable view of currently loaded coops and live NPC candidates. */
    @Nullable
    Scan scan(@Nonnull Store<ChunkStore> chunkStore) {
        World world = chunkStore.getExternalData() == null
                ? null : chunkStore.getExternalData().getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        if (entityStore == null) {
            return null;
        }
        WorldTimeResource worldTime = entityStore.getResource(
                WorldTimeResource.getResourceType()
        );
        if (worldTime == null) {
            return null;
        }
        List<LoadedCoop> coops = scanCoops(chunkStore, world);
        List<LiveNpc> npcs = coops.stream().anyMatch(LoadedCoop::acceptsCapture)
                ? scanLiveNpcs(entityStore)
                : List.of();
        return new Scan(world, chunkStore, entityStore, worldTime, coops, npcs);
    }
    /** Confirms removal only when the exact coop chunk is loaded and no matching coop remains. */
    boolean confirmedRemoved(
            @Nonnull World world,
            @Nonnull Store<ChunkStore> chunkStore,
            @Nonnull CoopSlotKey slot
    ) {
        WorldChunk chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(slot.x(), slot.z())
        );
        if (chunk == null) {
            return false;
        }
        BlockType block = chunk.getBlockType(slot.x(), slot.y(), slot.z());
        TwCoopConfig config = resolveConfig(block == null ? null : block.getId(), null);
        String foundId = normalize(config == null ? null : config.getCoopId());
        if (slot.coopId().equals(foundId)) {
            return false;
        }
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(
                slot.x(), slot.y(), slot.z()
        );
        if (blockRef == null) {
            return true;
        }
        if (!blockRef.isValid()) {
            return false;
        }
        ComponentType<ChunkStore, ?> type = coopBlockComponentType();
        return type != null
                && safeChunkComponent(
                        chunkStore, blockRef, castComponentType(type)
                ) == null;
    }
    private List<LoadedCoop> scanCoops(
            Store<ChunkStore> chunkStore,
            World world
    ) {
        resolveComponentTypes();
        ComponentType<ChunkStore, ?> infoType = blockStateInfoType;
        if (infoType == null
                || (itemContainerType == null && coopBlockType == null)) {
            return List.of();
        }
        ArrayList<LoadedCoop> found = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (itemContainerType != null) {
            scanCoopType(
                    chunkStore, world, itemContainerType, infoType, seen, found
            );
        }
        if (coopBlockType != null && coopBlockType != itemContainerType) {
            scanCoopType(
                    chunkStore, world, coopBlockType, infoType, seen, found
            );
        }
        found.sort(Comparator.comparing(LoadedCoop::physicalKey));
        return List.copyOf(found);
    }
    private void scanCoopType(
            Store<ChunkStore> store,
            World world,
            ComponentType<ChunkStore, ?> stateType,
            ComponentType<ChunkStore, ?> infoType,
            HashSet<String> seen,
            ArrayList<LoadedCoop> found
    ) {
        store.forEachChunk(
                Query.and(
                        castComponentTypeUnchecked(stateType),
                        castComponentTypeUnchecked(infoType)
                ),
                (ArchetypeChunk<ChunkStore> chunk,
                 CommandBuffer<ChunkStore> ignored) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        Ref<ChunkStore> reference = chunk.getReferenceTo(index);
                        if (reference == null || !reference.isValid()) {
                            continue;
                        }
                        Object state = chunk.getComponent(
                                index, castComponentType(stateType)
                        );
                        Object info = chunk.getComponent(
                                index, castComponentType(infoType)
                        );
                        LoadedCoop coop = loadedCoop(
                                store, world, state, info
                        );
                        if (coop != null && seen.add(coop.physicalKey())) {
                            found.add(coop);
                        }
                    }
                }
        );
    }

    @Nullable
    private LoadedCoop loadedCoop(
            Store<ChunkStore> store,
            World world,
            @Nullable Object state,
            @Nullable Object info
    ) {
        CoopLocation location = location(store, info);
        if (state == null || location == null) {
            return null;
        }
        String assetId = coopAssetId(state);
        TwCoopConfig config = resolveConfig(location.blockTypeId(), assetId);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        String coopId = normalize(first(
                config.getCoopId(), assetId, location.blockTypeId()
        ));
        String worldKey = normalize(world.getName());
        if (coopId == null || worldKey == null) {
            return null;
        }
        return new LoadedCoop(
                worldKey,
                coopId,
                location.block(),
                location.rotationIndex(),
                config,
                FeedTroughContainerCompat.getItemContainer(state)
        );
    }

    @Nullable
    private CoopLocation location(
            Store<ChunkStore> store,
            @Nullable Object info
    ) {
        Object rawReference = invoke(info, "getChunkRef");
        if (!(rawReference instanceof Ref<?> raw)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Ref<ChunkStore> reference = (Ref<ChunkStore>) raw;
        WorldChunk chunk = safeChunkComponent(
                store, reference, WorldChunk.getComponentType()
        );
        Object rawIndex = invoke(info, "getIndex");
        if (chunk == null || !(rawIndex instanceof Number number)) {
            return null;
        }
        int index = number.intValue();
        int localX = ChunkUtil.xFromBlockInColumn(index);
        int y = ChunkUtil.yFromBlockInColumn(index);
        int localZ = ChunkUtil.zFromBlockInColumn(index);
        int x = ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX);
        int z = ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ);
        BlockType block = chunk.getBlockType(x, y, z);
        return new CoopLocation(
                new Vector3i(x, y, z),
                normalizeBlockType(block == null ? null : block.getId()),
                chunk.getRotationIndex(x, y, z)
        );
    }

    private List<LiveNpc> scanLiveNpcs(Store<EntityStore> store) {
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        if (npcType == null || transformType == null) {
            return List.of();
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                TameworkTamedComponent.getComponentType();
        ComponentType<EntityStore, CoopResidentComponent> residentType =
                CoopResidentComponent.getComponentType();
        ArrayList<LiveNpc> found = new ArrayList<>();
        store.forEachChunk(
                Query.and(npcType, transformType),
                (ArchetypeChunk<EntityStore> chunk,
                 CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        Ref<EntityStore> reference =
                                chunk.getReferenceTo(index);
                        NPCEntity npc = chunk.getComponent(index, npcType);
                        TransformComponent transform =
                                chunk.getComponent(index, transformType);
                        if (reference == null || !reference.isValid()
                                || npc == null || transform == null
                                || transform.getPosition() == null
                                || (residentType != null
                                && chunk.getComponent(index, residentType)
                                != null)) {
                            continue;
                        }
                        UUID alias = npc.getUuid();
                        if (alias == null && uuidType != null) {
                            UUIDComponent uuid =
                                    chunk.getComponent(index, uuidType);
                            alias = uuid == null ? null : uuid.getUuid();
                        }
                        String role = normalize(npc.getRoleName());
                        Vector3d position = transform.getPosition();
                        if (alias == null || role == null
                                || !finite(position)) {
                            continue;
                        }
                        TameworkTamedComponent tamed = tamedType == null
                                ? null : chunk.getComponent(index, tamedType);
                        found.add(new LiveNpc(
                                reference,
                                alias,
                                role,
                                new Vector3d(position),
                                tamed != null && tamed.isTamed()
                        ));
                    }
                }
        );
        found.sort(Comparator.comparing(candidate ->
                candidate.alias().toString()));
        return List.copyOf(found);
    }

    private synchronized void resolveComponentTypes() {
        if (componentTypesResolved) {
            return;
        }
        itemContainerType = staticComponentType(ITEM_CONTAINER_BLOCK_CLASS);
        coopBlockType = staticComponentType(COOP_BLOCK_CLASS);
        try {
            Class<?> moduleClass = Class.forName(BLOCK_MODULE_CLASS);
            Object module = moduleClass.getMethod("get").invoke(null);
            Object type = moduleClass
                    .getMethod("getBlockStateInfoComponentType")
                    .invoke(module);
            if (type instanceof ComponentType<?, ?> componentType) {
                blockStateInfoType = castComponentTypeUnchecked(componentType);
            }
        } catch (ReflectiveOperationException ignored) {
            blockStateInfoType = null;
        }
        componentTypesResolved = true;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> coopBlockComponentType() {
        resolveComponentTypes();
        return coopBlockType;
    }

    @Nullable
    private ComponentType<ChunkStore, ?> staticComponentType(String className) {
        try {
            Class<?> type = Class.forName(className);
            Object result = type.getMethod("getComponentType").invoke(null);
            return result instanceof ComponentType<?, ?> componentType
                    ? castComponentTypeUnchecked(componentType) : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nullable
    private TwCoopConfig resolveConfig(
            @Nullable String blockType,
            @Nullable String coopAsset
    ) {
        TwCoopConfig config = resolveIdentifier(coopAsset);
        return config != null ? config : resolveIdentifier(blockType);
    }

    @Nullable
    private TwCoopConfig resolveIdentifier(@Nullable String raw) {
        String value = normalizeBlockType(raw);
        if (value == null) {
            return null;
        }
        TwCoopConfig config = TwCoopConfig.resolveForBlockType(value);
        if (config == null) {
            config = TwCoopConfig.resolveForCoop(value);
        }
        return config;
    }

    @Nullable
    private String coopAssetId(Object state) {
        Object asset = invoke(state, "getCoopAsset");
        Object id = invoke(asset, "getId");
        if (id instanceof String value) {
            return normalize(value);
        }
        Object direct = invoke(state, "getCoopAssetId");
        return direct instanceof String value ? normalize(value) : null;
    }

    @Nullable
    private Object invoke(@Nullable Object target, String methodName) {
        return target == null
                ? null
                : TameworkReflectionAccessCache.invokeNoArg(
                        target, methodName
                );
    }

    @Nullable
    private <T extends Component<ChunkStore>> T safeChunkComponent(
            Store<ChunkStore> store,
            Ref<ChunkStore> reference,
            ComponentType<ChunkStore, T> type
    ) {
        if (reference == null || !reference.isValid() || type == null) {
            return null;
        }
        try {
            return store.getComponent(reference, type);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Component<ChunkStore>>
    ComponentType<ChunkStore, T> castComponentType(
            ComponentType<ChunkStore, ?> type
    ) {
        return (ComponentType<ChunkStore, T>) type;
    }

    @SuppressWarnings("unchecked")
    private ComponentType<ChunkStore, ? extends Component<ChunkStore>>
    castComponentTypeUnchecked(ComponentType<?, ?> type) {
        return (ComponentType<ChunkStore, ? extends Component<ChunkStore>>) type;
    }

    private boolean finite(Vector3d value) {
        return Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    @Nullable
    private String normalizeBlockType(@Nullable String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        while (value.startsWith("*")) {
            value = value.substring(1);
        }
        int state = value.indexOf("_state_definitions_");
        return state > 0 ? value.substring(0, state) : value;
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String first(@Nullable String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    record Scan(@Nonnull World world,
                @Nonnull Store<ChunkStore> chunkStore,
                @Nonnull Store<EntityStore> entityStore,
                @Nonnull WorldTimeResource worldTime,
                @Nonnull List<LoadedCoop> coops,
                @Nonnull List<LiveNpc> liveNpcs) {
    }

    record LoadedCoop(@Nonnull String worldKey,
                      @Nonnull String coopId,
                      @Nonnull Vector3i block,
                      int rotationIndex,
                      @Nullable TwCoopConfig config,
                      @Nullable ItemContainer container) {
        String physicalKey() {
            return worldKey + "|" + coopId + "|" + block.x + "|"
                    + block.y + "|" + block.z;
        }

        List<CoopSlotKey> slots() {
            ArrayList<CoopSlotKey> slots = new ArrayList<>();
            for (int index = 0;
                 index < config.getLifecycleRules().getMaxResidents();
                 index++) {
                slots.add(slot(index));
            }
            return List.copyOf(slots);
        }
        CoopSlotKey slot(int index) {
            return new CoopSlotKey(
                    worldKey, coopId, block.x, block.y, block.z, index
            );
        }
        boolean acceptsCapture() {
            return config.getLifecycleRules().isCaptureWildNPCsInRange();
        }
    }

    record LiveNpc(@Nonnull Ref<EntityStore> reference,
                   @Nonnull UUID alias,
                   @Nonnull String roleId,
                   @Nonnull Vector3d position,
                   boolean tamed) {
    }

    private record CoopLocation(Vector3i block,
                                String blockTypeId,
                                int rotationIndex) {
    }
}
