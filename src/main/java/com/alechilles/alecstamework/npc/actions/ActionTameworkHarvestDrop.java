package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.debug.CompanionXpEventDebugLogService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.alechilles.alecstamework.output.CompanionOutputService.FinalizedOutput;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.items.ActionDropItem;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Drop-item action for harvest flows that supports trait- and husbandry-driven bonus drops.
 *
 * <p>This action behaves like {@code DropItem} for regular output and then performs
 * the resolved number of additional identical drop passes.
 */
public final class ActionTameworkHarvestDrop extends ActionDropItem {
    private final boolean awardXp;
    private final StdScope roleParameterScopeSnapshot;

    public ActionTameworkHarvestDrop(@Nonnull BuilderActionTameworkHarvestDrop builder, @Nonnull BuilderSupport support) {
        super(builder, support);
        this.awardXp = builder.getAwardXp(support);
        this.roleParameterScopeSnapshot = InteractionRoleParameterScope.snapshot(support);
    }

    public boolean execute(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           InfoProvider sensorInfo,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        setOnce();
        prepareDelay();
        EntitySupport entitySupport = NpcSupportAccess.entity(role, ref, store);
        startDelay(entitySupport);

        List<ItemStack> baseDrops = resolveDrops();
        if (baseDrops.isEmpty()) {
            logHarvestDropAttempt("skipped reason=no-resolved-drops item=" + valueOrNull(this.item)
                    + " dropList=" + valueOrNull(this.dropList));
            return true;
        }
        int bonusCopies = CompanionHarvestBonusService.resolveBonusCopies(
                ref,
                store,
                role,
                resolveProductId(baseDrops),
                java.util.concurrent.ThreadLocalRandom.current()::nextDouble
        );
        FinalizedOutput output = CompanionOutputService.finalizeDrops(baseDrops, bonusCopies);
        List<ItemStack> drops = output.itemStacks();
        UUID operationId = UUID.randomUUID();
        String activityContext = resolveActivityContext(
                role,
                awardXp,
                roleParameterScopeSnapshot
        );

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
        if (dropped && awardXp) {
            AwardResult result = CompanionLevelingService.awardHarvestXp(ref, store);
            logHarvestDropAward(ref, store, baseDrops.size(), drops.size(), droppedCount, result);
            ActivityRuntime.publishHarvest(
                    operationId,
                    role == null ? null : role.getRoleName(),
                    activityContext,
                    ActivityRuntime.resolveOwnerId(ref, store),
                    ActivityRuntime.resolveCompanionId(ref, store),
                    output.itemQuantities(),
                    result
            );
        } else if (dropped) {
            ActivityRuntime.publishHarvest(
                    operationId,
                    role == null ? null : role.getRoleName(),
                    activityContext,
                    ActivityRuntime.resolveOwnerId(ref, store),
                    ActivityRuntime.resolveCompanionId(ref, store),
                    output.itemQuantities(),
                    null
            );
        } else if (!dropped) {
            logHarvestDropAttempt("skipped reason=resolved-drops-empty baseDrops=" + baseDrops.size()
                    + " attemptedDrops=" + drops.size()
                    + " item=" + valueOrNull(this.item)
                    + " dropList=" + valueOrNull(this.dropList));
        }
        return true;
    }

    @Nullable
    static String resolveActivityContext(
            @Nullable Role role,
            boolean manualHarvest,
            @Nullable StdScope roleParameterScopeSnapshot
    ) {
        if (!manualHarvest || role == null) {
            return null;
        }
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        String paramName = global == null
                ? "HarvestInteractionContext"
                : global.getHarvestContextParam();
        return new InteractionParamResolver(
                roleParameterScopeSnapshot,
                null,
                null,
                null
        )
                .getStringParam(role, null, paramName);
    }

    /** Bridges the Update 6 callback while retaining the Update 5 Role overload above. */
    @Override
    public boolean execute(@Nonnull Ref<EntityStore> ref,
                           @Nonnull ExecutionSupport support,
                           InfoProvider sensorInfo,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            return execute(ref, support.getRole(), sensorInfo, dt, store);
        } finally {
            NpcSupportAccess.restore(previous);
        }
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

    @Nullable
    private static String resolveProductId(@Nonnull List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty()
                    && drop.getItemId() != null && !drop.getItemId().isBlank()) {
                return drop.getItemId();
            }
        }
        return null;
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
