package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StringRequirement;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.npc.util.expression.Scope;

/**
 * Prototype action that executes a TwInteractionConfig-driven interaction flow.
 */
public final class ActionTameworkInteract extends TameworkActionBase {
    static final String DEFAULT_CONFIG_PARAM = "InteractionConfigId";
    static final String DEFAULT_LOVED_ITEMS_PARAM = "LovedItems";
    static final String DEFAULT_IS_HARVESTABLE_PARAM = "IsHarvestable";
    static final String DEFAULT_IS_MOUNTABLE_PARAM = "IsMountable";
    static final String DEFAULT_HARVEST_CONTEXT_PARAM = "HarvestInteractionContext";
    static final String DEFAULT_HARVEST_ALARM = "Harvest_Ready";
    static final String DEFAULT_COOLDOWN_ALARM_PREFIX = "TameworkInteract_Cooldown";

    private final String configIdOverride;
    private final boolean hasLovedItemsOverride;
    private final String[] lovedItemsOverride;
    private final Boolean isMountableOverride;
    private final Boolean isHarvestableOverride;
    private final boolean hasHarvestContextOverride;
    private final String harvestContextOverride;
    private final InteractionConfigResolver configResolver;
    private final InteractionParamAccess paramAccess;
    private final InteractionFeedHelper feedHelper;
    private final InteractionItemRequirementResolver itemRequirements;
    private final TameworkInteractEffects effects;
    private final TameworkInteractRequirements requirements;
    private final InteractionExecutor executor;
    private final InteractionCooldowns cooldowns;
    private final InteractionSelector selector;
    private final InteractionDiagnostics diagnostics;
    private final InteractionMatchHelpers matchHelpers;
    private final InteractionParamMatcher paramMatcher;
    private final InteractionOwnershipHelper ownershipHelper;
    private final InteractionAlarmHelper alarmHelper;

