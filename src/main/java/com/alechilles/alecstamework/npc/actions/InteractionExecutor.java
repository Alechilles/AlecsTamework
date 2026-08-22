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
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService.AwardResult;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Objects;
import java.util.UUID;

/** Executes a resolved interaction entry using shared effect handlers. */
final class InteractionExecutor {
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
                    (liveNpcRef, liveStore, livePlayer) -> {
                        Role liveRole = effects.resolveLiveRole(liveNpcRef, liveStore, role);
                        InteractionContextSnapshot liveContext = effects.refreshContext(livePlayer, liveRole);
                        effects.applyTameRoleChange(tame, liveNpcRef, liveRole, liveStore, liveContext);
                        if (livePlayer != null) {
                            feedHelper.consumeHeldItem(livePlayer, 1);
                        }
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
                                harvestInteraction
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
            if (!effects.isHarvestCooldownReady(npcRef, role, store, ctx)) {
                effects.logHarvestExecution("cooldown-blocked", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            effects.logHarvestExecution("cooldown-ready", interactionConfigId, interactionIndex, role, ctx);
            UUID operationId = UUID.randomUUID();
            TameworkInteractEffects.HarvestContainerOutcome containerOutcome =
                    effects.applyHarvestContainerTransform(npcRef, store, role, player, ctx);
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
            if (containerOutcome.result == TameworkInteractEffects.HarvestContainerResult.APPLIED
                    && isInteractingOwner(npcRef, store, player)) {
                AwardResult award = CompanionLevelingService.awardHarvestXp(npcRef, store);
                publishContainerHarvest(
                        operationId,
                        role == null ? null : role.getRoleName(),
                        resolveHarvestContext(role, ctx),
                        resolveOwnerId(npcRef, store),
                        resolveCompanionId(npcRef, store),
                        containerOutcome,
                        award
                );
            }
            return true | customApplied;
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
            boolean applied = effects.applyStartBreeding(breeding, npcRef, role, store, player);
            if (!applied) {
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
            ) | applied;
        }
        return false;
    }

    void publishContainerHarvest(
            UUID operationId,
            String roleId,
            String harvestContext,
            UUID ownerId,
            UUID companionId,
            TameworkInteractEffects.HarvestContainerOutcome outcome,
            AwardResult award
    ) {
        if (outcome == null
                || outcome.result != TameworkInteractEffects.HarvestContainerResult.APPLIED) {
            return;
        }
        ActivityRuntime.publishHarvest(
                operationId,
                roleId,
                harvestContext,
                ownerId,
                companionId,
                outcome.itemQuantities,
                award
        );
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
