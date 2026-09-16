package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.NpcHealthPercentRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StringRequirement;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import java.util.UUID;

/**
 * Prototype action that executes a TwInteractionConfig-driven interaction flow.
 */
public class ActionTameworkInteract extends TameworkActionBase {
    private static final ThreadLocal<ActionTameworkInteract> NEUTRAL_CHAIN_ACTION = new ThreadLocal<>();
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
    private final InteractionParamAccess paramAccess;
    private final InteractionConfigResolver configResolver;
    private final InteractionParamAccess neutralChainParamAccess;
    private final InteractionConfigResolver neutralChainConfigResolver;
    private final InteractionItemRequirementResolver itemRequirements;
    private final InteractionMatchHelpers matchHelpers;
    private final InteractionParamMatcher paramMatcher;
    private final InteractionOwnershipHelper ownershipHelper;
    private final InteractionSelector selector;
    private final InteractionDiagnostics diagnostics;
    private final InteractionCooldowns cooldowns;
    private final InteractionExecutor executor;
    private final InteractionLegacyAdoptionService legacyAdoptionService;

    public ActionTameworkInteract(BuilderActionTameworkInteract builder, BuilderSupport support) {
        this(builder, support, true);
    }

    protected ActionTameworkInteract(
            BuilderActionTameworkInteract builder,
            BuilderSupport support,
            boolean includeExecutionEffects
    ) {
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
        if (globalConfig == null) {
            globalConfig = TwGlobalConfig.defaultConfig();
        }
        this.configParamName = globalConfig.getInteractionConfigParam();
        this.lovedItemsParamName = globalConfig.getLovedItemsParam();
        this.isHarvestableParamName = globalConfig.getIsHarvestableParam();
        this.isMountableParamName = globalConfig.getIsMountableParam();
        this.harvestContextParamName = globalConfig.getHarvestContextParam();
        this.harvestAlarmName = globalConfig.getHarvestAlarmName();
        this.cooldownAlarmPrefix = globalConfig.getInteractionCooldownAlarmPrefix();
        boolean interactionRequireOwnerDefault =
                TameworkRuntimeSettings.interactionRequiresOwner(globalConfig.isOwnershipInteractionRequiresOwner());
        StdScope globalSnapshot = null;
        StdScope execSnapshot = null;
        StdScope sensorSnapshot = null;
        StdScope roleParameterSnapshot = null;
        if (support != null) {
            roleParameterSnapshot = InteractionRoleParameterScope.snapshotDeclaredParameters(support);
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
        InteractionParamResolver paramResolver = new InteractionParamResolver(
                roleParameterSnapshot,
                globalSnapshot,
                execSnapshot,
                sensorSnapshot
        );
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
        this.paramAccess = paramAccess;
        this.configResolver = configResolver;
        InteractionParamResolver neutralParamResolver = new InteractionParamResolver(
                null, null, null, null);
        InteractionParamAccess neutralParamAccess = new InteractionParamAccess(
                neutralParamResolver, false, null, null, null,
                lovedItemsParamName, isHarvestableParamName, isMountableParamName);
        this.neutralChainParamAccess = neutralParamAccess;
        this.neutralChainConfigResolver =
                new InteractionConfigResolver(null, neutralParamAccess, configParamName);
        this.itemRequirements = itemRequirements;
        this.matchHelpers = matchHelpers;
        this.paramMatcher = paramMatcher;
        this.ownershipHelper = ownershipHelper;
        this.selector = selector;
        this.diagnostics = diagnostics;
        this.cooldowns = cooldowns;
        if (includeExecutionEffects) {
            TameworkInteractEffects effects =
                    new TameworkInteractEffects(this, interactionExtensionRuntime);
            this.executor = new InteractionExecutor(effects, feedHelper);
        } else {
            this.executor = null;
        }
        this.legacyAdoptionService = new InteractionLegacyAdoptionService(diagnostics::logDebug);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        diagnostics.logDebug("TameworkInteract: execute called. source=" + describeTriggerSource());
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        StateSupport stateSupport = NpcSupportAccess.state(role, npcRef, store);
        if (role == null || stateSupport == null) {
            return false;
        }
        Ref<EntityStore> interactionTarget = stateSupport.getInteractionIterationTarget();
        if (interactionTarget == null || !interactionTarget.isValid()) {
            diagnostics.logDebug("TameworkInteract: no interaction target recorded.");
            return false;
        }
        Player player = store.getComponent(interactionTarget, Player.getComponentType());
        if (player == null) {
            diagnostics.logDebug("TameworkInteract: no player resolved for interaction.");
            return false;
        }
        return executeWithPlayer(npcRef, role, infoProvider, store, interactionTarget, player, true);
    }

    private boolean executeWithPlayer(Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Ref<EntityStore> playerRef,
                                      Player player,
                                      boolean allowLegacyAdoption) {
        InteractionContextSnapshot ctx = paramAccess.buildContextSnapshot(player, playerRef, role);
        String roleName = role != null ? role.getRoleName() : "<null>";
        String roleOverride = getRoleStringParam(role, ctx, configParamName);
        diagnostics.logDebug(String.format(
                "TameworkInteract: source=%s role=%s configOverride=%s roleParam=%s heldItem=%s",
                describeTriggerSource(),
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
        if (allowLegacyAdoption) {
            InteractionLegacyAdoptionService.Attempt attempt = legacyAdoptionService.attempt(
                    npcRef, store, player, role,
                    live -> executeWithPlayer(
                            live.npcRef(), live.role(), infoProvider, live.store(),
                            live.playerRef(), live.player(), false
                    )
            );
            if (attempt.handled()) {
                return attempt.succeeded();
            }
        }
        ResolvedInteraction interaction = selector.selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
        if (interaction == null) {
            ownershipHelper.maybeNotifyOwnerDenied(npcRef, store, player);
            diagnostics.logDebug(diagnostics.buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx));
            return false;
        }
        if (interaction.blockedByCooldown) {
            return false;
        }
        return applyInteraction(interaction, npcRef, role, infoProvider, store, player, ctx);
    }

    private boolean applyInteraction(ResolvedInteraction interaction,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     InfoProvider infoProvider,
                                     Store<EntityStore> store,
                                     Player player,
                                     InteractionContextSnapshot ctx) {
        if (executor == null || interaction == null) {
            return false;
        }
        boolean applied = executor.applyInteraction(interaction, npcRef, role, infoProvider, store, player, ctx);
        if (applied) {
            cooldowns.applyInteractionCooldown(interaction, npcRef, store);
        }
        return applied;
    }

    /** Re-selects and executes the target's own Harvest interaction without source action overrides. */
    boolean executeNeutralChainHarvest(
            Ref<EntityStore> npcRef,
            Role role,
            InfoProvider infoProvider,
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            Player player
    ) {
        ActionTameworkInteract previous = NEUTRAL_CHAIN_ACTION.get();
        NEUTRAL_CHAIN_ACTION.set(this);
        try {
            InteractionContextSnapshot ctx = neutralChainParamAccess.buildContextSnapshot(player, playerRef, role);
            TwInteractionConfig config = neutralChainConfigResolver.resolveConfig(role, ctx);
            if (config == null || !config.isEnabled()) {
                return false;
            }
            ResolvedInteraction interaction = selector.selectInteraction(
                    config, npcRef, role, infoProvider, store, player, ctx);
            if (interaction == null || interaction.blockedByCooldown
                    || !(interaction.entry instanceof HarvestInteraction)) {
                return false;
            }
            return InteractionExecutor.withChainSuppressed(
                    () -> applyInteraction(interaction, npcRef, role, infoProvider, store, player, ctx));
        } finally {
            if (previous == null) {
                NEUTRAL_CHAIN_ACTION.remove();
            } else {
                NEUTRAL_CHAIN_ACTION.set(previous);
            }
        }
    }

    private InteractionParamAccess activeParamAccess() {
        return isNeutralChainSelection() ? neutralChainParamAccess : paramAccess;
    }

    private boolean isNeutralChainSelection() {
        return NEUTRAL_CHAIN_ACTION.get() == this;
    }

    // Builds the cached context snapshot for prompt/selection helpers.
    InteractionContextSnapshot buildContextSnapshot(Player player,
                                                    Ref<EntityStore> playerRef,
                                                    Role role) {
        return activeParamAccess().buildContextSnapshot(player, playerRef, role);
    }

    InteractionContextSnapshot buildContextSnapshot(Player player,
                                                    Ref<EntityStore> playerRef,
                                                    Role role,
                                                    ItemStack activeItem,
                                                    UUID playerId) {
        return activeParamAccess().buildContextSnapshot(player, playerRef, role, activeItem, playerId);
    }

    // Resolves the interaction config for prompt/selection helpers.
    TwInteractionConfig resolveConfig(Role role, InteractionContextSnapshot ctx) {
        return (isNeutralChainSelection() ? neutralChainConfigResolver : configResolver).resolveConfig(role, ctx);
    }

    // Selects an interaction entry without executing it.
    ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player,
                                          InteractionContextSnapshot ctx) {
        return selector.selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
    }

    // Selects an interaction for prompts, prioritizing contextual and conditional entries.
    ResolvedInteraction selectInteractionForPrompt(TwInteractionConfig config,
                                                   Ref<EntityStore> npcRef,
                                                   Role role,
                                                   InfoProvider infoProvider,
                                                   Store<EntityStore> store,
                                                   Player player,
                                                   InteractionContextSnapshot ctx) {
        return selector.selectInteractionForPrompt(config, npcRef, role, infoProvider, store, player, ctx);
    }

    String describeHeldItem(InteractionContextSnapshot ctx) {
        return diagnostics.describeHeldItem(ctx);
    }

    String buildNoMatchSummary(TwInteractionConfig config,
                               Ref<EntityStore> npcRef,
                               Role role,
                               InfoProvider infoProvider,
                               Store<EntityStore> store,
                               Player player,
                               InteractionContextSnapshot ctx) {
        return diagnostics.buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx);
    }

    boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store, InteractionContextSnapshot ctx) {
        return ownershipHelper.isTamed(npcRef, store, ctx);
    }

    boolean isOwner(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player, InteractionContextSnapshot ctx) {
        return ownershipHelper.isOwner(npcRef, store, player, ctx);
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

    // Returns true if the interacting player is not holding any item.
    boolean isPlayerHandEmpty(InteractionContextSnapshot ctx) {
        return itemRequirements.isPlayerHandEmpty(ctx);
    }

    // Delegates item param resolution for requirement parsing.
    String[] resolveItemsParam(Role role, InteractionContextSnapshot ctx, String itemsParam) {
        return itemRequirements.resolveItemsParam(role, ctx, itemsParam);
    }

    boolean matchesHarvestContext(Role role,
                                  InfoProvider infoProvider,
                                  InteractionContextSnapshot ctx) {
        String context = !isNeutralChainSelection() && hasHarvestContextOverride
                ? harvestContextOverride
                : getRoleStringParam(role, ctx, harvestContextParamName);
        return matchHelpers.matchesInteractionContext(context, role, infoProvider, true);
    }

    boolean matchesHarvestContextForPrompt(Role role,
                                           InfoProvider infoProvider,
                                           InteractionContextSnapshot ctx) {
        String context = !isNeutralChainSelection() && hasHarvestContextOverride
                ? harvestContextOverride
                : getRoleStringParam(role, ctx, harvestContextParamName);
        return matchHelpers.matchesInteractionContextForPrompt(context, role, infoProvider, ctx, true);
    }

    boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                      Role role,
                                      InfoProvider infoProvider,
                                      InteractionContextSnapshot ctx) {
        return matchHelpers.matchesInteractionContext(requirement, role, infoProvider, ctx);
    }

    boolean matchesInteractionContextForPrompt(InteractionContextRequirement requirement,
                                               Role role,
                                               InfoProvider infoProvider,
                                               InteractionContextSnapshot ctx) {
        return matchHelpers.matchesInteractionContextForPrompt(requirement, role, infoProvider, ctx);
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
                                          Store<EntityStore> store,
                                          InteractionContextSnapshot ctx) {
        return matchHelpers.matchesMovementState(requirement, role, infoProvider, store, ctx);
    }

    boolean isPlayerCrouching(Role role,
                              InfoProvider infoProvider,
                              Store<EntityStore> store,
                              InteractionContextSnapshot ctx) {
        return matchHelpers.isPlayerCrouching(role, infoProvider, store, ctx);
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

    boolean matchesNpcHealthPercent(NpcHealthPercentRequirement requirement,
                                    Ref<EntityStore> npcRef,
                                    Store<EntityStore> store) {
        return matchHelpers.matchesNpcHealthPercent(requirement, npcRef, store);
    }

    boolean matchesNpcState(StringRequirement requirement, Role role) {
        StateSupport stateSupport = NpcSupportAccess.state(role, null, null);
        if (requirement == null || role == null || stateSupport == null) {
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
                return stateSupport.inState(state, "");
            }
            return stateSupport.inState(state, subState);
        }
        if (subState == null || subState.isBlank()) {
            return false;
        }
        int currentState = stateSupport.getStateIndex();
        int currentSubState = stateSupport.getSubStateIndex();
        String currentSubName = stateSupport
                .getStateHelper()
                .getSubStateName(currentState, currentSubState);
        return currentSubName != null && currentSubName.equalsIgnoreCase(subState);
    }

    String[] resolveLovedItems(Role role, InteractionContextSnapshot ctx) {
        return activeParamAccess().resolveLovedItems(role, ctx);
    }

    InteractionRequiredItems resolveFeedRequirementItems(FeedInteraction interaction,
                                                         Role role,
                                                         InteractionContextSnapshot ctx) {
        return activeParamAccess().resolveFeedRequirementItems(interaction, role, ctx);
    }

    boolean resolveIsHarvestable(Role role, InteractionContextSnapshot ctx) {
        return activeParamAccess().resolveIsHarvestable(role, ctx);
    }

    boolean resolveIsMountable(Role role, InteractionContextSnapshot ctx) {
        return activeParamAccess().resolveIsMountable(role, ctx);
    }

    String getRoleStringParam(Role role, String paramName) {
        return activeParamAccess().getRoleStringParam(role, null, paramName);
    }

    String getRoleStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return activeParamAccess().getRoleStringParam(role, ctx, paramName);
    }

    StdScope[] resolveRoleScopes(Role role) {
        return activeParamAccess().resolveRoleScopes(role);
    }

    String[] getRoleStringArrayParam(Role role, String paramName) {
        return activeParamAccess().getRoleStringArrayParam(role, null, paramName);
    }

    String[] getRoleStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return activeParamAccess().getRoleStringArrayParam(role, ctx, paramName);
    }

    private boolean getRoleBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return activeParamAccess().getRoleBooleanParam(role, ctx, paramName);
    }

    double getRoleNumberParam(Role role, String paramName, double defaultValue) {
        return activeParamAccess().getRoleNumberParam(role, null, paramName, defaultValue);
    }

    double getRoleNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        return activeParamAccess().getRoleNumberParam(role, ctx, paramName, defaultValue);
    }

    double[] getRoleNumberArrayParam(Role role, String paramName) {
        return activeParamAccess().getRoleNumberArrayParam(role, null, paramName);
    }

    double[] getRoleNumberArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return activeParamAccess().getRoleNumberArrayParam(role, ctx, paramName);
    }

    void logUnsupported(String message) {
        diagnostics.logUnsupported(message);
    }

    void logDebug(String message) {
        diagnostics.logDebug(message);
    }

    boolean isHarvestAlarmReady(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return isHarvestAlarmReady(npcRef, store, null);
    }

    boolean isHarvestAlarmReady(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                InteractionContextSnapshot ctx) {
        return TameworkAlarmService.isReady(npcRef, store, harvestAlarmName);
    }

    boolean isHarvestAlarmPassed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return TameworkAlarmService.snapshot(npcRef, store, harvestAlarmName).passed;
    }

    boolean isHarvestAlarmActive(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return TameworkAlarmService.snapshot(npcRef, store, harvestAlarmName).active;
    }

    boolean isHarvestAlarmUnset(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return !TameworkAlarmService.snapshot(npcRef, store, harvestAlarmName).exists;
    }

    TameworkAlarmService.Snapshot getHarvestAlarmSnapshot(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return getHarvestAlarmSnapshot(npcRef, store, null);
    }

    TameworkAlarmService.Snapshot getHarvestAlarmSnapshot(Ref<EntityStore> npcRef,
                                                          Store<EntityStore> store,
                                                          InteractionContextSnapshot ctx) {
        return TameworkAlarmService.snapshot(npcRef, store, harvestAlarmName);
    }

    String getHarvestAlarmName() {
        return harvestAlarmName;
    }

    String describeTriggerSource() {
        return triggerSource != null && !triggerSource.isBlank() ? triggerSource : "<unspecified>";
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
