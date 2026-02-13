package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Locale;

// Helper routines for matching interaction context, movement state, and alarms.
final class InteractionMatchHelpers {
    private final ActionTameworkInteract owner;
    private final InteractionParamAccess paramAccess;
    private final InteractionAlarmHelper alarmHelper;

    InteractionMatchHelpers(ActionTameworkInteract owner,
                            InteractionParamAccess paramAccess,
                            InteractionAlarmHelper alarmHelper) {
        this.owner = owner;
        this.paramAccess = paramAccess;
        this.alarmHelper = alarmHelper;
    }

    // Checks whether the required interaction context is present.
    boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                      Role role,
                                      InfoProvider infoProvider,
                                      InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return false;
        }
        String context = resolveInteractionContextParam(requirement, role, ctx);
        if (context == null || context.isBlank()) {
            context = requirement.getContext();
        }
        return matchesInteractionContext(context, role, infoProvider, false);
    }

    // Checks whether a context string matches the interaction context for the target player.
    boolean matchesInteractionContext(String context,
                                      Role role,
                                      InfoProvider infoProvider,
                                      boolean allowBlank) {
        if (context == null || context.isBlank()) {
            return allowBlank;
        }
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        Ref<EntityStore> playerRef = owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        return role.getStateSupport().hasContextualInteraction(playerRef, context);
    }

    // Checks the movement state requirement against the interacting player.
    boolean matchesMovementState(MovementStateRequirement requirement,
                                 Role role,
                                 InfoProvider infoProvider,
                                 Store<EntityStore> store) {
        if (requirement == null || requirement.getState() == null || requirement.getState().isBlank()) {
            return false;
        }
        MovementStates states = statesForPlayer(role, infoProvider, store);
        return matchesMovementState(states, requirement.getState());
    }

    // Returns true when the player is crouching.
    boolean isPlayerCrouching(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        return matchesMovementState(statesForPlayer(role, infoProvider, store), "Crouching");
    }

    // Matches a state name against the movement state snapshot.
    boolean matchesMovementState(MovementStates states, String state) {
        if (states == null || state == null) {
            return false;
        }
        String normalized = state.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "crouching":
                return states.crouching || states.forcedCrouching;
            case "walking":
                return states.walking;
            case "running":
                return states.running;
            case "sprinting":
                return states.sprinting;
            case "idle":
                return states.idle;
            case "mounting":
                return states.mounting;
            case "sleeping":
                return states.sleeping;
            default:
                return false;
        }
    }

    // Checks an alarm requirement against the NPC alarm state.
    boolean matchesAlarmState(AlarmRequirement requirement,
                              Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              Role role,
                              InteractionContextSnapshot ctx) {
        String alarmName = resolveAlarmName(requirement, role, ctx);
        if (alarmName == null || alarmName.isBlank()) {
            return false;
        }
        String state = requirement.getState() != null ? requirement.getState().trim().toLowerCase(Locale.ROOT) : "";
        return alarmHelper.matchesAlarmState(npcRef, store, alarmName, state);
    }

    // Resolves a context parameter value from role params.
    private String resolveInteractionContextParam(InteractionContextRequirement requirement,
                                                  Role role,
                                                  InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return null;
        }
        String paramName = requirement.getContextParam();
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        String context = paramAccess.getRoleStringParam(role, ctx, paramName);
        return context != null && !context.isBlank() ? context : null;
    }

    // Resolves the alarm name, supporting param overrides.
    private String resolveAlarmName(AlarmRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return null;
        }
        String paramName = requirement.getAlarmParam();
        if (paramName != null && !paramName.isBlank()) {
            String resolved = paramAccess.getRoleStringParam(role, ctx, paramName);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        String name = requirement.getName();
        return name != null && !name.isBlank() ? name : null;
    }

    // Retrieves movement states for the interacting player.
    private MovementStates statesForPlayer(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        Ref<EntityStore> playerRef = owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        MovementStatesComponent component = store.getComponent(playerRef, MovementStatesComponent.getComponentType());
        return component != null ? component.getMovementStates() : null;
    }

}
