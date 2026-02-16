package com.alechilles.alecstamework.npc.actions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import com.alechilles.alecstamework.Tamework;
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

    private final Map<UUID, PromptState> lastPrompts = new HashMap<>();
    private final Map<UUID, Long> lastDebugMs = new HashMap<>();

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
            resolved = selectInteractionForPrompt(config, npcRef, role, infoProvider, store, player, ctx);
        }
        if (resolved != null && resolved.entry instanceof HarvestInteraction) {
            HarvestInteraction harvest = (HarvestInteraction) resolved.entry;
            boolean requireAlarm = harvest.getRequireHarvestAlarmReady() == null || harvest.getRequireHarvestAlarmReady();
            // Prompt should only show when the harvest alarm is ready (unset or passed).
            if (requireAlarm && !isHarvestAlarmReady(npcRef, store)) {
                resolved = null;
            }
        }

        PromptState prompt = resolvePromptState(resolved);
        UUID playerId = ctx != null ? ctx.playerId : null;
        if (playerId == null) {
            return false;
        }
        PromptState previous = lastPrompts.get(playerId);
        boolean changed = !prompt.equals(previous);
        boolean interactable = !prompt.isHidden();
        maybeLogPromptDebug(resolved, config, npcRef, role, store, ctx, prompt);
        if (changed) {
            // Force a refresh when the prompt changes so the hint updates on the client.
            role.getStateSupport().setInteractable(npcRef, interactionTarget, false, null, false, store);
        }
        role.getStateSupport().setInteractable(
                npcRef,
                interactionTarget,
                interactable,
                prompt.hint,
                prompt.showPrompt,
                store
        );
        lastPrompts.put(playerId, prompt);
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

        boolean isHidden() {
            return !showPrompt || hint == null || hint.isBlank();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PromptState)) {
                return false;
            }
            PromptState that = (PromptState) other;
            if (this.showPrompt != that.showPrompt) {
                return false;
            }
            if (this.hint == null) {
                return that.hint == null;
            }
            return this.hint.equals(that.hint);
        }

        @Override
        public int hashCode() {
            int result = Boolean.hashCode(showPrompt);
            result = 31 * result + (hint == null ? 0 : hint.hashCode());
            return result;
        }

        static PromptState hidden() {
            return new PromptState(null, false);
        }
    }

    private void maybeLogPromptDebug(ActionTameworkInteract.ResolvedInteraction resolved,
                                     TwInteractionConfig config,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     Store<EntityStore> store,
                                     InteractionContextSnapshot ctx,
                                     PromptState prompt) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugPromptEnabled() || instance.getLogger() == null) {
            return;
        }
        UUID playerId = ctx != null ? ctx.playerId : null;
        if (playerId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastDebugMs.get(playerId);
        if (last != null && now - last < 1000) {
            return;
        }
        lastDebugMs.put(playerId, now);

        String roleName = role != null ? role.getRoleName() : "<null>";
        String configId = config != null ? config.getId() : "<null>";
        String entryType = resolved != null && resolved.entry != null
                ? resolved.entry.getClass().getSimpleName()
                : "<none>";
        boolean showPrompt = !prompt.isHidden();
        boolean alarmReady = isHarvestAlarmReady(npcRef, store);
        boolean alarmActive = isHarvestAlarmActive(npcRef, store);
        boolean alarmPassed = isHarvestAlarmPassed(npcRef, store);
        boolean alarmUnset = isHarvestAlarmUnset(npcRef, store);
        boolean requireAlarm = false;
        boolean requireContext = false;
        if (resolved != null && resolved.entry instanceof HarvestInteraction) {
            HarvestInteraction harvest = (HarvestInteraction) resolved.entry;
            requireAlarm = harvest.getRequireHarvestAlarmReady() == null || harvest.getRequireHarvestAlarmReady();
            requireContext = harvest.getRequireHarvestInteractionContext() == null || harvest.getRequireHarvestInteractionContext();
        }

        instance.getLogger().at(Level.INFO).log(
                "TameworkPrompt debug: role=%s config=%s entry=%s show=%s alarmName=%s " +
                        "ready=%s active=%s passed=%s unset=%s requireAlarm=%s requireContext=%s",
                roleName,
                configId,
                entryType,
                showPrompt,
                getHarvestAlarmName(),
                alarmReady,
                alarmActive,
                alarmPassed,
                alarmUnset,
                requireAlarm,
                requireContext
        );
    }
}
