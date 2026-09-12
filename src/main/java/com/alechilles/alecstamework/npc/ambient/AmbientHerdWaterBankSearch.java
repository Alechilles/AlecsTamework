package com.alechilles.alecstamework.npc.ambient;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Resumable, loaded-only water-bank discovery. Its cursor contains coordinates and counters only.
 */
public final class AmbientHerdWaterBankSearch {
    private static final int[] RADII = {16, 32, 48, 64};
    private static final int SAMPLES_PER_RADIUS = 24;
    private static final int MAX_COLUMNS = 96;
    private static final int[] VERTICAL = verticalOffsets();
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    public SearchCursor newCursor(
            AmbientHerdPoint origin, long rotation, double width, double height) {
        return new SearchCursor(origin, rotation, validSize(width), validSize(height), null);
    }

    public SearchCursor newValidationCursor(BankPatch patch, double width, double height) {
        if (patch == null) throw new IllegalArgumentException("patch is required");
        return new SearchCursor(patch.water(), 0L, validSize(width), validSize(height), patch);
    }

    public StepResult step(
            SearchCursor cursor,
            Store<EntityStore> store,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long nowMillis) {
        if (store == null
                || store.getExternalData() == null
                || store.getExternalData().getWorld() == null) return StepResult.miss();
        World world = store.getExternalData().getWorld();
        ChunkStore chunks = world.getChunkStore();
        return chunks == null
                ? StepResult.miss()
                : step(cursor, new HytaleWorldAccess(chunks), budget, worldId, nowMillis);
    }

    StepResult step(
            SearchCursor cursor,
            WorldAccess access,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long nowMillis) {
        if (cursor == null || access == null || budget == null || worldId == null || cursor.done)
            return StepResult.miss();
        if (cursor.patch != null) return validatePatch(cursor, access, budget, worldId, nowMillis);
        if (cursor.candidate != null)
            return validateCandidate(cursor, access, budget, worldId, nowMillis);
        while (cursor.column < MAX_COLUMNS) {
            AmbientHerdPoint point = sample(cursor);
            if (cursor.vertical < VERTICAL.length) {
                AmbientHerdPoint water = point.offset(0.0, VERTICAL[cursor.vertical], 0.0);
                TerrainCell cell = read(cursor, access, budget, worldId, nowMillis, water);
                if (cell == null) return cursor.done ? StepResult.miss() : StepResult.pending();
                cursor.vertical++;
                if (cell.loaded()
                        && cell.water()
                        && point.horizontalDistanceSquared(cursor.origin) >= 144.0) {
                    if (++cursor.patchAttempts > 4) continue;
                    cursor.candidate = water;
                    cursor.validationDirection = 0;
                    cursor.bankPointIndex = 0;
                    cursor.slots.clear();
                    cursor.staging.clear();
                    return validateCandidate(cursor, access, budget, worldId, nowMillis);
                }
                continue;
            }
            cursor.vertical = 0;
            cursor.column++;
        }
        cursor.done = true;
        return StepResult.miss();
    }

    private StepResult validatePatch(
            SearchCursor cursor,
            WorldAccess access,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long now) {
        BankPatch patch = cursor.patch;
        if (cursor.validationIndex == 0) {
            TerrainCell water = read(cursor, access, budget, worldId, now, patch.water());
            if (water == null) return cursor.done ? StepResult.miss() : StepResult.pending();
            if (!water.loaded() || !water.water()) {
                cursor.done = true;
                return StepResult.miss();
            }
            cursor.validationIndex++;
        }
        List<AmbientHerdPoint> positions =
                new ArrayList<>(patch.slots().size() + patch.staging().size());
        positions.addAll(patch.slots());
        positions.addAll(patch.staging());
        while (cursor.validationIndex <= positions.size()) {
            AmbientHerdPoint point = positions.get(cursor.validationIndex - 1);
            Boolean safe =
                    safe(
                            cursor,
                            access,
                            budget,
                            worldId,
                            now,
                            point,
                            cursor.width,
                            cursor.height,
                            cursor.verified);
            if (safe == null) return cursor.done ? StepResult.miss() : StepResult.pending();
            if (!safe) {
                cursor.done = true;
                return StepResult.miss();
            }
            cursor.validationIndex++;
        }
        cursor.done = true;
        return StepResult.found(patch);
    }

