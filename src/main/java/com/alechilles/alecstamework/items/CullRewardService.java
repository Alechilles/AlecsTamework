package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.AnimalAgeStage;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.HusbandryRewardEntry;
import com.alechilles.alecstamework.api.HusbandryRewardTable;
import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.api.internal.HusbandryOutcomeRuntime;
import com.alechilles.alecstamework.api.internal.HusbandryYieldResolver;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionService;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.alechilles.alecstamework.output.CompanionOutputService.FinalizedOutput;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable HusbandryToolContext tool,
            @Nullable java.util.UUID actorId
    ) {
        return apply(prepare(dropListId, npcRef, store, roleId, tool, actorId), npcRef, store);
    }

    /**
     * Resolves the managed cull batch for one role, or an unavailable outcome when its family is
     * not listed in {@code CullDropLists} (the normal death drops then apply).
     */
    static PreparedOutcome prepareManaged(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable HusbandryToolContext tool,
            @Nullable java.util.UUID actorId
    ) {
        if (!ActivityRuntime.hasManagedCullRewards(roleId)) {
            return PreparedOutcome.unavailable();
        }
        return prepare(ActivityRuntime.resolveCullDropList(roleId), npcRef, store, roleId, tool, actorId);
    }

    /**
     * Resolves one authorized managed-cull batch before a deferred durable release.
     *
     * <p>A reward table provider's table, when it returns one, replaces the configured drop list
     * as the base roll. Modifiers, the age multiplier and conversions apply to either.</p>
     */
    static PreparedOutcome prepare(
            @Nullable String dropListId,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId,
            @Nullable HusbandryToolContext tool,
            @Nullable java.util.UUID actorId
    ) {
        Random random = ThreadLocalRandom.current();
        HusbandryRewardTable table = HusbandryOutcomeRuntime.resolveRewardTable(
                HusbandryOutcomeKind.CULL_YIELD, npcRef, store, roleId, tool, actorId);
        Roll roll = baseRoll(table, table == null ? resolve(dropListId) : null, random);
        if (roll == null) {
            return PreparedOutcome.unavailable();
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (Reward reward : roll.rewards()) {
            if (table != null && !isKnownItem(reward.itemId())) {
                continue;
            }
            stacks.add(new ItemStack(
                    reward.itemId(),
                    reward.quantity(),
                    reward.metadata()
            ));
        }
        double[] wearMultiplier = {1.0};
        double age = AnimalProgressionService.slaughterMultiplier(npcRef, store, roleId);
        HusbandryYieldResolver.BatchRoll batch = new HusbandryYieldResolver.BatchRoll(random::nextDouble);
        FinalizedOutput output = CompanionOutputService.finalizeExpectedQuantity(
                stacks,
                stack -> {
                    HusbandryOutcomeModifiers modifiers = HusbandryYieldResolver.resolveCull(
                            npcRef, store, roleId, stack.getItemId(), tool, actorId);
                    if (!modifiers.toolAuthorized()) {
                        return -1.0;
                    }
                    wearMultiplier[0] = Math.min(wearMultiplier[0], modifiers.toolWearMultiplier());
                    double bonus = HusbandryYieldResolver.cullYieldBonus(npcRef, store, stack.getItemId(), modifiers);
                    return (1.0 + bonus) * age * batch.copies(modifiers) - 1.0;
                },
                random::nextDouble);
        output = HusbandryYieldResolver.applyCullConversions(
                output, npcRef, store, roleId, tool, actorId, random::nextDouble);
        return new PreparedOutcome(true, output.itemStacks(), output.itemQuantities(), wearMultiplier[0],
                AnimalProgressionService.ageStage(npcRef, store, roleId));
    }

    static Outcome apply(@Nullable PreparedOutcome prepared,
                         Ref<EntityStore> npcRef,
                         ComponentAccessor<EntityStore> store) {
        if (prepared == null || !prepared.domesticDropsPrepared()) {
            return Outcome.unavailable();
        }
        for (ItemStack stack : prepared.itemStacks()) {
            ItemUtils.dropItem(npcRef, stack, store);
        }
        return new Outcome(true, prepared.itemQuantities(), prepared.toolWearMultiplier(), prepared.ageStage());
    }

    /** Resolves one cull batch bonus from the current husbandry provider. */
    static int resolveBonusCopies(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store,
            @Nonnull DoubleSupplier random
    ) {
        HusbandryOutcomeModifiers modifiers = HusbandryYieldResolver.resolveCull(
                npcRef, store, null, null, null, null);
        return resolveBonusCopies(modifiers, random);
    }

    /** Rolls the primary and gated triple-upgrade cull chances. */
    static int resolveBonusCopies(
            @Nullable HusbandryOutcomeModifiers modifiers,
            @Nonnull DoubleSupplier random
    ) {
        return HusbandryYieldResolver.rollBatchCopies(modifiers, random) - 1;
    }

    /**
     * Picks the base roll: a provider table wins (an empty one rolls nothing), otherwise the
     * configured list. Returns {@code null} when neither exists.
     */
    @Nullable
    static Roll baseRoll(@Nullable HusbandryRewardTable table,
                         @Nullable ItemDropList dropList,
                         Random random) {
        if (table != null) {
            return roll(table, random);
        }
        if (dropList == null || dropList.getContainer() == null) {
            return null;
        }
        return roll(dropList, random);
    }

    /** Rolls each valid entry's chance once, then a uniform quantity in its inclusive range. */
    static Roll roll(@Nonnull HusbandryRewardTable table, @Nonnull Random random) {
        List<Reward> rewards = new ArrayList<>();
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (HusbandryRewardEntry entry : table.entries()) {
            if (!entry.valid() || random.nextDouble() >= entry.chance()) {
                continue;
            }
            int quantity = entry.min() + random.nextInt(entry.max() - entry.min() + 1);
            if (quantity <= 0) {
                continue;
            }
            rewards.add(new Reward(entry.itemId(), quantity, null));
            quantities.merge(entry.itemId(), quantity, Integer::sum);
        }
        return new Roll(rewards, quantities);
    }

    /** Provider tables are not asset-validated, so an unknown item is skipped instead of dropped. */
    private static boolean isKnownItem(String itemId) {
        try {
            DefaultAssetMap<String, Item> items = Item.getAssetMap();
            return items != null && items.getAsset(itemId) != null;
        } catch (RuntimeException unavailableAssetStore) {
            return false;
        }
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

    /** {@code ageStage} is the culled animal's age when its batch was prepared (null: aging disabled). */
    record Outcome(boolean domesticDropsApplied,
                   Map<String, Integer> itemQuantities,
                   double toolWearMultiplier,
                   @Nullable AnimalAgeStage ageStage) {
        Outcome {
            itemQuantities = Map.copyOf(itemQuantities);
        }

        static Outcome unavailable() {
            return new Outcome(false, Map.of(), 1.0, null);
        }
    }

    record PreparedOutcome(boolean domesticDropsPrepared,
                           List<ItemStack> itemStacks,
                           Map<String, Integer> itemQuantities,
                           double toolWearMultiplier,
                           @Nullable AnimalAgeStage ageStage) {
        PreparedOutcome {
            itemStacks = List.copyOf(itemStacks);
            itemQuantities = Map.copyOf(itemQuantities);
        }

        static PreparedOutcome unavailable() {
            return new PreparedOutcome(false, List.of(), Map.of(), 1.0, null);
        }
    }
}
