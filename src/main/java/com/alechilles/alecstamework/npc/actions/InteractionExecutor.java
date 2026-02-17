package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
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
    boolean applyInteraction(InteractionEntry entry,
                             Ref<EntityStore> npcRef,
                             Role role,
                             InfoProvider infoProvider,
                             Store<EntityStore> store,
                             Player player,
                             InteractionContextSnapshot ctx) {
        if (entry instanceof CustomInteraction) {
            return effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
        }
        if (entry instanceof TameInteraction) {
            TameInteraction tame = (TameInteraction) entry;
            boolean applied = effects.applyStartTaming(npcRef, store, player);
            applied |= effects.applyTameRoleChange(tame, npcRef, role, store, ctx);
            feedHelper.consumeHeldItem(player, 1);
            applied |= effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
            return applied;
        }
        if (entry instanceof FeedInteraction) {
            FeedInteraction feed = (FeedInteraction) entry;
            double healAmount = feedHelper.resolveFeedHeal(feed, role, ctx);
            boolean applied = effects.applyFeeding(npcRef, store, healAmount, player);
            feedHelper.consumeHeldItem(player, 1);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
        }
        if (entry instanceof HarvestInteraction) {
            boolean applied = effects.applyStartHarvest(npcRef, role, store);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
        }
        if (entry instanceof MountInteraction) {
            boolean applied = effects.applyMount(npcRef, role, infoProvider, store);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
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
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
        }
        if (entry instanceof BreedInteraction) {
            boolean applied = effects.applyStartBreeding();
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player, ctx);
        }
        return false;
    }
}