    private StepResult validateCandidate(
            SearchCursor cursor,
            WorldAccess access,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long now) {
        while (cursor.validationDirection < DX.length) {
            int direction = cursor.validationDirection;
            boolean staging = cursor.bankPointIndex >= 2;
            double bankDistance = Math.max(1.5, cursor.width * .5 + .75);
            double radial = staging ? bankDistance + 3.0 : bankDistance;
            double spacing = Math.max(1.25, cursor.width * .5 + .25);
            double lateral = (cursor.bankPointIndex & 1) == 0 ? -spacing : spacing;
            AmbientHerdPoint point =
                    cursor.candidate.offset(
                            DX[direction] * radial - DZ[direction] * lateral,
                            1.0,
                            DZ[direction] * radial + DX[direction] * lateral);
            Boolean pointSafe =
                    safe(
                            cursor,
                            access,
                            budget,
                            worldId,
                            now,
                            point,
                            cursor.width,
                            cursor.height,
                            cursor.verified);
            if (pointSafe == null) return cursor.done ? StepResult.miss() : StepResult.pending();
            if (!pointSafe) {
                cursor.slots.clear();
                cursor.staging.clear();
                cursor.bankPointIndex = 0;
                cursor.validationDirection++;
                continue;
            }
            if (staging) cursor.staging.add(point);
            else cursor.slots.add(point);
            cursor.bankPointIndex++;
            if (cursor.bankPointIndex == 4) {
                cursor.done = true;
                return StepResult.found(
                        new BankPatch(
                                cursor.candidate,
                                List.copyOf(cursor.slots),
                                List.copyOf(cursor.staging)));
            }
        }
        cursor.column++;
        cursor.vertical = 0;
        if (cursor.slots.size() >= 2 && cursor.staging.size() >= 2) {
            cursor.done = true;
            return StepResult.found(
                    new BankPatch(
                            cursor.candidate,
                            List.copyOf(cursor.slots),
                            List.copyOf(cursor.staging)));
        }
        cursor.candidate = null;
        return step(cursor, access, budget, worldId, now);
    }

    @Nullable
    private static Boolean safe(
            SearchCursor cursor,
            WorldAccess access,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long now,
            AmbientHerdPoint stand,
            double width,
            double height,
            java.util.Map<AmbientHerdPoint, SafeState> verified) {
        SafeState state = verified.get(stand);
        if (state != null && state.result != null) return state.result;
        int minimumX = (int) Math.floor(stand.x() - width * .5 + .000001);
        int maximumX = (int) Math.floor(stand.x() + width * .5 - .000001);
        int minimumZ = (int) Math.floor(stand.z() - width * .5 + .000001);
        int maximumZ = (int) Math.floor(stand.z() + width * .5 - .000001);
        int head = Math.max(1, (int) Math.ceil(height));
        if (state == null) {
            state = new SafeState();
            for (int x = minimumX; x <= maximumX; x++)
                for (int z = minimumZ; z <= maximumZ; z++) {
                    state.cells.add(
                            new FootCell(
                                    new AmbientHerdPoint(x + .5, stand.blockY() - 1, z + .5),
                                    true));
                    for (int y = 0; y < head; y++)
                        state.cells.add(
                                new FootCell(
                                        new AmbientHerdPoint(x + .5, stand.blockY() + y, z + .5),
                                        false));
                }
            verified.put(stand, state);
        }
        while (state.index < state.cells.size()) {
            if (!budget.claim(worldId, AmbientHerdWorkBudget.Work.LOADED_REFERENCE_LOOKUP, 2, now)
                    || !budget.claim(worldId, AmbientHerdWorkBudget.Work.POINT_READ, 2, now))
                return null;
            if (cursor.directReads > 4_094) {
                cursor.done = true;
                return null;
            }
            FootCell cell = state.cells.get(state.index++);
            TerrainCell terrain = access.read(cell.point);
            cursor.directReads += 2;
            if (!terrain.loaded()
                    || terrain.hazardous()
                    || (cell.ground ? !terrain.solid() : terrain.water() || terrain.solid())) {
                state.result = false;
                return false;
            }
        }
        state.result = true;
        return true;
    }

    @Nullable
    private static TerrainCell read(
            SearchCursor cursor,
            WorldAccess access,
            AmbientHerdWorkBudget budget,
            UUID worldId,
            long now,
            AmbientHerdPoint point) {
        if (cursor.directReads > 4_094) {
            cursor.done = true;
            return null;
        }
        if (!budget.claim(worldId, AmbientHerdWorkBudget.Work.LOADED_REFERENCE_LOOKUP, 2, now)
                || !budget.claim(worldId, AmbientHerdWorkBudget.Work.POINT_READ, 2, now))
            return null;
        cursor.directReads += 2;
        return access.read(point);
    }

    private static AmbientHerdPoint sample(SearchCursor cursor) {
        int ring = cursor.column / SAMPLES_PER_RADIUS;
        int within = cursor.column % SAMPLES_PER_RADIUS;
        double angle =
                (Math.PI
                                * 2.0
                                * ((within + Math.floorMod(cursor.rotation, SAMPLES_PER_RADIUS))
                                        % SAMPLES_PER_RADIUS))
                        / SAMPLES_PER_RADIUS;
        int radius = RADII[ring];
        return new AmbientHerdPoint(
                cursor.origin.blockX() + Math.cos(angle) * radius + .5,
                cursor.origin.blockY(),
                cursor.origin.blockZ() + Math.sin(angle) * radius + .5);
    }

    private static int[] verticalOffsets() {
        int[] values = new int[17];
        values[0] = 0;
        for (int i = 1; i <= 8; i++) {
            values[i * 2 - 1] = -i;
            values[i * 2] = i;
        }
        return values;
    }

