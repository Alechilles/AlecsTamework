package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopProductionState;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Retains the released coop produce behavior without participating in persistence authority. */
final class DirectLiveCoopProduceService {
    private static final long GAME_MILLIS_PER_HOUR = 3_600_000L;
    private static final int MAX_CATCH_UP_CYCLES_PER_SWEEP = 32;
    private static final String DEFAULT_INTERACTION_STATE = "default";
    private static final String PRODUCE_READY_INTERACTION_STATE =
            "Produce_Ready";

    boolean produceWhileRoaming(
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop,
            @Nonnull Map<CoopSlotKey, CoopOccupancy> occupancies,
            @Nonnull Map<com.alechilles.alecstamework.companion.identity.ProfileId,
                    CompanionProfileProjectionState> profiles,
            @Nonnull DirectLiveCoopProductionState productionState,
            double gameSecondsPerRealSecond
    ) {
        ItemContainer container = coop.container();
        Map<String, String> drops = normalizeDrops(
                coop.config().getProduceRules().getDropsByRole()
        );
        if (container == null || drops.isEmpty()) {
            return true;
        }
        TwCoopConfig.ProduceRules rules = coop.config().getProduceRules();
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

        boolean readyForRelease = true;
        for (CoopSlotKey slot : coop.slots()) {
            CoopOccupancy occupancy = occupancies.get(slot);
            if (occupancy == null) {
                continue;
            }
            // An unfinished capture/release owns this resident, including quarantined releases.
            if (occupancy.slot().reserved()) {
                readyForRelease = false;
                continue;
            }
            CompanionProfileProjectionState profile =
                    profiles.get(occupancy.residency().profileId());
            if (profile == null) {
                continue;
            }
            var profileId = occupancy.residency().profileId();
            if (productionState.pending(profileId)) {
                readyForRelease = false;
                continue;
            }
            String role = normalize(profile == null ? null : profile.roleId());
            String dropId = role == null ? null : drops.get(role);
            if (dropId == null) {
                continue;
            }
            Long now = productionState.activeTime(profileId, occupancy.residency().snapshotId()).orElse(null);
            if (now == null) {
                readyForRelease = false;
                continue;
            }
            if (productionState.deathDue(profileId, profile.roleId())) continue;
            DirectLiveCoopProductionState.Watermark watermark = productionState.watermark(profileId).orElse(null);
            if (watermark == null) {
                readyForRelease = false;
                if (productionState.malformedWatermark(profileId)) continue;
                // Migration initializes at the current eligible time: no free first interval.
                productionState.record(profileId, now, 0L);
                continue;
            }
            int cycles = cyclesDue(now, watermark.eligibleMs(), intervalMs);
            if (cycles <= 0) continue;
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
            if (completed > 0) {
                productionState.record(profileId, watermark.eligibleMs() + completed * intervalMs,
                        watermark.revision());
            }
        }
        return readyForRelease;
    }

    void syncInteractionState(
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
        BlockType block = chunk.getBlockType(
                coop.block().x, coop.block().y, coop.block().z
        );
        if (block == null) {
            return;
        }
        String state = container.isEmpty()
                ? DEFAULT_INTERACTION_STATE
                : PRODUCE_READY_INTERACTION_STATE;
        try {
            chunk.setBlockInteractionState(coop.block(), block, state);
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
