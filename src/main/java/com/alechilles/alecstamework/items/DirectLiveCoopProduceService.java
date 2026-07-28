package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
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
import java.time.Instant;
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
    private static final String DEFAULT_INTERACTION_STATE = "default";
    private static final String PRODUCE_READY_INTERACTION_STATE =
            "Produce_Ready";

    private final Map<CoopSlotKey, Long> lastProducedAtBySlot =
            new HashMap<>();

    void produceOnRoamingStart(
            @Nonnull HytaleDirectLiveCoopScanner.LoadedCoop coop,
            @Nonnull WorldTimeResource worldTime,
            @Nonnull Map<CoopSlotKey, CoopOccupancy> occupancies,
            @Nonnull Map<com.alechilles.alecstamework.companion.identity.ProfileId,
                    CompanionProfileProjectionState> profiles
    ) {
        ItemContainer container = coop.container();
        Map<String, String> drops = normalizeDrops(
                coop.config().getProduceRules().getDropsByRole()
        );
        if (container == null || drops.isEmpty()) {
            return;
        }
        long now = gameTime(worldTime);
        TwCoopConfig.ProduceRules rules = coop.config().getProduceRules();
        long intervalHours = Math.max(
                WorldTimeResource.HOURS_PER_DAY,
                rules.getIntervalGameHours()
        );
        long intervalMs = intervalHours * GAME_MILLIS_PER_HOUR;
        int itemsPerTick = rules.getItemsPerTick();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        DefaultAssetMap<String, ItemDropList> dropLists =
                ItemDropList.getAssetMap();

        for (CoopSlotKey slot : coop.slots()) {
            CoopOccupancy occupancy = occupancies.get(slot);
            if (occupancy == null) {
                continue;
            }
            CompanionProfileProjectionState profile =
                    profiles.get(occupancy.residency().profileId());
            String role = normalize(profile == null ? null : profile.roleId());
            String dropId = role == null ? null : drops.get(role);
            if (dropId == null) {
                continue;
            }
            Long previous = lastProducedAtBySlot.get(slot);
            if (previous == null || previous > now) {
                previous = now - intervalMs;
            }
            long elapsedHours = (now - previous) / GAME_MILLIS_PER_HOUR;
            if (elapsedHours < intervalHours) {
                lastProducedAtBySlot.put(slot, previous);
                continue;
            }
            int cycles = (int) Math.max(
                    1L,
                    (long) Math.ceil(
                            (double) elapsedHours / (double) intervalHours
                    )
            );
            ItemDropList dropList = resolveDropList(dropLists, dropId);
            boolean saturated = false;
            for (int cycle = 0; cycle < cycles && !saturated; cycle++) {
                for (int item = 0; item < itemsPerTick; item++) {
                    if (!produce(container, dropList, dropId, random)) {
                        saturated = true;
                        break;
                    }
                }
            }
            lastProducedAtBySlot.put(slot, now);
            if (saturated) {
                break;
            }
        }
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
            if (quantity > 0 && !add(container, new ItemStack(
                    drop.getItemId(), quantity, drop.getMetadata()
            ))) {
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

    private long gameTime(WorldTimeResource worldTime) {
        Instant time = worldTime.getGameTime();
        return time == null ? System.currentTimeMillis() : time.toEpochMilli();
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