    public ActionTameworkInteract(BuilderActionTameworkInteract builder, BuilderSupport support) {
        super(builder);
        this.configIdOverride = builder.hasConfigIdOverride() ? builder.getConfigId(support) : null;
        this.hasLovedItemsOverride = builder.hasLovedItemsOverride();
        this.lovedItemsOverride = hasLovedItemsOverride ? builder.getLovedItems(support) : null;
        this.isMountableOverride = builder.hasIsMountableOverride() ? builder.getIsMountable(support) : null;
        this.isHarvestableOverride = builder.hasIsHarvestableOverride() ? builder.getIsHarvestable(support) : null;
        this.hasHarvestContextOverride = builder.hasHarvestInteractionContextOverride();
        this.harvestContextOverride = hasHarvestContextOverride ? builder.getHarvestInteractionContext(support) : null;
        StdScope globalSnapshot = null;
        StdScope execSnapshot = null;
        StdScope sensorSnapshot = null;
        if (support != null) {
            Scope globalScope = support.getGlobalScope();
            if (globalScope != null) {
                globalSnapshot = globalScope instanceof StdScope
                        ? StdScope.copyOf((StdScope) globalScope)
                        : new StdScope(globalScope);
            }
            ExecutionContext execContext = support.getExecutionContext();
            Scope execScope = execContext != null ? execContext.getScope() : null;
            if (execScope != null) {
                execSnapshot = execScope instanceof StdScope
                        ? StdScope.copyOf((StdScope) execScope)
                        : new StdScope(execScope);
            }
            StdScope supportScope = support.getSensorScope();
            if (supportScope != null) {
                sensorSnapshot = StdScope.copyOf(supportScope);
            }
        }
        InteractionParamResolver paramResolver = new InteractionParamResolver(globalSnapshot, execSnapshot, sensorSnapshot);
        this.paramAccess = new InteractionParamAccess(
                paramResolver,
                hasLovedItemsOverride,
                lovedItemsOverride,
                isHarvestableOverride,
                isMountableOverride
        );
        this.configResolver = new InteractionConfigResolver(
                configIdOverride,
                paramAccess,
                DEFAULT_CONFIG_PARAM
        );
        this.feedHelper = new InteractionFeedHelper(paramAccess);
        this.itemRequirements = new InteractionItemRequirementResolver(paramResolver);
        this.effects = new TameworkInteractEffects(this);
        this.alarmHelper = new InteractionAlarmHelper(this);
        this.requirements = new TameworkInteractRequirements(this, feedHelper, alarmHelper);
        this.executor = new InteractionExecutor(effects, feedHelper);
        this.cooldowns = new InteractionCooldowns(this, DEFAULT_COOLDOWN_ALARM_PREFIX);
        this.selector = new InteractionSelector(this, requirements, cooldowns, alarmHelper);
        this.diagnostics = new InteractionDiagnostics(this, alarmHelper);
        this.matchHelpers = new InteractionMatchHelpers(this, paramAccess, alarmHelper);
        this.paramMatcher = new InteractionParamMatcher(paramAccess);
        this.ownershipHelper = new InteractionOwnershipHelper(this);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        diagnostics.logDebug("TameworkInteract: execute called.");
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            diagnostics.logDebug("TameworkInteract: no player resolved for interaction.");
            return false;
        }
        InteractionContextSnapshot ctx = paramAccess.buildContextSnapshot(player, role);
        String roleName = role != null ? role.getRoleName() : "<null>";
        String roleOverride = getRoleStringParam(role, ctx, DEFAULT_CONFIG_PARAM);
        diagnostics.logDebug(String.format(
                "TameworkInteract: role=%s configOverride=%s roleParam=%s heldItem=%s",
                roleName,
                configIdOverride,
                roleOverride,
                diagnostics.describeHeldItem(ctx)
        ));
        TwInteractionConfig config = configResolver.resolveConfig(role, ctx);
        if (config == null || !config.isEnabled()) {
            diagnostics.logDebug(String.format(
                    "TameworkInteract: no config resolved or config disabled (role=%s).",
                    roleName
            ));
            return false;
        }
        ResolvedInteraction interaction = selector.selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
        if (interaction == null) {
            maybeNotifyOwnerDenied(npcRef, store, player);
            diagnostics.logDebug(diagnostics.buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx));
            return false;
        }
        if (interaction.blockedByCooldown) {
            return false;
        }
        boolean applied = executor.applyInteraction(interaction.entry, npcRef, role, infoProvider, store, player, ctx);
        if (applied) {
            cooldowns.applyInteractionCooldown(interaction, npcRef, store);
        }
        return applied;
    }

    boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return ownershipHelper.isTamed(npcRef, store);
    }

    boolean isOwner(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        return ownershipHelper.isOwner(npcRef, store, player);
    }

    // Delegates item-in-hand requirements to the shared resolver.
    boolean matchesItemsInHand(ItemsInHandRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return itemRequirements.matchesItemsInHand(requirement, role, ctx);
    }

    // Delegates inventory-based requirements to the shared resolver.
    boolean matchesItemsInInventory(ItemsInInventoryRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return itemRequirements.matchesItemsInInventory(requirement, role, ctx);
    }

    // Delegates equipped-item requirements to the shared resolver.
    boolean matchesItemsEquipped(ItemsEquippedRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return itemRequirements.matchesItemsEquipped(requirement, role, ctx);
    }

    // Delegates held-item matching for requirement checks.
    boolean isHeldItemInList(String[] items, InteractionContextSnapshot ctx) {
        return itemRequirements.isHeldItemInList(items, ctx);
    }

    // Delegates item param resolution for requirement parsing.
    String[] resolveItemsParam(Role role, InteractionContextSnapshot ctx, String itemsParam) {
        return itemRequirements.resolveItemsParam(role, ctx, itemsParam);
    }

    boolean matchesHarvestContext(Role role,
                                  InfoProvider infoProvider,
                                  InteractionContextSnapshot ctx) {
        String context = hasHarvestContextOverride
                ? harvestContextOverride
                : getRoleStringParam(role, ctx, DEFAULT_HARVEST_CONTEXT_PARAM);
        return matchHelpers.matchesInteractionContext(context, role, infoProvider, true);
    }

    boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                      Role role,
                                      InfoProvider infoProvider,
                                      InteractionContextSnapshot ctx) {
        return matchHelpers.matchesInteractionContext(requirement, role, infoProvider, ctx);
    }

    boolean matchesInteractionContext(String context,
                                      Role role,
                                      InfoProvider infoProvider,
                                      boolean allowBlank) {
        return matchHelpers.matchesInteractionContext(context, role, infoProvider, allowBlank);
    }

    boolean matchesMovementState(MovementStateRequirement requirement,
                                         Role role,
                                         InfoProvider infoProvider,
                                         Store<EntityStore> store) {
        return matchHelpers.matchesMovementState(requirement, role, infoProvider, store);
    }

    boolean isPlayerCrouching(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        return matchHelpers.isPlayerCrouching(role, infoProvider, store);
    }

    boolean matchesAlarmState(AlarmRequirement requirement,
                              Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              Role role,
                              InteractionContextSnapshot ctx) {
        return matchHelpers.matchesAlarmState(requirement, npcRef, store, role, ctx);
    }

    boolean matchesParamRequirement(ParamRequirement requirement, Role role) {
        return paramMatcher.matchesParamRequirement(requirement, role);
    }

    boolean matchesNpcState(StringRequirement requirement, Role role) {
        if (requirement == null || role == null || role.getStateSupport() == null) {
            return false;
        }
        String state = requirement.getState();
        String subState = requirement.getSubState();
        if (state != null && state.contains(".")) {
            String[] parts = state.split("\\.", 2);
            state = parts[0];
            if (subState == null || subState.isBlank()) {
                subState = parts[1];
            }
        }
        if (state != null && !state.isBlank()) {
            if (subState == null || subState.isBlank()) {
                return role.getStateSupport().inState(state, "");
            }
            return role.getStateSupport().inState(state, subState);
        }
        if (subState == null || subState.isBlank()) {
            return false;
        }
        int currentState = role.getStateSupport().getStateIndex();
        int currentSubState = role.getStateSupport().getSubStateIndex();
        String currentSubName = role.getStateSupport()
                .getStateHelper()
                .getSubStateName(currentState, currentSubState);
        return currentSubName != null && currentSubName.equalsIgnoreCase(subState);
    }

    String[] resolveLovedItems(Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveLovedItems(role, ctx);
    }

    boolean resolveIsHarvestable(Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveIsHarvestable(role, ctx);
    }

    boolean resolveIsMountable(Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveIsMountable(role, ctx);
    }

    String getRoleStringParam(Role role, String paramName) {
        return paramAccess.getRoleStringParam(role, null, paramName);
    }

    String getRoleStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramAccess.getRoleStringParam(role, ctx, paramName);
    }

    String[] getRoleStringArrayParam(Role role, String paramName) {
        return paramAccess.getRoleStringArrayParam(role, null, paramName);
    }

    String[] getRoleStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramAccess.getRoleStringArrayParam(role, ctx, paramName);
    }

    private boolean getRoleBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramAccess.getRoleBooleanParam(role, ctx, paramName);
    }

    double getRoleNumberParam(Role role, String paramName, double defaultValue) {
        return paramAccess.getRoleNumberParam(role, null, paramName, defaultValue);
    }

    double getRoleNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        return paramAccess.getRoleNumberParam(role, ctx, paramName, defaultValue);
    }

    void logUnsupported(String message) {
        diagnostics.logUnsupported(message);
    }

    void logDebug(String message) {
        diagnostics.logDebug(message);
    }

    private void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Player player) {
        ownershipHelper.maybeNotifyOwnerDenied(npcRef, store, player);
    }

    // Captures the selected interaction entry and cooldown metadata.
    static final class ResolvedInteraction {
        final InteractionEntry entry;
        final int index;
        final int cooldownSeconds;
        final String cooldownAlarmName;
        final boolean blockedByCooldown;

        ResolvedInteraction(InteractionEntry entry,
                            int index,
                            int cooldownSeconds,
                            String cooldownAlarmName) {
            this.entry = entry;
            this.index = index;
            this.cooldownSeconds = cooldownSeconds;
            this.cooldownAlarmName = cooldownAlarmName;
            this.blockedByCooldown = false;
        }

        ResolvedInteraction(InteractionEntry entry,
                            int index,
                            int cooldownSeconds,
                            String cooldownAlarmName,
                            boolean blockedByCooldown) {
            this.entry = entry;
            this.index = index;
            this.cooldownSeconds = cooldownSeconds;
            this.cooldownAlarmName = cooldownAlarmName;
            this.blockedByCooldown = blockedByCooldown;
        }
    }

}
