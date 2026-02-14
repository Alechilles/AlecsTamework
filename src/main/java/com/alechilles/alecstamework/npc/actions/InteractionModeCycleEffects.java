package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeStep;
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
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String defaultSub = stateEffects.resolveDefaultSubState(role);
        ModeStep[] resolvedCycle = (cycle == null || cycle.length == 0) ? DEFAULT_MODE_CYCLE : cycle;
        ResolvedModeStep[] resolved = resolveValidModeSteps(resolvedCycle, role, defaultSub);
        if (resolved.length == 0) {
            owner.logDebug("ModeToggle: no valid mode cycle states found for role " + role.getRoleName());
            return false;
        }
        int currentIndex = findCurrentModeIndex(resolved, role, defaultSub);
        int nextIndex = (currentIndex + 1) % resolved.length;
        if (currentIndex < 0) {
            nextIndex = 0;
        }
        ResolvedModeStep next = resolved[nextIndex];
        role.getStateSupport().setState(npcRef, next.state, next.subState, store);
        if (next.message != null && !next.message.isBlank()) {
            boolean emitted = false;
            if (showFloatingText) {
                emitted |= presentationEffects.showFloatingTextMessage(next.message, npcRef, store, player);
            }
            if (showUiMessage) {
                emitted |= presentationEffects.applyUiMessage(next.message, player);
            }
            if (emitted) {
                owner.logDebug("ModeToggle: message=" + next.message);
            }
        }
        return true;
    }

    // Filters and resolves mode steps against the role's valid states and substates.
    private ResolvedModeStep[] resolveValidModeSteps(ModeStep[] cycle, Role role, String defaultSub) {
        if (cycle == null || cycle.length == 0 || role == null || role.getStateSupport() == null) {
            return new ResolvedModeStep[0];
        }
        StateSupport stateSupport = role.getStateSupport();
        if (stateSupport.getStateHelper() == null) {
            return new ResolvedModeStep[0];
        }
        ArrayList<ResolvedModeStep> resolved = new ArrayList<>();
        for (ModeStep step : cycle) {
            if (step == null || step.getState() == null || step.getState().isBlank()) {
                continue;
            }
            String state = step.getState();
            String sub = step.getSubState();
            if (sub == null || sub.isBlank()) {
                sub = defaultSub;
            }
            int stateIndex = stateSupport.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                continue;
            }
            String resolvedSub = sub == null ? "" : sub;
            if (!resolvedSub.isBlank()) {
                int subIndex = stateSupport.getStateHelper().getSubStateIndex(stateIndex, resolvedSub);
                if (subIndex == StateSupport.NO_STATE) {
                    continue;
                }
            }
            resolved.add(new ResolvedModeStep(state, resolvedSub, step.getMessage()));
        }
        return resolved.toArray(new ResolvedModeStep[0]);
    }

    // Finds the index of the active mode step within the resolved cycle.
    private int findCurrentModeIndex(ResolvedModeStep[] steps, Role role, String defaultSub) {
        if (steps == null || steps.length == 0 || role == null || role.getStateSupport() == null) {
            return -1;
        }
        for (int i = 0; i < steps.length; i++) {
            ResolvedModeStep step = steps[i];
            if (step == null) {
                continue;
            }
            String sub = step.subState;
            if (sub == null || sub.isBlank()) {
                sub = defaultSub;
            }
            if (role.getStateSupport().inState(step.state, sub)) {
                return i;
            }
        }
        return -1;
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
