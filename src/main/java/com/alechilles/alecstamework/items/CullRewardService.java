package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.alechilles.alecstamework.output.CompanionOutputService.FinalizedOutput;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** Rolls and drops one configured domestic reward on the world thread. */
final class CullRewardService {

    private CullRewardService() {
    }

    static Outcome apply(
            @Nullable String dropListId,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store
    ) {
        ItemDropList dropList = resolve(dropListId);
        if (dropList == null || dropList.getContainer() == null) {
            return Outcome.unavailable();
        }
        Random random = ThreadLocalRandom.current();
        Roll roll = roll(dropList, random);
        List<ItemStack> stacks = new ArrayList<>();
        for (Reward reward : roll.rewards()) {
            stacks.add(new ItemStack(
                    reward.itemId(),
                    reward.quantity(),
                    reward.metadata()
            ));
        }
        int bonusCopies = resolveBonusCopies(
                npcRef, store, random::nextDouble);
        FinalizedOutput output = CompanionOutputService.finalizeDrops(
                stacks, bonusCopies);
        for (ItemStack stack : output.itemStacks()) {
            ItemUtils.dropItem(npcRef, stack, store);
        }
        return new Outcome(true, output.itemQuantities());
    }

    /** Resolves one cull batch bonus from the current husbandry provider. */
    static int resolveBonusCopies(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store,
            @Nonnull DoubleSupplier random
    ) {
        HusbandryOutcomeModifiers modifiers = HusbandryOutcomeRuntime.resolve(
                HusbandryOutcomeKind.CULL_YIELD,
                npcRef,
                store,
                (String) null,
                null
        );
        return resolveBonusCopies(modifiers, random);
    }

    /** Rolls the primary and gated triple-upgrade cull chances. */
    static int resolveBonusCopies(
            @Nullable HusbandryOutcomeModifiers modifiers,
            @Nonnull DoubleSupplier random
    ) {
        HusbandryOutcomeModifiers safe = modifiers == null
                ? HusbandryOutcomeModifiers.identity() : modifiers;
        if (!rollChance(safe.bonusOutputChance(), random)) {
            return 0;
        }
        return rollChance(safe.tripleOutputChance(), random) ? 2 : 1;
    }

    static Roll roll(ItemDropList dropList, Random random) {
        if (dropList == null || dropList.getContainer() == null
                || random == null) {
            return new Roll(List.of(), Map.of());
        }
        ItemDropContainer container = dropList.getContainer();
        List<ItemDrop> drops = new ArrayList<>();
        container.populateDrops(drops, random::nextDouble, dropList.getId());
        List<Reward> rewards = new ArrayList<>();
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (ItemDrop drop : drops) {
            if (drop == null || drop.getItemId() == null
                    || drop.getItemId().isBlank()) {
                continue;
            }
            int quantity = drop.getRandomQuantity(random);
            if (quantity <= 0) {
                continue;
            }
            String itemId = drop.getItemId();
            rewards.add(new Reward(itemId, quantity, drop.getMetadata()));
            quantities.merge(itemId, quantity, Integer::sum);
        }
        return new Roll(rewards, quantities);
    }

    private static boolean rollChance(double chance, @Nonnull DoubleSupplier random) {
        if (!Double.isFinite(chance)) {
            return false;
        }
        double bounded = Math.max(0.0, Math.min(1.0, chance));
        return random.getAsDouble() < bounded;
    }

    @Nullable
    private static ItemDropList resolve(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, ItemDropList> assets = ItemDropList.getAssetMap();
        if (assets == null) {
            return null;
        }
        ItemDropList direct = assets.getAsset(id.trim());
        if (direct != null) {
            return direct;
        }
        Map<String, ItemDropList> values = assets.getAssetMap();
        if (values == null) {
            return null;
        }
        for (Map.Entry<String, ItemDropList> entry : values.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase(id.trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    record Reward(String itemId, int quantity, @Nullable BsonDocument metadata) {
    }

    record Roll(List<Reward> rewards, Map<String, Integer> itemQuantities) {
        Roll {
            rewards = List.copyOf(rewards);
            itemQuantities = Map.copyOf(itemQuantities);
        }
    }

    record Outcome(boolean domesticDropsApplied,
                   Map<String, Integer> itemQuantities) {
        Outcome {
            itemQuantities = Map.copyOf(itemQuantities);
        }

        static Outcome unavailable() {
            return new Outcome(false, Map.of());
        }
    }
}