    private static double validSize(double value) {
        if (!Double.isFinite(value) || value <= 0.0 || value > 4.0)
            throw new IllegalArgumentException("ambient herd body dimensions must be in (0, 4]");
        return value;
    }

    public enum SearchStatus {
        PENDING,
        FOUND,
        MISS
    }

    public record BankPatch(
            AmbientHerdPoint water, List<AmbientHerdPoint> slots, List<AmbientHerdPoint> staging) {
        public BankPatch {
            slots = List.copyOf(slots);
            staging = List.copyOf(staging);
            if (slots.size() < 2 || staging.size() < 2 || slots.size() > 4 || staging.size() > 4)
                throw new IllegalArgumentException(
                        "bank patch needs two to four slots and staging points");
        }
    }

    public record StepResult(SearchStatus status, @Nullable BankPatch patch) {
        static StepResult pending() {
            return new StepResult(SearchStatus.PENDING, null);
        }

        static StepResult miss() {
            return new StepResult(SearchStatus.MISS, null);
        }

        static StepResult found(BankPatch patch) {
            return new StepResult(SearchStatus.FOUND, patch);
        }
    }

    public interface WorldAccess {
        TerrainCell read(AmbientHerdPoint point);
    }

    public record TerrainCell(boolean loaded, boolean water, boolean solid, boolean hazardous) {}

    public static final class SearchCursor {
        private final AmbientHerdPoint origin;
        private final long rotation;
        private final double width, height;
        private final BankPatch patch;
        private final List<AmbientHerdPoint> slots = new ArrayList<>(4),
                staging = new ArrayList<>(4);
        private final java.util.Map<AmbientHerdPoint, SafeState> verified =
                new java.util.HashMap<>();
        private int column,
                vertical,
                validationDirection,
                validationIndex,
                patchAttempts,
                bankPointIndex,
                directReads;
        private AmbientHerdPoint candidate;
        private boolean done;

        private SearchCursor(
                AmbientHerdPoint origin,
                long rotation,
                double width,
                double height,
                BankPatch patch) {
            this.origin = origin;
            this.rotation = rotation;
            this.width = width;
            this.height = height;
            this.patch = patch;
        }
    }

    private record FootCell(AmbientHerdPoint point, boolean ground) {}

    private static final class SafeState {
        private final List<FootCell> cells = new ArrayList<>();
        private int index;
        private Boolean result;
    }

    private static final class HytaleWorldAccess implements WorldAccess {
        private final ChunkStore chunkStore;
        private final Store<ChunkStore> store;

        private HytaleWorldAccess(ChunkStore chunkStore) {
            this.chunkStore = chunkStore;
            this.store = chunkStore.getStore();
        }

        @Override
        public TerrainCell read(AmbientHerdPoint point) {
            if (store == null) return new TerrainCell(false, false, false, false);
            long index = ChunkUtil.indexChunkFromBlock(point.blockX(), point.blockZ());
            Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(index);
            if (chunkRef == null || !chunkRef.isValid())
                return new TerrainCell(false, false, false, false);
            WorldChunk chunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
            ChunkColumn column = store.getComponent(chunkRef, ChunkColumn.getComponentType());
            if (chunk == null || column == null) return new TerrainCell(false, false, false, false);
            int sectionY = ChunkUtil.chunkCoordinate(point.blockY());
            Ref<ChunkStore> sectionRef =
                    sectionY < 0 || sectionY >= ChunkUtil.HEIGHT_SECTIONS
                            ? null
                            : column.getSection(sectionY);
            if (sectionRef == null || !sectionRef.isValid())
                return new TerrainCell(false, false, false, false);
            FluidSection fluids = store.getComponent(sectionRef, FluidSection.getComponentType());
            if (fluids == null) return new TerrainCell(false, false, false, false);
            int fluidId = fluids.getFluidId(point.blockX(), point.blockY(), point.blockZ());
            int blockId = chunk.getBlock(point.blockX(), point.blockY(), point.blockZ());
            BlockType block = blockId == 0 ? null : BlockType.getAssetMap().getAsset(blockId);
            boolean water = isNaturalWater(fluidId);
            boolean solid =
                    block != null
                            && block != BlockType.UNKNOWN
                            && WorldUtil.isSolidOnlyBlock(block, fluidId);
            boolean hazardous =
                    (fluidId != 0 && !water)
                            || (blockId != 0 && (block == null || block == BlockType.UNKNOWN))
                            || (block != null && block.getDamageToEntities() > 0);
            return new TerrainCell(true, water, solid, hazardous);
        }

        private static boolean isNaturalWater(int fluidId) {
            Fluid fluid = fluidId == 0 ? null : Fluid.getAssetMap().getAsset(fluidId);
            String id = fluid == null ? null : fluid.getId();
            return id != null && id.regionMatches(true, 0, "Water", 0, 5);
        }
    }
}
