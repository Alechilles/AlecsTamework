package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.debug.CompanionXpEventDebugLogService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Drop-item action for harvest flows that supports trait-driven bonus drops.
 *
 * <p>This action behaves like {@code DropItem} for regular output and then performs
 * one additional identical drop pass when the Bounty trait proc succeeds.
 */
public final class ActionTameworkHarvestDrop extends ActionDropItem {
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
            logHarvestDropAttempt("skipped reason=no-resolved-drops item=" + valueOrNull(this.item)
                    + " dropList=" + valueOrNull(this.dropList));
            return true;
        }
        List<ItemStack> drops = CompanionHarvestBonusService.shouldDuplicateDrops(ref, store, role)
                ? duplicateDrops(baseDrops)
                : baseDrops;

        ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        float eyeHeight = modelComponent != null ? modelComponent.getModel().getEyeHeight(ref, store) : 0.0F;
        float height = -eyeHeight;
        boolean dropped = false;
        int droppedCount = 0;
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            newDirection(ref, pickDistance(), height, store);
            ItemUtils.throwItem(ref, store, drop, this.dropDirection, this.throwSpeed);
            dropped = true;
            droppedCount++;
        }
        if (dropped) {
            AwardResult result = CompanionLevelingService.awardHarvestXp(ref, store);
            logHarvestDropAward(ref, store, baseDrops.size(), drops.size(), droppedCount, result);
        } else {
            logHarvestDropAttempt("skipped reason=resolved-drops-empty baseDrops=" + baseDrops.size()
                    + " attemptedDrops=" + drops.size()
                    + " item=" + valueOrNull(this.item)
                    + " dropList=" + valueOrNull(this.dropList));
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

    private void logHarvestDropAward(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull Store<EntityStore> store,
                                     int baseDropCount,
                                     int attemptedDropCount,
                                     int droppedCount,
                                     @Nonnull AwardResult result) {
        if (!isXpEventDebugEnabled()) {
            return;
        }
        String readiness = result.applied()
                ? "reason=awarded"
                : CompanionLevelingService.describeHarvestXpReadiness(ref, store);
        logHarvestDropAttempt("award applied=" + result.applied()
                + " awardedXp=" + result.awardedXp()
                + " level=" + result.previousLevel() + "->" + result.currentLevel()
                + " totalXp=" + result.totalXp()
                + " baseDrops=" + baseDropCount
                + " attemptedDrops=" + attemptedDropCount
                + " dropped=" + droppedCount
                + " item=" + valueOrNull(this.item)
                + " dropList=" + valueOrNull(this.dropList)
                + " " + readiness);
    }

    private void logHarvestDropAttempt(@Nonnull String message) {
        CompanionXpEventDebugLogService debugLog = resolveXpEventDebugLogService();
        if (debugLog != null) {
            debugLog.logHarvestDropAttempt(message);
        }
    }

    private boolean isXpEventDebugEnabled() {
        CompanionXpEventDebugLogService debugLog = resolveXpEventDebugLogService();
        return debugLog != null && debugLog.isEnabled();
    }

    @Nullable
    private CompanionXpEventDebugLogService resolveXpEventDebugLogService() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getCompanionXpEventDebugLogService() : null;
    }

    @Nonnull
    private static String valueOrNull(@Nullable Object value) {
        return value == null ? "<null>" : value.toString();
    }
}
