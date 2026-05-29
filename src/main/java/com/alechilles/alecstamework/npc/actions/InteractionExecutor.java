package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
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
            CommandAutoLinkService.autoLinkNewlyTamedNpc(player, npcRef, store);
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
            TameworkInteractEffects.HarvestContainerResult containerResult =
                    effects.applyHarvestContainerTransform(npcRef, store, role, player, ctx);
            if (containerResult == TameworkInteractEffects.HarvestContainerResult.FAILED) {
                return false;
            }
            boolean applied = effects.applyStartHarvest(npcRef, role, store);
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
}
