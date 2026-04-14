package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.NpcHealthPercentRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StringRequirement;
import com.alechilles.alecstamework.ownership.LegacyTamedOwnershipBridge;
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
public class ActionTameworkInteract extends TameworkActionBase {
    private final String configIdOverride;
    private final boolean hasLovedItemsOverride;
    private final String[] lovedItemsOverride;
    private final Boolean isMountableOverride;
    private final Boolean isHarvestableOverride;
    private final boolean hasHarvestContextOverride;
    private final String harvestContextOverride;
    private final String triggerSource;
    private final String configParamName;
    private final String lovedItemsParamName;
    private final String isHarvestableParamName;
    private final String isMountableParamName;
    private final String harvestContextParamName;
    private final String harvestAlarmName;
    private final String cooldownAlarmPrefix;
    private final InteractionAlarmHelper alarmHelper;
    private final InteractionResolution resolution;
    private final InteractionSelection selection;
    private final InteractionExecution execution;

    public ActionTameworkInteract(BuilderActionTameworkInteract builder, BuilderSupport support) {
        super(builder);
        this.configIdOverride = builder.hasConfigIdOverride() ? builder.getConfigId(support) : null;
        this.hasLovedItemsOverride = builder.hasLovedItemsOverride();
        this.lovedItemsOverride = hasLovedItemsOverride ? builder.getLovedItems(support) : null;
        this.isMountableOverride = builder.hasIsMountableOverride() ? builder.getIsMountable(support) : null;
        this.isHarvestableOverride = builder.hasIsHarvestableOverride() ? builder.getIsHarvestable(support) : null;
        this.hasHarvestContextOverride = builder.hasHarvestInteractionContextOverride();
        this.harvestContextOverride = hasHarvestContextOverride ? builder.getHarvestInteractionContext(support) : null;
        this.triggerSource = builder.hasTriggerSourceOverride() ? builder.getTriggerSource(support) : null;
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        this.configParamName = globalConfig.getInteractionConfigParam();
        this.lovedItemsParamName = globalConfig.getLovedItemsParam();
        this.isHarvestableParamName = globalConfig.getIsHarvestableParam();
        this.isMountableParamName = globalConfig.getIsMountableParam();
        this.harvestContextParamName = globalConfig.getHarvestContextParam();
        this.harvestAlarmName = globalConfig.getHarvestAlarmName();
        this.cooldownAlarmPrefix = globalConfig.getInteractionCooldownAlarmPrefix();
        boolean interactionRequireOwnerDefault = globalConfig.isOwnershipInteractionRequiresOwner();
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
        InteractionParamAccess paramAccess = new InteractionParamAccess(
                paramResolver,
                hasLovedItemsOverride,
                lovedItemsOverride,
                isHarvestableOverride,
                isMountableOverride,
                lovedItemsParamName,
                isHarvestableParamName,
                isMountableParamName
        );
        InteractionConfigResolver configResolver = new InteractionConfigResolver(
                configIdOverride,
                paramAccess,
                configParamName
        );
        InteractionFeedHelper feedHelper = new InteractionFeedHelper(paramAccess);
        InteractionAlarmHelper alarmHelper = new InteractionAlarmHelper(this);
        this.alarmHelper = alarmHelper;
        InteractionItemRequirementResolver itemRequirements = new InteractionItemRequirementResolver(paramResolver);
        InteractionMatchHelpers matchHelpers = new InteractionMatchHelpers(this, paramAccess, alarmHelper);
        InteractionParamMatcher paramMatcher = new InteractionParamMatcher(paramAccess);
        InteractionOwnershipHelper ownershipHelper = new InteractionOwnershipHelper(this);
        InteractionExtensionRuntime interactionExtensionRuntime = null;
        Tamework plugin = Tamework.getInstance();
        if (plugin != null) {
            interactionExtensionRuntime = plugin.getInteractionExtensionRuntime();
        }
        TameworkInteractEffects effects = new TameworkInteractEffects(this, interactionExtensionRuntime);
        InteractionExecutor executor = new InteractionExecutor(effects, feedHelper);
        InteractionCooldowns cooldowns = new InteractionCooldowns(this, cooldownAlarmPrefix);
        TameworkInteractRequirements requirements =
                new TameworkInteractRequirements(
                        this,
                        feedHelper,
                        alarmHelper,
                        harvestAlarmName,
                        interactionRequireOwnerDefault,
                        interactionExtensionRuntime
                );
        InteractionSelector selector =
                new InteractionSelector(this, requirements, cooldowns, alarmHelper, harvestAlarmName);
        InteractionDiagnostics diagnostics = new InteractionDiagnostics(this, alarmHelper, harvestAlarmName);
        this.resolution = new InteractionResolution(paramAccess, configResolver);
        this.selection = new InteractionSelection(
                itemRequirements,
                matchHelpers,
                paramMatcher,
                ownershipHelper,
                requirements,
                selector,
                diagnostics
        );
        this.execution = new InteractionExecution(executor, cooldowns);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        selection.logDebug("TameworkInteract: execute called. source=" + describeTriggerSource());
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        Ref<EntityStore> interactionTarget = role.getStateSupport().getInteractionIterationTarget();
        if (interactionTarget == null || !interactionTarget.isValid()) {
            selection.logDebug("TameworkInteract: no interaction target recorded.");
            return false;
        }
        Player player = store.getComponent(interactionTarget, Player.getComponentType());
        if (player == null) {
            selection.logDebug("TameworkInteract: no player resolved for interaction.");
            return false;
        }
        InteractionContextSnapshot ctx = resolution.buildContextSnapshot(player, interactionTarget, role);
        String roleName = role != null ? role.getRoleName() : "<null>";
        String roleOverride = getRoleStringParam(role, ctx, configParamName);
        selection.logDebug(String.format(
                "TameworkInteract: source=%s role=%s configOverride=%s roleParam=%s heldItem=%s",
                describeTriggerSource(),
                roleName,
                configIdOverride,
                roleOverride,
                selection.describeHeldItem(ctx)
        ));
        TwInteractionConfig config = resolution.resolveConfig(role, ctx);
        if (config == null || !config.isEnabled()) {
            selection.logDebug(String.format(
                    "TameworkInteract: no config resolved or config disabled (role=%s).",
                    roleName
            ));
            return false;
        }
        LegacyTamedOwnershipBridge.ClaimResult ownerBridgeResult =
                LegacyTamedOwnershipBridge.claimForPlayerIfEligible(npcRef, store, player);
        if (ownerBridgeResult.isClaimed()) {
            selection.logDebug("TameworkInteract: claimed legacy tamed ownership for player interaction.");
        }
        ResolvedInteraction interaction = selection.selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
        if (interaction == null) {
            maybeNotifyOwnerDenied(npcRef, store, player);
            selection.logDebug(selection.buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx));
            return false;
        }
        if (interaction.blockedByCooldown) {
            return false;
        }
        return execution.applyInteraction(interaction, npcRef, role, infoProvider, store, player, ctx);
    }

    // Builds the cached context snapshot for prompt/selection helpers.
    InteractionContextSnapshot buildContextSnapshot(Player player,
                                                    Ref<EntityStore> playerRef,
                                                    Role role) {
        return resolution.buildContextSnapshot(player, playerRef, role);
    }

    // Resolves the interaction config for prompt/selection helpers.
    TwInteractionConfig resolveConfig(Role role, InteractionContextSnapshot ctx) {
        return resolution.resolveConfig(role, ctx);
    }

    // Selects an interaction entry without executing it.
    ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player,
                                          InteractionContextSnapshot ctx) {
        return selection.selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
    }

    // Selects an interaction for prompts, prioritizing contextual and conditional entries.
    ResolvedInteraction selectInteractionForPrompt(TwInteractionConfig config,
                                                   Ref<EntityStore> npcRef,
                                                   Role role,
                                                   InfoProvider infoProvider,
                                                   Store<EntityStore> store,
                                                   Player player,
                                                   InteractionContextSnapshot ctx) {
        return selection.selectInteractionForPrompt(config, npcRef, role, infoProvider, store, player, ctx);
    }

    boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store, InteractionContextSnapshot ctx) {
        return selection.isTamed(npcRef, store, ctx);
    }

    boolean isOwner(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player, InteractionContextSnapshot ctx) {
        return selection.isOwner(npcRef, store, player, ctx);
    }

    // Delegates item-in-hand requirements to the shared resolver.
    boolean matchesItemsInHand(ItemsInHandRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return selection.matchesItemsInHand(requirement, role, ctx);
    }

    // Delegates inventory-based requirements to the shared resolver.
    boolean matchesItemsInInventory(ItemsInInventoryRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return selection.matchesItemsInInventory(requirement, role, ctx);
    }

    // Delegates equipped-item requirements to the shared resolver.
    boolean matchesItemsEquipped(ItemsEquippedRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        return selection.matchesItemsEquipped(requirement, role, ctx);
    }

    // Delegates held-item matching for requirement checks.
    boolean isHeldItemInList(String[] items, InteractionContextSnapshot ctx) {
        return selection.isHeldItemInList(items, ctx);
    }

    // Returns true if the interacting player is not holding any item.
    boolean isPlayerHandEmpty(InteractionContextSnapshot ctx) {
        return selection.isPlayerHandEmpty(ctx);
    }

    // Delegates item param resolution for requirement parsing.
    String[] resolveItemsParam(Role role, InteractionContextSnapshot ctx, String itemsParam) {
        return selection.resolveItemsParam(role, ctx, itemsParam);
    }

    boolean matchesHarvestContext(Role role,
                                  InfoProvider infoProvider,
                                  InteractionContextSnapshot ctx) {
        String context = hasHarvestContextOverride
                ? harvestContextOverride
                : getRoleStringParam(role, ctx, harvestContextParamName);
        return selection.matchesInteractionContext(context, role, infoProvider, true);
    }

    boolean matchesHarvestContextForPrompt(Role role,
                                           InfoProvider infoProvider,
                                           InteractionContextSnapshot ctx) {
        String context = hasHarvestContextOverride
                ? harvestContextOverride
                : getRoleStringParam(role, ctx, harvestContextParamName);
        return selection.matchesInteractionContextForPrompt(context, role, infoProvider, ctx, true);
    }

    boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                      Role role,
                                      InfoProvider infoProvider,
                                      InteractionContextSnapshot ctx) {
        return selection.matchesInteractionContext(requirement, role, infoProvider, ctx);
    }

    boolean matchesInteractionContextForPrompt(InteractionContextRequirement requirement,
                                               Role role,
                                               InfoProvider infoProvider,
                                               InteractionContextSnapshot ctx) {
        return selection.matchesInteractionContextForPrompt(requirement, role, infoProvider, ctx);
    }

    boolean matchesInteractionContext(String context,
                                      Role role,
                                      InfoProvider infoProvider,
                                      boolean allowBlank) {
        return selection.matchesInteractionContext(context, role, infoProvider, allowBlank);
    }

    boolean matchesMovementState(MovementStateRequirement requirement,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          InteractionContextSnapshot ctx) {
        return selection.matchesMovementState(requirement, role, infoProvider, store, ctx);
    }

    boolean isPlayerCrouching(Role role,
                              InfoProvider infoProvider,
                              Store<EntityStore> store,
                              InteractionContextSnapshot ctx) {
        return selection.isPlayerCrouching(role, infoProvider, store, ctx);
    }

    boolean matchesAlarmState(AlarmRequirement requirement,
                              Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              Role role,
                              InteractionContextSnapshot ctx) {
        return selection.matchesAlarmState(requirement, npcRef, store, role, ctx);
    }

    boolean matchesParamRequirement(ParamRequirement requirement, Role role) {
        return selection.matchesParamRequirement(requirement, role);
    }

    boolean matchesNpcHealthPercent(NpcHealthPercentRequirement requirement,
                                    Ref<EntityStore> npcRef,
                                    Store<EntityStore> store) {
        return selection.matchesNpcHealthPercent(requirement, npcRef, store);
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
        return resolution.resolveLovedItems(role, ctx);
    }

    InteractionRequiredItems resolveFeedRequirementItems(FeedInteraction interaction,
                                                         Role role,
                                                         InteractionContextSnapshot ctx) {
        return resolution.resolveFeedRequirementItems(interaction, role, ctx);
    }

    boolean resolveIsHarvestable(Role role, InteractionContextSnapshot ctx) {
        return resolution.resolveIsHarvestable(role, ctx);
    }

    boolean resolveIsMountable(Role role, InteractionContextSnapshot ctx) {
        return resolution.resolveIsMountable(role, ctx);
    }

    String getRoleStringParam(Role role, String paramName) {
        return resolution.getRoleStringParam(role, null, paramName);
    }

    String getRoleStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return resolution.getRoleStringParam(role, ctx, paramName);
    }

    String[] getRoleStringArrayParam(Role role, String paramName) {
        return resolution.getRoleStringArrayParam(role, null, paramName);
    }

    String[] getRoleStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return resolution.getRoleStringArrayParam(role, ctx, paramName);
    }

    private boolean getRoleBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return resolution.getRoleBooleanParam(role, ctx, paramName);
    }

    double getRoleNumberParam(Role role, String paramName, double defaultValue) {
        return resolution.getRoleNumberParam(role, null, paramName, defaultValue);
    }

    double getRoleNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        return resolution.getRoleNumberParam(role, ctx, paramName, defaultValue);
    }

    double[] getRoleNumberArrayParam(Role role, String paramName) {
        return resolution.getRoleNumberArrayParam(role, null, paramName);
    }

    double[] getRoleNumberArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return resolution.getRoleNumberArrayParam(role, ctx, paramName);
    }

    void logUnsupported(String message) {
        selection.logUnsupported(message);
    }

    void logDebug(String message) {
        selection.logDebug(message);
    }

    boolean isHarvestAlarmReady(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return isHarvestAlarmReady(npcRef, store, null);
    }

    boolean isHarvestAlarmReady(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                InteractionContextSnapshot ctx) {
        return alarmHelper.isAlarmReady(npcRef, store, harvestAlarmName, ctx);
    }

    boolean isHarvestAlarmPassed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return alarmHelper.matchesAlarmState(npcRef, store, harvestAlarmName, "passed", null);
    }

    boolean isHarvestAlarmActive(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return alarmHelper.matchesAlarmState(npcRef, store, harvestAlarmName, "active", null);
    }

    boolean isHarvestAlarmUnset(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return alarmHelper.matchesAlarmState(npcRef, store, harvestAlarmName, "unset", null);
    }

    InteractionAlarmHelper.AlarmSnapshot getHarvestAlarmSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return getHarvestAlarmSnapshot(npcRef, store, null);
    }

    InteractionAlarmHelper.AlarmSnapshot getHarvestAlarmSnapshot(Ref<EntityStore> npcRef,
                                                                 Store<EntityStore> store,
                                                                 InteractionContextSnapshot ctx) {
        return alarmHelper.snapshot(npcRef, store, harvestAlarmName, ctx);
    }

    String getHarvestAlarmName() {
        return harvestAlarmName;
    }

    String describeTriggerSource() {
        return triggerSource != null && !triggerSource.isBlank() ? triggerSource : "<unspecified>";
    }

    private void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Player player) {
        selection.maybeNotifyOwnerDenied(npcRef, store, player);
    }

    // Captures the selected interaction entry and cooldown metadata.
    static final class ResolvedInteraction {
        final String configId;
        final InteractionEntry entry;
        final int index;
        final int cooldownSeconds;
        final String cooldownAlarmName;
        final boolean blockedByCooldown;

        ResolvedInteraction(String configId,
                            InteractionEntry entry,
                            int index,
                            int cooldownSeconds,
                            String cooldownAlarmName) {
            this.configId = configId;
            this.entry = entry;
            this.index = index;
            this.cooldownSeconds = cooldownSeconds;
            this.cooldownAlarmName = cooldownAlarmName;
            this.blockedByCooldown = false;
        }

        ResolvedInteraction(String configId,
                            InteractionEntry entry,
                            int index,
                            int cooldownSeconds,
                            String cooldownAlarmName,
                            boolean blockedByCooldown) {
            this.configId = configId;
            this.entry = entry;
            this.index = index;
            this.cooldownSeconds = cooldownSeconds;
            this.cooldownAlarmName = cooldownAlarmName;
            this.blockedByCooldown = blockedByCooldown;
        }
    }

}
