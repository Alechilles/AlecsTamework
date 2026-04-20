package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.items.ActionDropItem;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;

/**
 * Drop-item action for harvest flows that supports trait-driven bonus drops.
 *
 * <p>This action behaves like {@code DropItem} for regular output and then performs
 * one additional identical drop pass when the Bounty trait proc succeeds.
 */
public final class ActionTameworkHarvestDrop extends ActionDropItem {
    private static final String HARVEST_DOUBLE_DROP_CHANCE_EFFECT_KEY = "HarvestDoubleDropChanceMultiplier";

    public ActionTameworkHarvestDrop(@Nonnull BuilderActionTameworkHarvestDrop builder, @Nonnull BuilderSupport support) {
        super(builder, support);
    }

    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           InfoProvider sensorInfo,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        setOnce();
        prepareDelay();
        startDelay(role.getEntitySupport());

        List<ItemStack> baseDrops = resolveDrops();
        if (baseDrops.isEmpty()) {
            return true;
        }
        List<ItemStack> drops = shouldDoubleDrops(ref, store) ? duplicateDrops(baseDrops) : baseDrops;

        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        float eyeHeight = modelComponent != null ? modelComponent.getModel().getEyeHeight(ref, store) : 0.0F;
        float height = -eyeHeight;
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            newDirection(ref, pickDistance(), height, store);
            ItemUtils.throwItem(ref, store, drop, this.dropDirection, this.throwSpeed);
        }
        return true;
    }

    private List<ItemStack> resolveDrops() {
        ArrayList<ItemStack> drops = new ArrayList<>();
        if (this.item != null) {
            ItemStack drop = InventoryHelper.createItem(this.item);
            if (drop != null && !drop.isEmpty()) {
                drops.add(drop);
            }
            return drops;
        }

        ItemModule itemModule = ItemModule.get();
        if (!itemModule.isEnabled() || this.dropList == null || this.dropList.isBlank()) {
            return drops;
        }
        for (ItemStack randomItem : itemModule.getRandomItemDrops(this.dropList)) {
            if (randomItem == null || randomItem.isEmpty()) {
                continue;
            }
            drops.add(randomItem);
        }
        return drops;
    }

    private boolean shouldDoubleDrops(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                HARVEST_DOUBLE_DROP_CHANCE_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(multiplier)) {
            return false;
        }
        double chance = clamp(multiplier - 1.0, 0.0, 1.0);
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    private List<ItemStack> duplicateDrops(List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return List.of();
        }
        ArrayList<ItemStack> out = new ArrayList<>(drops.size() * 2);
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            out.add(stack);
            ItemStack duplicate = cloneStack(stack);
            if (duplicate != null && !duplicate.isEmpty()) {
                out.add(duplicate);
            }
        }
        return out;
    }

    private ItemStack cloneStack(ItemStack stack) {
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        int quantity = stack.getQuantity();
        if (quantity <= 0) {
            return null;
        }
        return new ItemStack(
                itemId,
                quantity,
                stack.getDurability(),
                stack.getMaxDurability(),
                stack.getMetadata()
        );
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
