package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.NpcHealthPercentRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamOperator;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.interactions.ContextualUseNPCInteraction;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Helper routines for matching interaction context, movement state, and alarms. */
final class InteractionMatchHelpers {
    private final ActionTameworkInteract owner;
    private final InteractionParamAccess paramAccess;
    private final InteractionAlarmHelper alarmHelper;
    private final Map<String, Set<String>> contextCache = new HashMap<>();
    private Field contextualUseNpcContextField;

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

    // Checks whether the required interaction context is present for prompts.
    boolean matchesInteractionContextForPrompt(InteractionContextRequirement requirement,
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
        return matchesInteractionContextForPrompt(context, role, infoProvider, ctx, false);
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

    // Checks whether a context string matches for prompt display.
    boolean matchesInteractionContextForPrompt(String context,
                                               Role role,
                                               InfoProvider infoProvider,
                                               InteractionContextSnapshot ctx,
                                               boolean allowBlank) {
        if (context == null || context.isBlank()) {
            return allowBlank;
        }
        if (ctx != null) {
            Boolean cachedMatch = ctx.promptContextMatches.get(context);
            if (cachedMatch != null) {
                return cachedMatch;
            }
        }
        boolean matched = matchesRoleInteractionContext(context, role, infoProvider, ctx)
                || matchesHeldItemInteractionContext(context, ctx);
        if (ctx != null) {
            ctx.promptContextMatches.put(context, matched);
        }
        return matched;
    }

    // Checks whether the held item advertises the given interaction context.
    private boolean matchesHeldItemInteractionContext(String context, InteractionContextSnapshot ctx) {
        if (ctx == null || ctx.activeItem == null || context == null || context.isBlank()) {
            return false;
        }
        String itemId = ctx.activeItemId;
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Set<String> contexts = contextCache.computeIfAbsent(itemId, id -> resolveItemContexts(ctx.activeItem));
        if (contexts.isEmpty()) {
            return false;
        }
        for (String entry : contexts) {
            if (entry != null && entry.equalsIgnoreCase(context)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveItemContexts(ItemStack stack) {
        Set<String> contexts = new HashSet<>();
        if (stack == null || stack.isEmpty()) {
            return contexts;
        }
        Item item = stack.getItem();
        if (item == null) {
            return contexts;
        }
        Map<InteractionType, String> rootInteractions = item.getInteractions();
        if (rootInteractions == null || rootInteractions.isEmpty()) {
            return contexts;
        }
        for (String rootId : rootInteractions.values()) {
            if (rootId == null || rootId.isBlank()) {
                continue;
            }
            RootInteraction root = RootInteraction.getRootInteractionOrUnknown(rootId);
            if (root == null) {
                continue;
            }
            String[] interactionIds = root.getInteractionIds();
            if (interactionIds == null || interactionIds.length == 0) {
                continue;
            }
            for (String interactionId : interactionIds) {
                if (interactionId == null || interactionId.isBlank()) {
                    continue;
                }
                Interaction interaction = Interaction.getInteractionOrUnknown(interactionId);
                if (interaction instanceof ContextualUseNPCInteraction) {
                    String context = readContext((ContextualUseNPCInteraction) interaction);
                    if (context != null && !context.isBlank()) {
                        contexts.add(context);
                    }
                }
            }
        }
        return contexts;
    }

    private String readContext(ContextualUseNPCInteraction interaction) {
        if (interaction == null) {
            return null;
        }
        try {
            if (contextualUseNpcContextField == null) {
                contextualUseNpcContextField = ContextualUseNPCInteraction.class.getDeclaredField("context");
                contextualUseNpcContextField.setAccessible(true);
            }
            Object value = contextualUseNpcContextField.get(interaction);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    // Checks the movement state requirement against the interacting player.
    boolean matchesMovementState(MovementStateRequirement requirement,
                                 Role role,
                                 InfoProvider infoProvider,
                                 Store<EntityStore> store,
                                 InteractionContextSnapshot ctx) {
        if (requirement == null || requirement.getState() == null || requirement.getState().isBlank()) {
            return false;
        }
        MovementStates states = statesForPlayer(role, infoProvider, store, ctx);
        return matchesMovementState(states, requirement.getState());
    }

    // Returns true when the player is crouching.
    boolean isPlayerCrouching(Role role,
                              InfoProvider infoProvider,
                              Store<EntityStore> store,
                              InteractionContextSnapshot ctx) {
        return matchesMovementState(statesForPlayer(role, infoProvider, store, ctx), "Crouching");
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
        return alarmHelper.matchesAlarmState(npcRef, store, alarmName, state, ctx);
    }

    // Checks an NPC health-percent requirement against the target NPC.
    boolean matchesNpcHealthPercent(NpcHealthPercentRequirement requirement,
                                    Ref<EntityStore> npcRef,
                                    Store<EntityStore> store) {
        double healthPercent = resolveNpcHealthPercent(npcRef, store);
        if (!Double.isFinite(healthPercent)) {
            return false;
        }
        return matchesNpcHealthPercentValue(healthPercent, requirement);
    }

    // Evaluates a health percent value against a requirement threshold.
    static boolean matchesNpcHealthPercentValue(double healthPercent, NpcHealthPercentRequirement requirement) {
        if (!Double.isFinite(healthPercent) || requirement == null) {
            return false;
        }
        Double threshold = requirement.getValue();
        if (threshold == null || !Double.isFinite(threshold)) {
            return false;
        }
        int compare = Double.compare(healthPercent, threshold);
        ParamOperator operator = requirement.getOperator();
        return switch (operator) {
            case Equals -> compare == 0;
            case NotEquals -> compare != 0;
            case GreaterThan -> compare > 0;
            case GreaterThanOrEqual -> compare >= 0;
            case LessThan -> compare < 0;
            case LessThanOrEqual -> compare <= 0;
        };
    }

    // Resolves NPC health as a percent [0-100] from the health stat map.
    private double resolveNpcHealthPercent(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return Double.NaN;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return Double.NaN;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return Double.NaN;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex("Health");
        if (healthIndex < 0) {
            return Double.NaN;
        }
        EntityStatValue value = statMap.get(healthIndex);
        if (value == null) {
            return Double.NaN;
        }
        double max = value.getMax();
        if (!Double.isFinite(max) || max <= 0.0) {
            return Double.NaN;
        }
        double current = value.get();
        if (!Double.isFinite(current)) {
            return Double.NaN;
        }
        current = Math.max(0.0, Math.min(current, max));
        double percent = (current / max) * 100.0;
        return Math.max(0.0, Math.min(percent, 100.0));
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
        return statesForPlayer(role, infoProvider, store, null);
    }

    private boolean matchesRoleInteractionContext(String context,
                                                  Role role,
                                                  InfoProvider infoProvider,
                                                  InteractionContextSnapshot ctx) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        Ref<EntityStore> playerRef = ctx != null && ctx.playerRef != null
                ? ctx.playerRef
                : owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        return role.getStateSupport().hasContextualInteraction(playerRef, context);
    }

    private MovementStates statesForPlayer(Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           InteractionContextSnapshot ctx) {
        if (ctx != null && ctx.cachedPlayerMovementStates != null) {
            return ctx.cachedPlayerMovementStates;
        }
        Ref<EntityStore> playerRef = ctx != null && ctx.playerRef != null
                ? ctx.playerRef
                : owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        MovementStatesComponent component = store.getComponent(playerRef, MovementStatesComponent.getComponentType());
        MovementStates states = component != null ? component.getMovementStates() : null;
        if (ctx != null) {
            ctx.cachedPlayerMovementStates = states;
        }
        return states;
    }

}
