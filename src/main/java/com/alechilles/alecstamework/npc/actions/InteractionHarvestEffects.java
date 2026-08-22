package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.actions.TameworkInteractEffects.HarvestContainerOutcome;
import com.alechilles.alecstamework.npc.actions.TameworkInteractEffects.HarvestContainerResult;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns harvest cooldown, held-container transformation, and harvest diagnostics. */
final class InteractionHarvestEffects {
    private static final String CONTAINER_BUCKET_ITEM_ID = "Container_Bucket";
    private static final String DECO_BUCKET_ITEM_ID = "Deco_Bucket";
    private static final String HARVEST_ADD_ITEM_BUCKET_PARAM = "HarvestAddItemBucket";
    private static final String HARVEST_ADD_ITEM_DECO_BUCKET_PARAM = "HarvestAddItemDecoBucket";
    private static final String HARVEST_TIMEOUT_PARAMETER = "HarvestTimeout";

    private final ActionTameworkInteract owner;
    private final InteractionInventoryEffects inventoryEffects;
    private final InteractionStateEffects stateEffects;

    InteractionHarvestEffects(@Nonnull ActionTameworkInteract owner,
                              @Nonnull InteractionInventoryEffects inventoryEffects,
                              @Nonnull InteractionStateEffects stateEffects) {
        this.owner = owner;
        this.inventoryEffects = inventoryEffects;
        this.stateEffects = stateEffects;
    }

    boolean applyStartHarvest(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        return stateEffects.applyStartHarvest(npcRef, role, store);
    }

    boolean isCooldownReady(Ref<EntityStore> npcRef,
                            Role role,
                            Store<EntityStore> store,
                            InteractionContextSnapshot context) {
        double baseSeconds = resolveTimeoutSeconds(role, context);
        logDebug("TameworkHarvestDebug: cooldown-ready-request"
                + " role=" + roleName(role)
                + " held=" + heldItem(context)
                + " baseSeconds=" + baseSeconds);
        boolean ready = ActionTameworkHarvestAlarm.isHarvestCooldownReady(npcRef, role, store, baseSeconds);
        logDebug("TameworkHarvestDebug: cooldown-ready-response"
                + " role=" + roleName(role)
                + " held=" + heldItem(context)
                + " baseSeconds=" + baseSeconds
                + " ready=" + ready);
        return ready;
    }

    boolean ensureCooldownAfterState(Ref<EntityStore> npcRef,
                                     Role role,
                                     Store<EntityStore> store,
                                     InteractionContextSnapshot context) {
        double baseSeconds = resolveTimeoutSeconds(role, context);
        logDebug("TameworkHarvestDebug: cooldown-ensure-request"
                + " role=" + roleName(role)
                + " held=" + heldItem(context)
                + " baseSeconds=" + baseSeconds);
        boolean applied = ActionTameworkHarvestAlarm.ensureHarvestCooldownActive(
                npcRef, role, store, baseSeconds
        );
        logDebug("TameworkHarvestDebug: cooldown-ensure-response"
                + " role=" + roleName(role)
                + " held=" + heldItem(context)
                + " baseSeconds=" + baseSeconds
                + " applied=" + applied);
        return applied;
    }

    void logExecution(@Nonnull String stage,
                      @Nullable String interactionConfigId,
                      int interactionIndex,
                      @Nullable Role role,
                      @Nullable InteractionContextSnapshot context) {
        logDebug("TameworkHarvestDebug: execution"
                + " stage=" + stage
                + " config=" + text(interactionConfigId)
                + " index=" + interactionIndex
                + " role=" + roleName(role)
                + " held=" + heldItem(context));
    }

