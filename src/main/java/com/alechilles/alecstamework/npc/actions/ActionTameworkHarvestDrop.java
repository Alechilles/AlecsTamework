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
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryYieldResolver;
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
 * Drop-item action that applies trait and husbandry yield to each resolved product.
 *
 * <p>Manual shearing can consume its batch prepared at authorization; other drops
 * resolve their expected quantities when executed.
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

        HusbandryHarvestUseContext.CapturedUse toolUse = HusbandryHarvestUseContext.current(ref, store);
        FinalizedOutput preparedOutput = toolUse.preparedOutputFor(this.dropList, awardXp);
        boolean usesCapturedTool = awardXp && toolUse.tool().present()
                && (preparedOutput != null || toolUse.preparedDropList() == null);
        List<ItemStack> baseDrops = preparedOutput == null ? resolveDrops() : preparedOutput.itemStacks();
        if (baseDrops.isEmpty()) {
            if (preparedOutput != null) {
                HusbandryHarvestUseContext.finish(ref, store);
            }
            logHarvestDropAttempt("skipped reason=no-resolved-drops item=" + valueOrNull(this.item)
                    + " dropList=" + valueOrNull(this.dropList));
            return true;
        }
        FinalizedOutput output = preparedOutput != null ? preparedOutput
                : CompanionOutputService.finalizeExpectedQuantity(
                        baseDrops,
                        stack -> {
                            HusbandryOutcomeModifiers modifiers = HusbandryYieldResolver.resolveHarvest(
                                    ref, store, role == null ? null : role.getRoleName(), stack.getItemId(),
                                    usesCapturedTool ? toolUse.tool() : null,
                                    usesCapturedTool ? toolUse.actorId() : null);
                            return modifiers.toolAuthorized()
                                    ? HusbandryYieldResolver.harvestYieldBonus(
                                            ref, store, stack.getItemId(), modifiers,
                                            CompanionHarvestBonusService.expectedDropDuplicateYieldBonus(ref, store, role))
                                    : -1.0;
                        },
                        java.util.concurrent.ThreadLocalRandom.current()::nextDouble
                );
        if (preparedOutput == null) {
            output = HusbandryYieldResolver.applyHarvestConversions(
                    output, ref, store, role == null ? null : role.getRoleName(),
                    usesCapturedTool ? toolUse.tool() : null,
                    usesCapturedTool ? toolUse.actorId() : null,
                    java.util.concurrent.ThreadLocalRandom.current()::nextDouble);
        }
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
        if (dropped && usesCapturedTool) {
            HusbandryHarvestUseContext.applyWear(resolveInteractionPlayer(ref, role, store), toolUse,
                    toolUse.wearMultiplier());
        }
        if (usesCapturedTool || !toolUse.tool().present()) {
            HusbandryHarvestUseContext.finish(ref, store);
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
                    result,
                    usesCapturedTool ? toolUse.tool() : null,
                    usesCapturedTool ? toolUse.actorId() : null
            );
        } else if (dropped) {
            ActivityRuntime.publishHarvest(
                    operationId,
                    role == null ? null : role.getRoleName(),
                    activityContext,
                    ActivityRuntime.resolveOwnerId(ref, store),
                    ActivityRuntime.resolveCompanionId(ref, store),
                    output.itemQuantities(),
                    null,
                    usesCapturedTool ? toolUse.tool() : null,
                    usesCapturedTool ? toolUse.actorId() : null
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
    private static com.hypixel.hytale.server.core.entity.entities.Player resolveInteractionPlayer(
            Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        com.hypixel.hytale.server.npc.role.support.StateSupport state =
                NpcSupportAccess.state(role, npcRef, store);
        Ref<EntityStore> playerRef = state == null ? null : state.getInteractionIterationTarget();
        return playerRef == null || !playerRef.isValid() ? null
                : store.getComponent(playerRef,
                        com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
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
