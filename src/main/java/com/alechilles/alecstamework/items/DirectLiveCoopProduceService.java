package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.compat.HytaleBlockStateAccess;
import com.alechilles.alecstamework.companion.coop.TameworkCoopSlotsComponent;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
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
import java.util.function.IntConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coop produce (spec 8.9) on the resident slot entries, following vanilla
 * {@code CoopBlock.generateProduceToInventory} for every resident, owned or unowned. The clock is
 * the coop world's game time, never real or owner-online time. Production runs on the roam-hours
 * sweep, before release: a resident whose role has a drop gets
 * {@code ceil(floor(hours since its watermark) / interval)} units (0 h gives 0, 1 to 24 h gives 1,
 * 25 to 48 h gives 2 at the 24 h interval), at most {@value #MAX_CATCH_UP_UNITS_PER_SWEEP} per
 * sweep, and its watermark moves to now even when that is 0 units. What does not fit in the coop
 * container is discarded.
 *
 * <p>The watermark ({@code producedUntilMs}, game time in ms, can be negative, 0 for none) lives
 * in the slot entry and starts at the intake time of every stay, owned or unowned; nothing carries
 * across stays. The caller writes the changed entries back to the block. A resident that stays in its slot through the roam hours (its release keeps being
 * refused) is not looked at again in the same roam window, so it produces at most once per
 * window and its entry is written at most once per window. Rules: interval
 * max(24, IntervalGameHours) game hours, {@code ItemsPerTick} drop rolls per unit, drops by role.
 * Call on the coop's world thread.
 */
public final class DirectLiveCoopProduceService {
    private static final long GAME_MILLIS_PER_HOUR = 3_600_000L;
    private static final long GAME_MILLIS_PER_DAY = 24L * GAME_MILLIS_PER_HOUR;
    private static final int MAX_CATCH_UP_UNITS_PER_SWEEP = 32;
    private static final String DEFAULT_INTERACTION_STATE = "default";
    private static final String PRODUCE_READY_INTERACTION_STATE =
            "Produce_Ready";

    /** A current resident: its slot entry and role (a companion's from its record, an unowned one's from its entity). */
    public record Resident(@Nonnull TameworkCoopSlotsComponent.Slot entry, @Nullable String roleId) {
    }

    /**
     * Produces for each resident and returns the entries whose watermark changed. A resident whose
     * role has no drop is skipped without a change. {@code worldGameTimeMs} is the coop world's
     * game time; 0 means unknown and produces nothing.
     */
    @Nonnull
    public List<TameworkCoopSlotsComponent.Slot> produce(
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop,
            @Nonnull List<Resident> residents,
            long worldGameTimeMs
    ) {
        ItemContainer container = coop.container();
        TwCoopConfig config = coop.config();
        Map<String, String> drops = config == null ? Map.of()
                : normalizeDrops(config.getProduceRules().getDropsByRole());
        if (container == null || drops.isEmpty() || residents.isEmpty() || worldGameTimeMs == 0L) {
            return List.of();
        }
        TwCoopConfig.ProduceRules rules = config.getProduceRules();
        long intervalHours = Math.max(
                WorldTimeResource.HOURS_PER_DAY,
                rules.getIntervalGameHours()
        );
        long windowStartMs = roamWindowStartMs(worldGameTimeMs,
                config.getLifecycleRules().getResidentRoamStartHour());
        int itemsPerTick = rules.getItemsPerTick();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        List<TameworkCoopSlotsComponent.Slot> changed = new ArrayList<>();
        for (Resident resident : residents) {
            String role = normalize(resident.roleId());
            String dropId = role == null ? null : drops.get(role);
            if (dropId == null) {
                continue;
            }
            TameworkCoopSlotsComponent.Slot entry = resident.entry();
            long next = advance(entry.producedUntilMs(), worldGameTimeMs, windowStartMs, intervalHours,
                    units -> produceUnits(container, dropId, units, itemsPerTick, random));
            if (next != entry.producedUntilMs()) {
                changed.add(new TameworkCoopSlotsComponent.Slot(entry.slot(), entry.profileId(), entry.generation(),
                        entry.unownedEntity(), next));
            }
        }
        return changed;
    }

    /**
     * When the current roam window opened: the latest game time at or before {@code nowMs} whose
     * hour of day is {@code roamStartHour}. A coop that always roams gets one window per day.
     */
    static long roamWindowStartMs(long nowMs, int roamStartHour) {
        long startOffsetMs = Math.floorMod(roamStartHour, 24) * GAME_MILLIS_PER_HOUR;
        return Math.floorDiv(nowMs - startOffsetMs, GAME_MILLIS_PER_DAY) * GAME_MILLIS_PER_DAY + startOffsetMs;
    }

    /**
     * The next watermark. A watermark inside the current roam window (from {@code windowStartMs}
     * up to now) is kept and nothing is produced: this window already ran for the resident. No
     * watermark (0) starts at {@code nowMs} without producing. Otherwise the due units, when
     * there are any, go to {@code produceUnits}, and the watermark becomes {@code nowMs}.
     */
    static long advance(long watermarkMs, long nowMs, long windowStartMs, long intervalHours,
                        IntConsumer produceUnits) {
        if (watermarkMs == 0L) {
            return nowMs;
        }
        if (watermarkMs >= windowStartMs && watermarkMs <= nowMs) {
            return watermarkMs;
        }
        int units = unitsDue(watermarkMs, nowMs, intervalHours);
        if (units > 0) {
            produceUnits.accept(units);
        }
        return nowMs;
    }

    /**
     * Vanilla's count: whole game hours since the watermark, divided by the interval and rounded
     * up, capped at {@value #MAX_CATCH_UP_UNITS_PER_SWEEP}. A watermark ahead of now gives 0.
     */
    static int unitsDue(long watermarkMs, long nowMs, long intervalHours) {
        long hours = Math.floorDiv(nowMs - watermarkMs, GAME_MILLIS_PER_HOUR);
        if (hours <= 0L || intervalHours <= 0L) {
            return 0;
        }
        return (int) Math.min(MAX_CATCH_UP_UNITS_PER_SWEEP, (hours + intervalHours - 1L) / intervalHours);
    }

    /** Rolls the drop {@code itemsPerTick} times per unit; stops when the container is full. */
    private void produceUnits(ItemContainer container, String dropId, int units, int itemsPerTick,
                              ThreadLocalRandom random) {
        ItemDropList dropList = resolveDropList(ItemDropList.getAssetMap(), dropId);
        for (int unit = 0; unit < units; unit++) {
            for (int item = 0; item < itemsPerTick; item++) {
                if (!produce(container, dropList, dropId, random)) {
                    return;
                }
            }
        }
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

    /** Adds one roll of the drop; false when the container could not take all of it. */
    private boolean produce(
            ItemContainer container,
            @Nullable ItemDropList dropList,
            String dropId,
            ThreadLocalRandom random
    ) {
        if (dropList == null || dropList.getContainer() == null) {
            return add(container, new ItemStack(dropId, 1));
        }
        ArrayList<ItemDrop> drops = new ArrayList<>();
        dropList.getContainer().populateDrops(
                drops, random::nextDouble, dropId
        );
        for (ItemDrop drop : drops) {
            if (drop == null || drop.getItemId() == null
                    || drop.getItemId().isBlank()) {
                continue;
            }
            int quantity = drop.getRandomQuantity(random);
            if (quantity > 0 && !add(container, new ItemStack(drop.getItemId(), quantity, drop.getMetadata()))) {
                return false;
            }
        }
        return true;
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
