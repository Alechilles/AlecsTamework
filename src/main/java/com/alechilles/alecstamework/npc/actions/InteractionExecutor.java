package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.items.CommandAutoLinkResult;
import com.alechilles.alecstamework.items.CommandAutoLinkService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
import com.alechilles.alecstamework.output.CompanionOutputService;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.api.internal.HusbandryYieldResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Executes a resolved interaction entry using shared effect handlers. */
final class InteractionExecutor {
    private static final ThreadLocal<Boolean> CHAIN_SUPPRESSED = ThreadLocal.withInitial(() -> false);
    private final TameworkInteractEffects effects;
    private final InteractionFeedHelper feedHelper;

    // Builds an executor using shared effect and feed helpers.
    InteractionExecutor(TameworkInteractEffects effects, InteractionFeedHelper feedHelper) {
        this.effects = effects;
        this.feedHelper = feedHelper;
    }

    // Applies a single interaction entry and any configured custom effects.
    boolean applyInteraction(ActionTameworkInteract.ResolvedInteraction interaction,
                             Ref<EntityStore> npcRef,
                             Role role,
                             InfoProvider infoProvider,
                             Store<EntityStore> store,
                             Player player,
                             InteractionContextSnapshot ctx) {
        if (interaction == null || interaction.entry == null) {
            return false;
        }
        InteractionEntry entry = interaction.entry;
        String interactionConfigId = interaction.configId;
        int interactionIndex = interaction.index;
        boolean harvestInteraction = entry instanceof HarvestInteraction;
        if (entry instanceof CustomInteraction) {
            return effects.applyCustomEffects(
                    interactionConfigId,
                    interactionIndex,
                    entry,
                    entry.getEffects(),
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    harvestInteraction
            );
        }
        if (entry instanceof TameInteraction) {
            TameInteraction tame = (TameInteraction) entry;
            return effects.applyStartTaming(
                    npcRef,
                    store,
                    player,
                    (liveNpcRef, liveStore, livePlayer, acquired) -> {
                        Role liveRole = effects.resolveLiveRole(liveNpcRef, liveStore, role);
                        InteractionContextSnapshot liveContext = effects.refreshContext(livePlayer, liveRole);
                        String targetRoleId = effects.resolveTameRoleId(
                                tame, liveRole, liveContext);
                        boolean tameRoleChanged = effects.applyTameRoleChange(
                                tame, liveNpcRef, liveRole, liveStore, liveContext);
                        if (livePlayer != null) {
                            feedHelper.consumeHeldItem(livePlayer, 1);
                        }
                        String[] appliedRole = {
                                tameRoleChanged ? targetRoleId : null};
                        effects.applyCustomEffects(
                                interactionConfigId,
                                interactionIndex,
                                entry,
                                entry.getEffects(),
                                liveNpcRef,
                                liveRole,
                                infoProvider,
                                liveStore,
                                livePlayer,
                                liveContext,
                                harvestInteraction,
                                roleId -> appliedRole[0] = roleId
                        );
                        String finalRoleId = appliedRole[0];
                        if (finalRoleId == null || finalRoleId.isBlank()) {
                            finalRoleId = resolveRoleId(
                                    liveNpcRef, liveStore, liveRole);
                        }
                        publishTameIfCommitted(
                                UUID.randomUUID(),
                                acquired,
                                livePlayer == null ? null : livePlayer.getUuid(),
                                resolveOwnerId(liveNpcRef, liveStore),
                                TamedStateResolver.isTamed(
                                        liveNpcRef, liveStore),
                                finalRoleId,
                                resolveCompanionId(liveNpcRef, liveStore)
                        );
                        if (livePlayer != null) {
                            CommandAutoLinkResult autoLink = CommandAutoLinkService.autoLinkNewlyTamedNpc(
                                    livePlayer,
                                    liveNpcRef,
                                    liveStore
                            );
                            sendTameAutoLinkFeedback(livePlayer, autoLink);
                        }
                    }
            );
        }
        if (entry instanceof FeedInteraction) {
            FeedInteraction feed = (FeedInteraction) entry;
            double healAmount = feedHelper.resolveFeedHeal(feed, role, ctx);
            UUID operationId = UUID.randomUUID();
            boolean applied = effects.applyFeeding(
                    npcRef, store, healAmount, player, ctx);
            feedHelper.consumeHeldItem(player, 1);
            boolean customApplied = effects.applyCustomEffects(
                    interactionConfigId,
                    interactionIndex,
                    entry,
                    entry.getEffects(),
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    harvestInteraction
            );
            if (applied && isInteractingOwner(npcRef, store, player)) {
                boolean careCredit = ActivityRuntime.tryAcquireCareCredit(npcRef, store);
                AwardResult award = careCredit
                        ? CompanionLevelingService.awardFeedXp(npcRef, store)
                        : null;
                ActivityRuntime.publishFeed(
                        operationId,
                        role == null ? null : role.getRoleName(),
                        resolveOwnerId(npcRef, store),
                        resolveCompanionId(npcRef, store),
                        award,
                        careCredit
                );
            }
            return applied | customApplied;
        }
        if (entry instanceof HarvestInteraction) {
            effects.logHarvestExecution("selected", interactionConfigId, interactionIndex, role, ctx);
            String harvestContext = resolveHarvestContext(role, ctx);
            boolean shearContext = harvestContext != null && harvestContext.equalsIgnoreCase("Shear");
            HusbandryHarvestUseContext.CapturedUse toolUse = shearContext
                    ? HusbandryHarvestUseContext.capture(player, ctx == null ? null : ctx.activeItem)
                    : HusbandryHarvestUseContext.CapturedUse.empty();
            HusbandryOutcomeModifiers authorization = HusbandryYieldResolver.resolveHarvest(
                    npcRef, store, role == null ? null : role.getRoleName(), null, toolUse.tool(),
                    player == null ? null : player.getUuid());
            if (!authorization.toolAuthorized()) {
                effects.logHarvestExecution("tool-denied", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            if (!HusbandryHarvestUseContext.stillMatches(player, toolUse)) {
                effects.logHarvestExecution("tool-swapped", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            HusbandryHarvestUseContext.CapturedUse resolvedToolUse = toolUse
                    .withWearMultiplier(authorization.toolWearMultiplier())
                    .withModifiers(authorization);
            resolvedToolUse = prepareShearOutput(npcRef, store, role, ctx, resolvedToolUse);
            if (!effects.isHarvestCooldownReady(npcRef, role, store, ctx)) {
                effects.logHarvestExecution("cooldown-blocked", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            effects.logHarvestExecution("cooldown-ready", interactionConfigId, interactionIndex, role, ctx);
            UUID operationId = UUID.randomUUID();
            TameworkInteractEffects.HarvestContainerOutcome containerOutcome =
                    effects.applyHarvestContainerTransform(npcRef, store, role, player, ctx, resolvedToolUse);
            if (containerOutcome.result == TameworkInteractEffects.HarvestContainerResult.FAILED) {
                effects.logHarvestExecution("container-failed", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            effects.logHarvestExecution("container-" + containerOutcome.result, interactionConfigId, interactionIndex, role, ctx);
            boolean applied = effects.applyStartHarvest(npcRef, role, store);
            if (!applied) {
                effects.logHarvestExecution("state-blocked", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            HusbandryHarvestUseContext.begin(npcRef, store, resolvedToolUse);
            effects.logHarvestExecution("state-applied", interactionConfigId, interactionIndex, role, ctx);
            if (!containerOutcome.preserveCooldown
                    && !effects.ensureHarvestCooldownAfterState(npcRef, role, store, ctx)) {
                effects.logHarvestExecution("cooldown-ensure-blocked", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            effects.logHarvestExecution(
                    containerOutcome.preserveCooldown ? "cooldown-preserved" : "cooldown-ensured",
                    interactionConfigId,
                    interactionIndex,
                    role,
                    ctx
            );
            TameworkInteractEffects.HarvestCustomEffectsOutcome customOutcome =
                    effects.applyHarvestCustomEffects(
                            interactionConfigId,
                            interactionIndex,
                            entry,
                            entry.getEffects(),
                            npcRef,
                            role,
                            infoProvider,
                            store,
                            player,
                            ctx
                    );
            if (!CHAIN_SUPPRESSED.get() && shearContext && authorization.chainHarvestChance() > 0.0
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                    < authorization.chainHarvestChance()) {
                applyChainShear(npcRef, role, infoProvider, store, player, resolvedToolUse);
            }
            boolean dropActionExpected = hasHarvestDropAction(role, ctx);
            if (isInteractingOwner(npcRef, store, player)
                    && !dropActionExpected
                    && (containerOutcome.result
                    == TameworkInteractEffects.HarvestContainerResult.APPLIED
                    || !customOutcome.itemQuantities().isEmpty())) {
                publishContainerHarvest(
                        operationId,
                        role == null ? null : role.getRoleName(),
                        resolveHarvestContext(role, ctx),
                        resolveOwnerId(npcRef, store),
                        resolveCompanionId(npcRef, store),
                        containerOutcome,
                        false,
                        () -> CompanionLevelingService.awardHarvestXp(npcRef, store),
                        customOutcome.itemQuantities(),
                        resolvedToolUse.tool(),
                        resolvedToolUse.actorId()
                );
            }
            return true | customOutcome.applied();
        }
        if (entry instanceof MountInteraction) {
            effects.logMountExecution("selected", interactionConfigId, interactionIndex, role, ctx);
            boolean applied = effects.applyMount(npcRef, role, infoProvider, store);
            effects.logMountExecution(applied ? "mount-applied" : "mount-blocked", interactionConfigId, interactionIndex, role, ctx);
            return applied
                    | effects.applyCustomEffects(
                    interactionConfigId,
                    interactionIndex,
                    entry,
                    entry.getEffects(),
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    harvestInteraction
            );
        }
        if (entry instanceof ModeCycleInteraction) {
            ModeCycleInteraction cycle = (ModeCycleInteraction) entry;
            boolean applied = effects.applyToggleMode(
                    cycle.getCycle(),
                    cycle.isShowFloatingText(),
                    cycle.isShowUiMessage(),
                    npcRef,
                    role,
                    store,
                    player
            );
            return applied
                    | effects.applyCustomEffects(
                    interactionConfigId,
                    interactionIndex,
                    entry,
                    entry.getEffects(),
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    harvestInteraction
            );
        }
        if (entry instanceof BreedInteraction) {
            BreedInteraction breeding = (BreedInteraction) entry;
            BreedingInteractionOutcome outcome = effects.applyStartBreeding(
                    breeding, npcRef, role, store, player);
            if (!outcome.accepted()) {
                return false;
            }
            return effects.applyCustomEffects(
                    interactionConfigId,
                    interactionIndex,
                    entry,
                    entry.getEffects(),
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    harvestInteraction
            ) | outcome.accepted();
        }
        return false;
    }

    static void publishTameIfCommitted(
            UUID operationId,
            boolean acquired,
            UUID acquiringOwnerId,
            UUID finalOwnerId,
            boolean finalTamed,
            String finalRoleId,
            UUID companionId
    ) {
        if (!acquired || !finalTamed || acquiringOwnerId == null
                || !acquiringOwnerId.equals(finalOwnerId)) {
            return;
        }
        ActivityRuntime.publishTame(
                operationId, finalRoleId, finalOwnerId, companionId);
    }

    private static String resolveRoleId(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            Role fallback
    ) {
        NPCEntity npc =
                NPCEntity.getComponentType() == null
                        ? null
                        : store.getComponent(
                                npcRef,
                                NPCEntity.getComponentType());
        String roleId = npc == null ? null : npc.getRoleName();
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return fallback == null ? null : fallback.getRoleName();
    }

    void publishContainerHarvest(
            UUID operationId,
            String roleId,
            String harvestContext,
            UUID ownerId,
            UUID companionId,
            TameworkInteractEffects.HarvestContainerOutcome outcome,
            boolean dropActionExpected,
            Supplier<AwardResult> awardSupplier
    ) {
        publishContainerHarvest(
                operationId,
                roleId,
                harvestContext,
                ownerId,
                companionId,
                outcome,
                dropActionExpected,
                awardSupplier,
                Map.of(),
                null,
                null
        );
    }

    void publishContainerHarvest(
            UUID operationId,
            String roleId,
            String harvestContext,
            UUID ownerId,
            UUID companionId,
            TameworkInteractEffects.HarvestContainerOutcome outcome,
            boolean dropActionExpected,
            Supplier<AwardResult> awardSupplier,
            Map<String, Integer> looseItemQuantities
    ) {
        publishContainerHarvest(operationId, roleId, harvestContext, ownerId, companionId,
                outcome, dropActionExpected, awardSupplier, looseItemQuantities, null, null);
    }

    void publishContainerHarvest(
            UUID operationId,
            String roleId,
            String harvestContext,
            UUID ownerId,
            UUID companionId,
            TameworkInteractEffects.HarvestContainerOutcome outcome,
            boolean dropActionExpected,
            Supplier<AwardResult> awardSupplier,
            Map<String, Integer> looseItemQuantities,
            com.alechilles.alecstamework.api.HusbandryToolContext tool,
            UUID actorId
    ) {
        if (outcome == null || dropActionExpected) {
            return;
        }
        LinkedHashMap<String, Integer> itemQuantities = new LinkedHashMap<>();
        if (outcome.result == TameworkInteractEffects.HarvestContainerResult.APPLIED) {
            mergeQuantities(itemQuantities, outcome.itemQuantities);
        }
        mergeQuantities(itemQuantities, looseItemQuantities);
        if (itemQuantities.isEmpty()) {
            return;
        }
        AwardResult award = awardSupplier == null ? null : awardSupplier.get();
        ActivityRuntime.publishHarvest(
                operationId,
                roleId,
                harvestContext,
                ownerId,
                companionId,
                CompanionOutputService.finalizeQuantities(itemQuantities).itemQuantities(),
                award,
                tool,
                actorId
        );
    }

    private static void mergeQuantities(Map<String, Integer> target,
                                        Map<String, Integer> additions) {
        if (target == null || additions == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : additions.entrySet()) {
            String itemId = entry.getKey();
            Integer quantity = entry.getValue();
            if (itemId == null || itemId.isBlank() || quantity == null || quantity <= 0) {
                continue;
            }
            target.merge(itemId, quantity, (left, right) ->
                    (int) Math.min(Integer.MAX_VALUE, (long) left + right));
        }
    }

    private boolean hasHarvestDropAction(
            Role role,
            InteractionContextSnapshot ctx
    ) {
        String dropList = new InteractionParamResolver(null, null, null)
                .getStringParam(role, ctx, "HarvestDropList");
        return dropList != null && !dropList.isBlank();
    }

    private HusbandryHarvestUseContext.CapturedUse prepareShearOutput(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            Role role,
            InteractionContextSnapshot ctx,
            HusbandryHarvestUseContext.CapturedUse use
    ) {
        if (use == null || !use.tool().present()) {
            return use;
        }
        String dropList = new InteractionParamResolver(null, null, null)
                .getStringParam(role, ctx, "HarvestDropList");
        ItemModule itemModule = ItemModule.get();
        if (dropList == null || dropList.isBlank() || itemModule == null || !itemModule.isEnabled()) {
            return use;
        }
        List<ItemStack> baseDrops = itemModule.getRandomItemDrops(dropList);
        CompanionOutputService.FinalizedOutput output = CompanionOutputService.finalizeExpectedQuantity(
                baseDrops,
                stack -> {
                    HusbandryOutcomeModifiers productModifiers = HusbandryYieldResolver.resolveHarvest(
                            npcRef, store, role == null ? null : role.getRoleName(), stack.getItemId(),
                            use.tool(), use.actorId());
                    return productModifiers.toolAuthorized()
                            ? HusbandryYieldResolver.harvestYieldBonus(
                                    npcRef, store, stack.getItemId(), productModifiers,
                                    CompanionHarvestBonusService.expectedDropDuplicateYieldBonus(npcRef, store, role))
                            : -1.0;
                },
                java.util.concurrent.ThreadLocalRandom.current()::nextDouble);
        output = HusbandryYieldResolver.applyHarvestConversions(
                output, npcRef, store, role == null ? null : role.getRoleName(),
                use.tool(), use.actorId(), java.util.concurrent.ThreadLocalRandom.current()::nextDouble);
        return use.withPreparedOutput(dropList, output);
    }

    /** Starts at most one nearby owned ready shear using its own role state and cooldown. */
    private void applyChainShear(
            Ref<EntityStore> source,
            Role sourceRole,
            InfoProvider infoProvider,
            Store<EntityStore> store,
            Player player,
            HusbandryHarvestUseContext.CapturedUse sourceUse
    ) {
        if (source == null || !source.isValid() || store == null || player == null
                || !HusbandryHarvestUseContext.stillMatches(player, sourceUse)) {
            return;
        }
        com.hypixel.hytale.server.npc.role.support.StateSupport sourceState =
                NpcSupportAccess.state(sourceRole, source, store);
        Ref<EntityStore> playerRef = sourceState == null
                ? null : sourceState.getInteractionIterationTarget();
        TransformComponent sourceTransform = store.getComponent(
                source, TransformComponent.getComponentType());
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (sourceTransform == null || ownerType == null || playerRef == null || !playerRef.isValid()) {
            return;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return;
        }
        List<ChainCandidate> candidates = new ArrayList<>();
        double maxDistanceSquared = 36.0;
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk,
                                         CommandBuffer<EntityStore> ignored) -> {
            for (int index = 0; index < chunk.size(); index++) {
                NPCEntity candidateNpc = chunk.getComponent(index, NPCEntity.getComponentType());
                if (candidateNpc == null || candidateNpc.getUuid() == null) {
                    continue;
                }
                Ref<EntityStore> candidate = chunk.getReferenceTo(index);
                if (candidate == null || !candidate.isValid() || candidate.equals(source)) {
                    continue;
                }
                TameworkOwnerComponent owner = chunk.getComponent(index, ownerType);
                if (owner == null || !playerId.equals(owner.getOwnerId())
                        || !TamedStateResolver.isTamed(candidate, store)) {
                    continue;
                }
                TransformComponent transform = chunk.getComponent(
                        index, TransformComponent.getComponentType());
                if (transform == null) {
                    continue;
                }
                double distanceSquared = transform.getPosition().distanceSquared(
                        sourceTransform.getPosition());
                if (distanceSquared <= 0.000001 || distanceSquared > maxDistanceSquared) {
                    continue;
                }
                Role candidateRole = candidateNpc.getRole();
                if (candidateRole == null) {
                    continue;
                }
                candidates.add(new ChainCandidate(candidate, candidateRole, distanceSquared));
            }
        });
        candidates.sort(java.util.Comparator.comparingDouble(candidate -> candidate.distanceSquared));
        for (ChainCandidate candidate : candidates) {
            if (!HusbandryHarvestUseContext.stillMatches(player, sourceUse)) {
                return;
            }
            if (effects.executeNeutralChainHarvest(
                    candidate.ref, candidate.role, infoProvider, store, playerRef, player)) {
                return;
            }
        }
    }

    static boolean withChainSuppressed(BooleanSupplier action) {
        boolean previous = CHAIN_SUPPRESSED.get();
        CHAIN_SUPPRESSED.set(true);
        try {
            return action != null && action.getAsBoolean();
        } finally {
            CHAIN_SUPPRESSED.set(previous);
        }
    }

    private record ChainCandidate(
            Ref<EntityStore> ref,
            Role role,
            double distanceSquared
    ) {
    }

    private boolean isInteractingOwner(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            Player player
    ) {
        UUID ownerId = resolveOwnerId(npcRef, store);
        return ownerId != null
                && player != null
                && Objects.equals(ownerId, player.getUuid());
    }

    private UUID resolveOwnerId(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        var ownerType = com.alechilles.alecstamework.npc.components
                .TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return null;
        }
        var owner = store.getComponent(npcRef, ownerType);
        return owner == null ? null : owner.getOwnerId();
    }

    private UUID resolveCompanionId(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        var uuidType = com.hypixel.hytale.server.core.entity.UUIDComponent
                .getComponentType();
        if (uuidType != null) {
            var uuid = store.getComponent(npcRef, uuidType);
            if (uuid != null && uuid.getUuid() != null) {
                return uuid.getUuid();
            }
        }
        var npc = store.getComponent(
                npcRef,
                com.hypixel.hytale.server.npc.entities.NPCEntity
                        .getComponentType()
        );
        return npc == null ? null : npc.getUuid();
    }

    private String resolveHarvestContext(
            Role role,
            InteractionContextSnapshot ctx
    ) {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        String paramName = global == null
                ? "HarvestInteractionContext"
                : global.getHarvestContextParam();
        return new InteractionParamResolver(null, null, null)
                .getStringParam(role, ctx, paramName);
    }

    private void sendTameAutoLinkFeedback(Player player, CommandAutoLinkResult result) {
        if (player == null || result == null) {
            return;
        }
        InteractionUiMessageService ui = new InteractionUiMessageService();
        if (result.status() == CommandAutoLinkResult.Status.LINKED) {
            ui.showSuccessKey(
                    player,
                    "tamework.ui.notifications.tame.autoLink.linked",
                    safeCompanion(result.animalDisplayName()),
                    safeCommandItem(result.commandItemDisplayName())
            );
            return;
        }
        if (result.status() == CommandAutoLinkResult.Status.NO_APPLICABLE_TOOL) {
            ui.showWarningKey(
                    player,
                    "tamework.ui.notifications.tame.autoLink.noTool",
                    safeCompanion(result.animalDisplayName()),
                    safeCommandItem(result.commandItemDisplayName()),
                    safeCraftingStation(result.craftingStationDisplayName())
            );
        }
    }

    private String safeCompanion(String value) {
        return value == null || value.isBlank() ? "Companion" : value;
    }

    private String safeCommandItem(String value) {
        return value == null || value.isBlank() ? "command item" : value;
    }

    private String safeCraftingStation(String value) {
        return value == null || value.isBlank() ? "crafting bench" : value;
    }
}
