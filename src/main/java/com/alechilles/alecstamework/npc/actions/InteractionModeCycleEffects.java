package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeStep;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.ArrayList;

/** Handles mode-cycle state transitions and related messaging. */
final class InteractionModeCycleEffects {
    private static final ModeStep[] DEFAULT_MODE_CYCLE = new ModeStep[] {
            new ModeStep("Hold", null, null),
            new ModeStep("Idle", null, null),
            new ModeStep("Defend", null, null)
    };

    private final ActionTameworkInteract owner;
    private final InteractionPresentationEffects presentationEffects;
    private final InteractionStateEffects stateEffects;

    InteractionModeCycleEffects(ActionTameworkInteract owner,
                                InteractionPresentationEffects presentationEffects,
                                InteractionStateEffects stateEffects) {
        this.owner = owner;
        this.presentationEffects = presentationEffects;
        this.stateEffects = stateEffects;
    }

    // Cycles the NPC's state to the next valid mode step and optionally emits messages.
    boolean applyToggleMode(ModeStep[] cycle,
                            boolean showFloatingText,
                            boolean showUiMessage,
                            Ref<EntityStore> npcRef,
                            Role role,
                            Store<EntityStore> store,
                            Player player) {
        StateSupport stateSupport = NpcSupportAccess.state(role, npcRef, store);
        if (role == null || stateSupport == null) {
            return false;
        }
        String defaultSub = stateEffects.resolveDefaultSubState(role);
        ModeStep[] resolvedCycle = (cycle == null || cycle.length == 0) ? DEFAULT_MODE_CYCLE : cycle;
        ResolvedModeStep[] resolved = resolveValidModeSteps(resolvedCycle, stateSupport, defaultSub);
        if (resolved.length == 0) {
            owner.logDebug("ModeToggle: no valid mode cycle states found for role " + role.getRoleName());
            return false;
        }
        int currentIndex = findCurrentModeIndex(resolved, stateSupport);
        int nextIndex = (currentIndex + 1) % resolved.length;
        if (currentIndex < 0) {
            nextIndex = 0;
        }
        ResolvedModeStep next = resolved[nextIndex];
        stateSupport.setState(npcRef, next.state, resolveSetSubState(next.subState, defaultSub), store);
        if (next.message != null && !next.message.isBlank()) {
            String message = resolveMessage(next.message, player);
            boolean emitted = false;
            if (showFloatingText) {
                emitted |= presentationEffects.showFloatingTextMessage(message, npcRef, store, player);
            }
            if (showUiMessage) {
                emitted |= presentationEffects.applyUiMessage(message, player);
            }
            if (emitted) {
                owner.logDebug("ModeToggle: message=" + message);
            }
        }
        return true;
    }

    // Filters and resolves mode steps against the role's valid states and substates.
    private ResolvedModeStep[] resolveValidModeSteps(ModeStep[] cycle,
                                                     StateSupport stateSupport,
                                                     String defaultSub) {
        if (cycle == null || cycle.length == 0 || stateSupport == null) {
            return new ResolvedModeStep[0];
        }
        if (stateSupport.getStateHelper() == null) {
            return new ResolvedModeStep[0];
        }
        ArrayList<ResolvedModeStep> resolved = new ArrayList<>();
        for (ModeStep step : cycle) {
            if (step == null || step.getState() == null || step.getState().isBlank()) {
                continue;
            }
            String state = step.getState();
            String sub = normalizeSubState(step.getSubState());
            int stateIndex = stateSupport.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                continue;
            }
            String subToValidate = resolveSetSubState(sub, defaultSub);
            if (!subToValidate.isBlank()) {
                int subIndex = stateSupport.getStateHelper().getSubStateIndex(stateIndex, subToValidate);
                if (subIndex == StateSupport.NO_STATE) {
                    continue;
                }
            }
            resolved.add(new ResolvedModeStep(state, sub, step.getMessage()));
        }
        return resolved.toArray(new ResolvedModeStep[0]);
    }

    // Finds the index of the active mode step within the resolved cycle.
    private int findCurrentModeIndex(ResolvedModeStep[] steps, StateSupport stateSupport) {
        if (steps == null || steps.length == 0 || stateSupport == null) {
            return -1;
        }
        String currentStateName = resolveCurrentStateName(stateSupport);
        for (int i = 0; i < steps.length; i++) {
            ResolvedModeStep step = steps[i];
            if (step == null) {
                continue;
            }
            if (step.subState == null || step.subState.isBlank()) {
                if (currentStateName != null && currentStateName.equals(step.state)) {
                    return i;
                }
                continue;
            }
            if (stateSupport.inState(step.state, step.subState)) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeSubState(String subState) {
        if (subState == null) {
            return "";
        }
        String normalized = subState.trim();
        return normalized.isEmpty() ? "" : normalized;
    }

    private String resolveSetSubState(String subState, String defaultSub) {
        String normalized = normalizeSubState(subState);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return normalizeSubState(defaultSub);
    }

    private String resolveMessage(String configuredMessage, Player player) {
        String language = player != null && player.getPlayerRef() != null
                ? player.getPlayerRef().getLanguage()
                : null;
        return LocalizedText.resolveConfigValue(language, configuredMessage, configuredMessage);
    }

    private String resolveCurrentStateName(StateSupport stateSupport) {
        if (stateSupport == null || stateSupport.getStateHelper() == null) {
            return null;
        }
        int stateIndex = stateSupport.getStateIndex();
        if (stateIndex == StateSupport.NO_STATE) {
            return null;
        }
        return stateSupport.getStateHelper().getStateName(stateIndex);
    }

    // Represents a validated mode-cycle step.
    private static final class ResolvedModeStep {
        private final String state;
        private final String subState;
        private final String message;

        private ResolvedModeStep(String state, String subState, String message) {
            this.state = state;
            this.subState = subState;
            this.message = message;
        }
    }
}