    @Nonnull
    HarvestContainerOutcome applyContainerTransform(Ref<EntityStore> npcRef,
                                                    Store<EntityStore> store,
                                                    Role role,
                                                    Player player,
                                                    InteractionContextSnapshot context) {
        String bucketOutput = owner.getRoleStringParam(role, context, HARVEST_ADD_ITEM_BUCKET_PARAM);
        String decoBucketOutput = owner.getRoleStringParam(
                role, context, HARVEST_ADD_ITEM_DECO_BUCKET_PARAM
        );
        if (isBlank(bucketOutput) && isBlank(decoBucketOutput)) {
            logContainer(role, context, HarvestContainerResult.NOT_CONFIGURED,
                    bucketOutput, decoBucketOutput, null, false);
            return new HarvestContainerOutcome(HarvestContainerResult.NOT_CONFIGURED, false);
        }
        String activeItem = context != null ? context.activeItemId : null;
        boolean transformed = transformHeldContainer(player, context, activeItem, bucketOutput, decoBucketOutput);
        if (!transformed) {
            logContainer(role, context, HarvestContainerResult.FAILED,
                    bucketOutput, decoBucketOutput, null, false);
            return new HarvestContainerOutcome(HarvestContainerResult.FAILED, false);
        }
        String bonusMode = owner.getRoleStringParam(
                role, context, CompanionHarvestBonusService.HARVEST_BONUS_MODE_PARAM
        );
        boolean preserveCooldown = CompanionHarvestBonusService.shouldPreserveCooldown(
                bonusMode, npcRef, store
        );
        if (preserveCooldown) {
            CompanionHarvestBonusService.markCooldownSkip(npcRef, store);
        }
        logContainer(role, context, HarvestContainerResult.APPLIED,
                bucketOutput, decoBucketOutput, bonusMode, preserveCooldown);
        String outputItem = CONTAINER_BUCKET_ITEM_ID.equalsIgnoreCase(activeItem)
                ? bucketOutput
                : decoBucketOutput;
        return new HarvestContainerOutcome(
                HarvestContainerResult.APPLIED,
                preserveCooldown,
                Map.of(outputItem, 1)
        );
    }

    private boolean transformHeldContainer(Player player,
                                           InteractionContextSnapshot context,
                                           @Nullable String activeItem,
                                           @Nullable String bucketOutput,
                                           @Nullable String decoBucketOutput) {
        if (!isBlank(bucketOutput) && CONTAINER_BUCKET_ITEM_ID.equalsIgnoreCase(activeItem)) {
            return inventoryEffects.replaceHeldItem(
                    player, context, CONTAINER_BUCKET_ITEM_ID, bucketOutput
            );
        }
        return !isBlank(decoBucketOutput)
                && DECO_BUCKET_ITEM_ID.equalsIgnoreCase(activeItem)
                && inventoryEffects.replaceHeldItem(player, context, DECO_BUCKET_ITEM_ID, decoBucketOutput);
    }

    private double resolveTimeoutSeconds(Role role, InteractionContextSnapshot context) {
        String[] timeoutRange = owner.getRoleStringArrayParam(role, context, HARVEST_TIMEOUT_PARAMETER);
        double rangeSeconds = HarvestAlarmTimeBasis.resolveTemporalRangeSeconds(
                timeoutRange,
                ThreadLocalRandom.current()::nextDouble
        );
        return rangeSeconds > 0.0
                ? rangeSeconds
                : owner.getRoleNumberParam(role, context, HARVEST_TIMEOUT_PARAMETER, 0.0);
    }

    private void logContainer(Role role,
                              InteractionContextSnapshot context,
                              HarvestContainerResult result,
                              @Nullable String bucketOutput,
                              @Nullable String decoBucketOutput,
                              @Nullable String bonusMode,
                              boolean preserveCooldown) {
        logDebug("TameworkHarvestDebug: container"
                + " role=" + roleName(role)
                + " held=" + heldItem(context)
                + " bucketOutput=" + text(bucketOutput)
                + " decoBucketOutput=" + text(decoBucketOutput)
                + " harvestBonusMode=" + text(bonusMode)
                + " result=" + result
                + " preserveCooldown=" + preserveCooldown);
    }

    private void logDebug(@Nonnull String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.isDebugHarvestEnabled() && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(message);
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String roleName(@Nullable Role role) {
        String roleName = role != null ? role.getRoleName() : null;
        return roleName != null && !roleName.isBlank() ? roleName : "<null>";
    }

    private static String heldItem(@Nullable InteractionContextSnapshot context) {
        return text(context != null ? context.activeItemId : null);
    }

    private static String text(@Nullable String value) {
        return value != null && !value.isBlank() ? value : "<null>";
    }
}
