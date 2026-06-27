package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.items.CommandAutoLinkResult;
import com.alechilles.alecstamework.items.CommandAutoLinkService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

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
            boolean applied = effects.applyStartTaming(npcRef, store, player);
            if (!applied) {
                return false;
            }
            applied |= effects.applyTameRoleChange(tame, npcRef, role, store, ctx);
            feedHelper.consumeHeldItem(player, 1);
            applied |= effects.applyCustomEffects(
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
            CommandAutoLinkResult autoLink = CommandAutoLinkService.autoLinkNewlyTamedNpc(player, npcRef, store);
            sendTameAutoLinkFeedback(player, autoLink);
            return applied;
        }
        if (entry instanceof FeedInteraction) {
            FeedInteraction feed = (FeedInteraction) entry;
            double healAmount = feedHelper.resolveFeedHeal(feed, role, ctx);
            boolean applied = effects.applyFeeding(npcRef, store, healAmount, player, ctx);
            feedHelper.consumeHeldItem(player, 1);
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
        if (entry instanceof HarvestInteraction) {
            effects.logHarvestExecution("selected", interactionConfigId, interactionIndex, role, ctx);
            if (!effects.isHarvestCooldownReady(npcRef, role, store, ctx)) {
                effects.logHarvestExecution("cooldown-blocked", interactionConfigId, interactionIndex, role, ctx);
                return false;
            }
            effects.logHarvestExecution("cooldown-ready", interactionConfigId, interactionIndex, role, ctx);
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
            return true
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
        if (entry instanceof MountInteraction) {
            boolean applied = effects.applyMount(npcRef, role, infoProvider, store);
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
