package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
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
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/**
 * Updates the NPC interaction prompt based on the first matching Tamework interaction entry.
 */
public final class ActionTameworkInteractPrompt extends ActionTameworkInteract {
    private static final String HINT_GENERIC = "server.interactionHints.generic";
    private static final String HINT_HARVEST = "server.interactionHints.harvest";
    private static final String HINT_MOUNT = "server.interactionHints.mount";
    private static final String HINT_TAME = "server.interactionHints.tame";
    private static final String HINT_FEED = "server.interactionHints.feed";
    private static final String HINT_BREED = "server.interactionHints.breed";
    private static final String HINT_MODE_CYCLE = "server.interactionHints.modeCycle";
    private static final String HINT_CUSTOM = "server.interactionHints.custom";

    public ActionTameworkInteractPrompt(BuilderActionTameworkInteractPrompt builder, BuilderSupport support) {
        super(builder, support);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || role == null || role.getStateSupport() == null) {
            return false;
        }
        Ref<EntityStore> interactionTarget = resolveInteractionTarget(role, infoProvider);
        if (interactionTarget == null || !interactionTarget.isValid() || store == null) {
            return false;
        }
        Player player = store.getComponent(interactionTarget, Player.getComponentType());
        if (player == null) {
            return false;
        }

        InteractionContextSnapshot ctx = buildContextSnapshot(player, role);
        TwInteractionConfig config = resolveConfig(role, ctx);
        ActionTameworkInteract.ResolvedInteraction resolved = null;
        if (config != null && config.isEnabled()) {
            resolved = selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
        }

        PromptState prompt = resolvePromptState(resolved);
        role.getStateSupport().setInteractable(npcRef, interactionTarget, true, prompt.hint, prompt.showPrompt, store);
        return true;
    }

    // Determines the prompt state (visibility + hint key) for the selected entry.
    private PromptState resolvePromptState(ActionTameworkInteract.ResolvedInteraction resolved) {
        if (resolved == null || resolved.blockedByCooldown || resolved.entry == null) {
            return PromptState.hidden();
        }
        InteractionEntry entry = resolved.entry;
        Boolean showOverride = entry.getShowPrompt();
        boolean showPrompt = showOverride == null || showOverride;
        if (!showPrompt) {
            return PromptState.hidden();
        }
        String hint = entry.getPromptHint();
        if (hint == null || hint.isBlank()) {
            hint = resolveDefaultHint(entry);
        }
        return new PromptState(hint, true);
    }

    // Selects a default hint key by interaction type.
    private String resolveDefaultHint(InteractionEntry entry) {
        if (entry instanceof HarvestInteraction) {
            return HINT_HARVEST;
        }
        if (entry instanceof MountInteraction) {
            return HINT_MOUNT;
        }
        if (entry instanceof TameInteraction) {
            return HINT_TAME;
        }
        if (entry instanceof FeedInteraction) {
            return HINT_FEED;
        }
        if (entry instanceof BreedInteraction) {
            return HINT_BREED;
        }
        if (entry instanceof ModeCycleInteraction) {
            return HINT_MODE_CYCLE;
        }
        if (entry instanceof CustomInteraction) {
            return HINT_CUSTOM;
        }
        return HINT_GENERIC;
    }

    // Simple value object for prompt hint/visibility.
    private static final class PromptState {
        final String hint;
        final boolean showPrompt;

        private PromptState(String hint, boolean showPrompt) {
            this.hint = hint;
            this.showPrompt = showPrompt;
        }

        static PromptState hidden() {
            return new PromptState(null, false);
        }
    }
}
