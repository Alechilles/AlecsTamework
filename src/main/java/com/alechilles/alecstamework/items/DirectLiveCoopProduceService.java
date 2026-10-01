package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.compat.HytaleBlockStateAccess;
import com.alechilles.alecstamework.companion.coop.TameworkCoopSlotsComponent;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coop produce (spec 8.9) on the resident slot entries. While a coop's residents roam, each due
 * interval of a resident's active time adds its role's drops to the coop container. The watermark
 * ({@code producedUntilMs}, on the resident's active-time clock) lives in the slot entry; the
 * caller writes the changed entries back to the block. Rules: interval max(24, IntervalGameHours)
 * game hours, {@code ItemsPerTick} items per interval, drops by role, at most
 * {@value #MAX_CATCH_UP_CYCLES_PER_SWEEP} intervals per sweep. Call on the coop's world thread.
 */
public final class DirectLiveCoopProduceService {
    private static final long GAME_MILLIS_PER_HOUR = 3_600_000L;
    private static final int MAX_CATCH_UP_CYCLES_PER_SWEEP = 32;
    private static final String DEFAULT_INTERACTION_STATE = "default";
    private static final String PRODUCE_READY_INTERACTION_STATE =
            "Produce_Ready";

    /**
     * A current resident: its slot entry, role and life stage. A companion's life stage comes from
     * its record summary, an unowned resident's from its inline entity; null means its active time
     * is unknown, and it produces nothing.
     */
    public record Resident(@Nonnull TameworkCoopSlotsComponent.Slot entry, @Nullable String roleId,
                           @Nullable TameworkLifeStageComponent lifeStage) {
    }

    /**
     * Produces for each resident and returns the entries whose watermark changed. A resident with
     * no watermark starts from its current active time, with no free first interval.
     */
    @Nonnull
    public List<TameworkCoopSlotsComponent.Slot> produce(
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop,
            @Nonnull List<Resident> residents,
            double gameSecondsPerRealSecond
    ) {
        ItemContainer container = coop.container();
        TwCoopConfig config = coop.config();
        Map<String, String> drops = config == null ? Map.of()
                : normalizeDrops(config.getProduceRules().getDropsByRole());
        if (container == null || drops.isEmpty() || residents.isEmpty()) {
            return List.of();
        }
        TwCoopConfig.ProduceRules rules = config.getProduceRules();
        long intervalHours = Math.max(
                WorldTimeResource.HOURS_PER_DAY,
                rules.getIntervalGameHours()
        );
        double safeRate = Double.isFinite(gameSecondsPerRealSecond)
                && gameSecondsPerRealSecond > 0.0 ? gameSecondsPerRealSecond : 1.0;
        long intervalMs = Math.max(1L, (long) Math.ceil(
                (intervalHours * (double) GAME_MILLIS_PER_HOUR) / safeRate
        ));
        int itemsPerTick = rules.getItemsPerTick();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        List<TameworkCoopSlotsComponent.Slot> changed = new ArrayList<>();
        for (Resident resident : residents) {
            String role = normalize(resident.roleId());
            String dropId = role == null ? null : drops.get(role);
            if (dropId == null || resident.lifeStage() == null
                    || AnimalProgressionService.deathDue(resident.lifeStage(), resident.roleId())) {
                continue;
            }
            TameworkCoopSlotsComponent.Slot entry = resident.entry();
            long now = AnimalProgressionService.activeTimeMs(resident.lifeStage());
            long next = advance(entry.producedUntilMs(), now, intervalMs,
                    cycles -> produceCycles(container, dropId, cycles, itemsPerTick, random));
            if (next != entry.producedUntilMs()) {
                changed.add(new TameworkCoopSlotsComponent.Slot(entry.slot(), entry.profileId(), entry.generation(),
                        entry.unownedEntity(), next));
            }
        }
        return changed;
    }

    /**
     * The next watermark. No watermark (0) starts at {@code nowMs}. Otherwise the due cycles are
     * offered to {@code produceCycles}, which returns how many it completed (a cycle cut short by
     * a full container counts when it added something), and the watermark moves by those.
     */
    static long advance(long watermarkMs, long nowMs, long intervalMs, IntUnaryOperator produceCycles) {
        if (watermarkMs == 0L) {
            return nowMs;
        }
        int cycles = cyclesDue(nowMs, watermarkMs, intervalMs);
        if (cycles <= 0) {
            return watermarkMs;
        }
        int completed = produceCycles.applyAsInt(cycles);
        return completed > 0 ? watermarkMs + completed * intervalMs : watermarkMs;
    }

    private int produceCycles(ItemContainer container, String dropId, int cycles, int itemsPerTick,
                              ThreadLocalRandom random) {
        ItemDropList dropList = resolveDropList(ItemDropList.getAssetMap(), dropId);
        int completed = 0;
        boolean saturated = false;
        boolean partialCycle = false;
        for (int cycle = 0; cycle < cycles; cycle++) {
            boolean cycleAdded = false;
            for (int item = 0; item < itemsPerTick; item++) {
                ProductionResult result = produce(container, dropList, dropId, random);
                cycleAdded |= result.addedAny();
                if (!result.complete()) {
                    partialCycle = cycleAdded;
                    saturated = true;
                    break;
                }
            }
            if (saturated) break;
            completed++;
        }
        if (saturated && partialCycle) completed++;
        return completed;
    }

    /** Shows the produce-ready state while the coop container holds produce. */
    public void syncInteractionState(
            @Nonnull World world,
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop
    ) {
        ItemContainer container = coop.container();
        if (container == null) {
            return;
        }
        WorldChunk chunk = world.getChunkIfInMemory(
                com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(
                        coop.block().x, coop.block().z
                )
        );
        if (chunk == null) {
            return;
        }
        BlockType block = HytaleBlockStateAccess.blockTypeAt(chunk,
                coop.block().x, coop.block().y, coop.block().z
        );
        if (block == null) {
            return;
        }
        String state = container.isEmpty()
                ? DEFAULT_INTERACTION_STATE
                : PRODUCE_READY_INTERACTION_STATE;
        try {
            HytaleBlockStateAccess.setInteractionState(chunk,
                    coop.block().x, coop.block().y, coop.block().z, block, state);
        } catch (RuntimeException ignored) {
            // Optional presentation can race a chunk state update.
        }
    }

    private ProductionResult produce(
            ItemContainer container,
            @Nullable ItemDropList dropList,
            String dropId,
            ThreadLocalRandom random
    ) {
        if (dropList == null || dropList.getContainer() == null) {
            return result(add(container, new ItemStack(dropId, 1)));
        }
        ArrayList<ItemDrop> drops = new ArrayList<>();
        dropList.getContainer().populateDrops(
                drops, random::nextDouble, dropId
        );
        boolean addedAny = false;
        for (ItemDrop drop : drops) {
            if (drop == null || drop.getItemId() == null
                    || drop.getItemId().isBlank()) {
                continue;
            }
            int quantity = drop.getRandomQuantity(random);
            if (quantity > 0) {
                if (!add(container, new ItemStack(drop.getItemId(), quantity, drop.getMetadata()))) {
                    return new ProductionResult(false, addedAny);
                }
                addedAny = true;
            }
        }
        return new ProductionResult(true, addedAny);
    }

    private ProductionResult result(boolean complete) { return new ProductionResult(complete, complete); }

    private record ProductionResult(boolean complete, boolean addedAny) { }

    static int cyclesDue(long activeTimeMs, long watermarkMs, long intervalMs) {
        if (intervalMs <= 0L) return 0;
        long elapsed = Math.max(0L, activeTimeMs - watermarkMs);
        return (int) Math.min(MAX_CATCH_UP_CYCLES_PER_SWEEP, elapsed / intervalMs);
    }

    private boolean add(ItemContainer container, ItemStack stack) {
        ItemStackTransaction transaction = container.addItemStack(stack);
        ItemStack remainder = transaction == null
                ? null : transaction.getRemainder();
        return transaction != null
                && (remainder == null || remainder.isEmpty());
    }

    @Nullable
    private ItemDropList resolveDropList(
            @Nullable DefaultAssetMap<String, ItemDropList> assets,
            String id
    ) {
        if (assets == null) {
            return null;
        }
        ItemDropList direct = assets.getAsset(id);
        if (direct != null) {
            return direct;
        }
        String normalized = normalize(id);
        Map<String, ItemDropList> map = assets.getAssetMap();
        if (map == null) {
            return null;
        }
        for (Map.Entry<String, ItemDropList> entry : map.entrySet()) {
            if (normalized != null
                    && normalized.equals(normalize(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Map<String, String> normalizeDrops(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        HashMap<String, String> normalized = new HashMap<>();
        source.forEach((role, drop) -> {
            String key = normalize(role);
            if (key != null && drop != null && !drop.isBlank()) {
                normalized.put(key, drop.trim());
            }
        });
        return normalized;
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
