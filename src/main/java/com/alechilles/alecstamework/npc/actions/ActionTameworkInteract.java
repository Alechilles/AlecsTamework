package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInInventoryRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamOperator;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StringRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.EntityPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

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
    private final InteractionParamResolver paramResolver;
    private final InteractionItemRequirementResolver itemRequirements;
    private final TameworkInteractEffects effects;
    private final TameworkInteractRequirements requirements;

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
        this.paramResolver = new InteractionParamResolver(globalSnapshot, execSnapshot, sensorSnapshot);
        this.itemRequirements = new InteractionItemRequirementResolver(paramResolver);
        this.effects = new TameworkInteractEffects(this);
        this.requirements = new TameworkInteractRequirements(this);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        logDebug("TameworkInteract: execute called.");
        if (npcRef == null || !npcRef.isValid()) {
            return false;
        }
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            logDebug("TameworkInteract: no player resolved for interaction.");
            return false;
        }
        StdScope[] roleScopes = paramResolver.resolveRoleScopes(role, null);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(player, roleScopes);
        String roleName = role != null ? role.getRoleName() : "<null>";
        String roleOverride = getRoleStringParam(role, ctx, DEFAULT_CONFIG_PARAM);
        logDebug(String.format(
                "TameworkInteract: role=%s configOverride=%s roleParam=%s heldItem=%s",
                roleName,
                configIdOverride,
                roleOverride,
                describeHeldItem(ctx)
        ));
        TwInteractionConfig config = resolveConfig(role, ctx);
        if (config == null || !config.isEnabled()) {
            logDebug(String.format(
                    "TameworkInteract: no config resolved or config disabled (role=%s).",
                    roleName
            ));
            return false;
        }
        ResolvedInteraction interaction = selectInteraction(config, npcRef, role, infoProvider, store, player, ctx);
        if (interaction == null) {
            maybeNotifyOwnerDenied(npcRef, store, player);
            logDebug(buildNoMatchSummary(config, npcRef, role, infoProvider, store, player, ctx));
            return false;
        }
        boolean applied = applyInteraction(interaction.entry, npcRef, role, infoProvider, store, player, ctx);
        if (applied) {
            applyInteractionCooldown(interaction, npcRef, store);
        }
        return applied;
    }

    private TwInteractionConfig resolveConfig(Role role, InteractionContextSnapshot ctx) {
        DefaultAssetMap<String, TwInteractionConfig> assetMap = TwInteractionConfig.getAssetMap();
        if (assetMap == null) {
            return null;
        }
        String configId = configIdOverride;
        if (configId == null || configId.isBlank()) {
            String roleOverride = getRoleStringParam(role, ctx, DEFAULT_CONFIG_PARAM);
            if (roleOverride != null && !roleOverride.isBlank()) {
                configId = roleOverride;
            }
        }
        if (configId != null && !configId.isBlank()) {
            return assetMap.getAssetMap().get(configId);
        }
        String roleId = role != null ? role.getRoleName() : null;
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwInteractionConfig.resolveForRole(roleId);
    }

    private ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                                  Ref<EntityStore> npcRef,
                                                  Role role,
                                                  InfoProvider infoProvider,
                                                  Store<EntityStore> store,
                                                  Player player,
                                                  InteractionContextSnapshot ctx) {
        InteractionEntry[] entries = config.getInteractions();
        for (int index = 0; index < entries.length; index++) {
            InteractionEntry entry = entries[index];
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            int cooldownSeconds = resolveCooldownSeconds(config, entry);
            String cooldownAlarmName = cooldownSeconds > 0
                    ? buildCooldownAlarmName(config, index)
                    : null;
            if (cooldownSeconds > 0
                    && (cooldownAlarmName == null || !isCooldownReady(npcRef, store, cooldownAlarmName))) {
                continue;
            }
            if (requirements.requirementsMet(entry, npcRef, role, infoProvider, store, player, ctx)) {
                return new ResolvedInteraction(entry, index, cooldownSeconds, cooldownAlarmName);
            }
        }
        return null;
    }

    private boolean applyInteraction(InteractionEntry entry,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     InfoProvider infoProvider,
                                     Store<EntityStore> store,
                                     Player player,
                                     InteractionContextSnapshot ctx) {
        if (entry instanceof CustomInteraction) {
            return effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof TameInteraction) {
            boolean applied = effects.applyStartTaming(npcRef, store, player);
            consumeHeldItem(player);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof FeedInteraction) {
            FeedInteraction feed = (FeedInteraction) entry;
            double healAmount = resolveFeedHeal(feed, role, ctx);
            boolean applied = effects.applyFeeding(npcRef, store, healAmount, player);
            consumeHeldItem(player);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof HarvestInteraction) {
            boolean applied = effects.applyStartHarvest(npcRef, role, store);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof MountInteraction) {
            boolean applied = effects.applyMount(npcRef, role, infoProvider, store);
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
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
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof BreedInteraction) {
            boolean applied = effects.applyStartBreeding();
            return applied | effects.applyCustomEffects(entry.getEffects(), npcRef, role, infoProvider, store, player);
        }
        return false;
    }

    private double resolveFeedHeal(FeedInteraction feed, Role role, InteractionContextSnapshot ctx) {
        if (feed == null) {
            return 0;
        }
        Double baseHeal = feed.getHeal();
        String heldItem = ctx != null ? ctx.activeItemId : null;
        if (heldItem != null && !heldItem.isBlank()) {
            ResolvedFeedItems resolved = resolveFeedItems(feed, role, ctx);
            FeedItem[] feedItems = resolved != null ? resolved.getFeedItems() : null;
            if (feedItems != null) {
                for (FeedItem feedItem : feedItems) {
                    if (feedItem == null) {
                        continue;
                    }
                    String itemId = feedItem.getItem();
                    if (itemId != null && itemId.equalsIgnoreCase(heldItem)) {
                        Double itemHeal = feedItem.getHeal();
                        return itemHeal != null ? itemHeal : (baseHeal != null ? baseHeal : 0);
                    }
                }
            }
        }
        return baseHeal != null ? baseHeal : 0;
    }

    static final class ResolvedFeedItems {
        private final String[] itemIds;
        private final FeedItem[] feedItems;
        private final boolean requiresItems;

        ResolvedFeedItems(String[] itemIds, FeedItem[] feedItems, boolean requiresItems) {
            this.itemIds = itemIds == null ? new String[0] : itemIds;
            this.feedItems = feedItems == null ? new FeedItem[0] : feedItems;
            this.requiresItems = requiresItems;
        }

        String[] getItemIds() {
            return itemIds;
        }

        FeedItem[] getFeedItems() {
            return feedItems;
        }

        boolean requiresItems() {
            return requiresItems;
        }
    }
    private boolean consumeHeldItem(Player player) {
        return removeHeldItemQuantity(player, 1);
    }

    boolean removeHeldItemQuantity(Player player, int quantity) {
        if (player == null) {
            return false;
        }
        if (quantity <= 0) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        byte slot = inventory.getActiveHotbarSlot();
        if (slot == Inventory.INACTIVE_SLOT_INDEX) {
            return false;
        }
        ItemContainer hotbar = inventory.getHotbar();
        ItemStack stack = hotbar != null ? hotbar.getItemStack(slot) : null;
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        int removeCount = Math.min(quantity, stack.getQuantity());
        if (removeCount <= 0) {
            return false;
        }
        hotbar.removeItemStackFromSlot((short) slot, removeCount);
        return true;
    }

    boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkTamedComponent component = store.getComponent(npcRef, type);
        return component != null && component.isTamed();
    }

    boolean isOwner(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        if (player == null) {
            return false;
        }
        UUID ownerId = resolveOwnerUuid(npcRef, store);
        return ownerId != null && ownerId.equals(getPlayerUuid(player));
    }

    boolean isAlarmReady(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        NPCEntity npc = resolveNpcEntity(npcRef, store);
        if (npc == null || alarmName == null || alarmName.isBlank()) {
            return false;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return false;
        }
        Alarm alarm = alarmStore.get(npc, alarmName);
        if (alarm == null) {
            return true;
        }
        if (!alarm.isSet()) {
            return true;
        }
        return alarm.hasPassed(resolveGameTime(store));
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

    boolean matchesHarvestContext(Role role,
                                  InfoProvider infoProvider,
                                  InteractionContextSnapshot ctx) {
        String context = hasHarvestContextOverride
                ? harvestContextOverride
                : getRoleStringParam(role, ctx, DEFAULT_HARVEST_CONTEXT_PARAM);
        return matchesInteractionContext(context, role, infoProvider, true);
    }

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
        Ref<EntityStore> playerRef = resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        return role.getStateSupport().hasContextualInteraction(playerRef, context);
    }

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
        String context = getRoleStringParam(role, ctx, paramName);
        return context != null && !context.isBlank() ? context : null;
    }

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

    boolean isPlayerCrouching(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        return matchesMovementState(statesForPlayer(role, infoProvider, store), "Crouching");
    }

    private MovementStates statesForPlayer(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        Ref<EntityStore> playerRef = resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        MovementStatesComponent component = store.getComponent(playerRef, MovementStatesComponent.getComponentType());
        return component != null ? component.getMovementStates() : null;
    }

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

    private String resolveAlarmName(AlarmRequirement requirement, Role role, InteractionContextSnapshot ctx) {
        if (requirement == null) {
            return null;
        }
        String paramName = requirement.getAlarmParam();
        if (paramName != null && !paramName.isBlank()) {
            String resolved = getRoleStringParam(role, ctx, paramName);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        String name = requirement.getName();
        return name != null && !name.isBlank() ? name : null;
    }

    boolean matchesAlarmState(AlarmRequirement requirement,
                              Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              Role role,
                              InteractionContextSnapshot ctx) {
        String alarmName = resolveAlarmName(requirement, role, ctx);
        if (alarmName == null || alarmName.isBlank()) {
            return false;
        }
        NPCEntity npc = resolveNpcEntity(npcRef, store);
        if (npc == null) {
            return false;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return false;
        }
        Alarm alarm = alarmStore.get(npc, alarmName);
        String state = requirement.getState() != null ? requirement.getState().trim().toLowerCase(Locale.ROOT) : "";
        if (alarm == null) {
            return "unset".equals(state);
        }
        Instant now = resolveGameTime(store);
        switch (state) {
            case "unset":
                return !alarm.isSet();
            case "passed":
                return alarm.isSet() && alarm.hasPassed(now);
            case "active":
                return alarm.isSet() && !alarm.hasPassed(now);
            default:
                return false;
        }
    }

    boolean matchesParamRequirement(ParamRequirement requirement, Role role) {
        if (requirement == null || requirement.getName() == null || requirement.getName().isBlank()) {
            return false;
        }
        String[] targets = requirement.getValues();
        if (targets == null || targets.length == 0) {
            return false;
        }
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return false;
        }
        ParamOperator operator = requirement.getOperator();
        TwInteractionConfig.MatchType matchType = requirement.getMatch();

        BooleanSupplier booleanSupplier;
        try {
            booleanSupplier = scope.getBooleanSupplier(requirement.getName());
        } catch (IllegalStateException ignored) {
            booleanSupplier = null;
        }

        DoubleSupplier numberSupplier;
        try {
            numberSupplier = scope.getNumberSupplier(requirement.getName());
        } catch (IllegalStateException ignored) {
            numberSupplier = null;
        }

        Supplier<String> stringSupplier;
        try {
            stringSupplier = scope.getStringSupplier(requirement.getName());
        } catch (IllegalStateException ignored) {
            stringSupplier = null;
        }

        boolean anyMatched = false;
        for (String target : targets) {
            if (target == null) {
                continue;
            }
            boolean matched = evaluateParamTarget(operator, target, booleanSupplier, numberSupplier, stringSupplier);
            if (matchType == TwInteractionConfig.MatchType.Any) {
                if (matched) {
                    return true;
                }
            } else {
                if (!matched) {
                    return false;
                }
            }
            anyMatched |= matched;
        }
        return anyMatched;
    }

    private Instant resolveGameTime(Store<EntityStore> store) {
        if (store == null) {
            return Instant.now();
        }
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        if (time == null) {
            return Instant.now();
        }
        return time.getGameTime();
    }

    private boolean evaluateParamTarget(ParamOperator operator,
                                        String target,
                                        BooleanSupplier booleanSupplier,
                                        DoubleSupplier numberSupplier,
                                        Supplier<String> stringSupplier) {
        if (target == null) {
            return false;
        }
        boolean targetIsBoolean = target.equalsIgnoreCase("true") || target.equalsIgnoreCase("false");
        if ((operator == ParamOperator.Equals || operator == ParamOperator.NotEquals) && targetIsBoolean && booleanSupplier != null) {
            boolean actual = booleanSupplier.getAsBoolean();
            boolean expected = Boolean.parseBoolean(target);
            return operator == ParamOperator.Equals ? actual == expected : actual != expected;
        }
        Double targetNumber = null;
        try {
            targetNumber = Double.parseDouble(target);
        } catch (NumberFormatException ignored) {
            targetNumber = null;
        }
        if (numberSupplier != null && targetNumber != null) {
            double actual = numberSupplier.getAsDouble();
            int compare = Double.compare(actual, targetNumber);
            return switch (operator) {
                case Equals -> compare == 0;
                case NotEquals -> compare != 0;
                case GreaterThan -> compare > 0;
                case GreaterThanOrEqual -> compare >= 0;
                case LessThan -> compare < 0;
                case LessThanOrEqual -> compare <= 0;
            };
        }
        if ((operator == ParamOperator.Equals || operator == ParamOperator.NotEquals) && stringSupplier != null) {
            String value = stringSupplier.get();
            boolean matches = value != null && value.equalsIgnoreCase(target);
            return operator == ParamOperator.Equals ? matches : !matches;
        }
        return false;
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

    ResolvedFeedItems resolveFeedItems(FeedInteraction interaction, Role role, InteractionContextSnapshot ctx) {
        if (interaction == null) {
            return new ResolvedFeedItems(new String[0], new FeedItem[0], false);
        }
        FeedItem[] paramItems = resolveFeedItemsFromParam(role, ctx, interaction.getItemsParam());
        if (paramItems != null && paramItems.length > 0) {
            String[] paramIds = InteractionItemParser.extractItemIds(paramItems);
            if (paramIds.length > 0) {
                return new ResolvedFeedItems(paramIds, paramItems, true);
            }
        }
        FeedItem[] explicitItems = interaction.getItemsInHand();
        String[] explicitIds = InteractionItemParser.extractItemIds(explicitItems);
        if (explicitIds.length > 0) {
            return new ResolvedFeedItems(explicitIds, explicitItems, true);
        }
        boolean useLovedItems = interaction.getUseLovedItems() == null || interaction.getUseLovedItems();
        if (useLovedItems) {
            return new ResolvedFeedItems(resolveLovedItems(role, ctx), new FeedItem[0], true);
        }
        return new ResolvedFeedItems(new String[0], new FeedItem[0], false);
    }

    private FeedItem[] resolveFeedItemsFromParam(Role role,
                                                 InteractionContextSnapshot ctx,
                                                 String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        String[] rawValues = getRoleStringArrayParam(role, ctx, paramName);
        if (rawValues == null || rawValues.length == 0) {
            return null;
        }
        if (rawValues.length == 1 && InteractionItemParser.looksLikeJsonArray(rawValues[0])) {
            FeedItem[] parsed = InteractionItemParser.parseFeedItemsFromJson(rawValues[0]);
            if (parsed != null && parsed.length > 0) {
                return parsed;
            }
            return null;
        }
        FeedItem[] items = InteractionItemParser.toFeedItems(rawValues);
        return items != null && items.length > 0 ? items : null;
    }

    String[] resolveLovedItems(Role role, InteractionContextSnapshot ctx) {
        if (hasLovedItemsOverride) {
            return lovedItemsOverride != null ? lovedItemsOverride : new String[0];
        }
        String[] items = getRoleStringArrayParam(role, ctx, DEFAULT_LOVED_ITEMS_PARAM);
        return items != null ? items : new String[0];
    }

    boolean resolveIsHarvestable(Role role, InteractionContextSnapshot ctx) {
        if (isHarvestableOverride != null) {
            return isHarvestableOverride;
        }
        return getRoleBooleanParam(role, ctx, DEFAULT_IS_HARVESTABLE_PARAM);
    }

    boolean resolveIsMountable(Role role, InteractionContextSnapshot ctx) {
        if (isMountableOverride != null) {
            return isMountableOverride;
        }
        return getRoleBooleanParam(role, ctx, DEFAULT_IS_MOUNTABLE_PARAM);
    }

    String getRoleStringParam(Role role, String paramName) {
        return paramResolver.getStringParam(role, null, paramName);
    }

    String getRoleStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getStringParam(role, ctx, paramName);
    }

    String[] getRoleStringArrayParam(Role role, String paramName) {
        return paramResolver.getStringArrayParam(role, null, paramName);
    }

    String[] getRoleStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getStringArrayParam(role, ctx, paramName);
    }

    private boolean getRoleBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getBooleanParam(role, ctx, paramName);
    }

    double getRoleNumberParam(Role role, String paramName, double defaultValue) {
        return paramResolver.getNumberParam(role, null, paramName, defaultValue);
    }

    double getRoleNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        return paramResolver.getNumberParam(role, ctx, paramName, defaultValue);
    }

    private StdScope getRoleScope(Role role) {
        return paramResolver.resolveRoleScope(role);
    }

    Ref<EntityStore> resolveInteractionTarget(Role role, InfoProvider infoProvider) {
        if (role != null && role.getStateSupport() != null) {
            Ref<EntityStore> target = role.getStateSupport().getInteractionIterationTarget();
            if (target != null && target.isValid()) {
                return target;
            }
        }
        if (infoProvider != null && infoProvider.hasPosition()) {
            IPositionProvider positionProvider = infoProvider.getPositionProvider();
            if (positionProvider instanceof EntityPositionProvider) {
                Ref<EntityStore> target = ((EntityPositionProvider) positionProvider).getTarget();
                if (target != null && target.isValid()) {
                    return target;
                }
            }
        }
        return null;
    }

    void logUnsupported(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(java.util.logging.Level.WARNING).log(message);
        }
    }

    void logDebug(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.FINE).log(message);
        }
    }

    private String describeHeldItem(InteractionContextSnapshot ctx) {
        ItemStack stack = ctx != null ? ctx.activeItem : null;
        if (stack == null || stack.isEmpty()) {
            return "<empty>";
        }
        String itemId = stack.getItemId();
        return itemId != null ? itemId : "<unknown>";
    }

    private String buildNoMatchSummary(TwInteractionConfig config,
                                       Ref<EntityStore> npcRef,
                                       Role role,
                                       InfoProvider infoProvider,
                                       Store<EntityStore> store,
                                       Player player,
                                       InteractionContextSnapshot ctx) {
        String roleName = role != null ? role.getRoleName() : "<null>";
        boolean tamed = isTamed(npcRef, store);
        boolean owner = isOwner(npcRef, store, player);
        boolean hasLoved = itemRequirements.isHeldItemInList(resolveLovedItems(role, ctx), ctx);
        boolean isHarvestable = resolveIsHarvestable(role, ctx);
        boolean harvestReady = isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM);
        boolean harvestContext = matchesHarvestContext(role, infoProvider, ctx);
        boolean isMountable = resolveIsMountable(role, ctx);
        boolean crouching = isPlayerCrouching(role, infoProvider, store);
        return String.format(
                "TameworkInteract: no interactions matched (role=%s config=%s). " +
                        "tamed=%s owner=%s held=%s lovedMatch=%s isHarvestable=%s harvestReady=%s harvestContext=%s " +
                        "isMountable=%s crouching=%s",
                roleName,
                config.getId(),
                tamed,
                owner,
                describeHeldItem(ctx),
                hasLoved,
                isHarvestable,
                harvestReady,
                harvestContext,
                isMountable,
                crouching
        );
    }

    private void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Player player) {
        if (player == null) {
            return;
        }
        UUID ownerUuid = resolveOwnerUuid(npcRef, store);
        UUID playerUuid = getPlayerUuid(player);
        if (ownerUuid == null || playerUuid == null || ownerUuid.equals(playerUuid)) {
            return;
        }
        String npcName = resolveNpcName(resolveNpcEntity(npcRef, store));
        String ownerName = resolveOwnerName(npcRef, store);
        OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "interact with");
    }

    private int resolveCooldownSeconds(TwInteractionConfig config, InteractionEntry entry) {
        if (entry != null && entry.getCooldownSeconds() != null) {
            return Math.max(0, entry.getCooldownSeconds());
        }
        if (config != null && config.getCooldowns() != null
                && config.getCooldowns().getInteractionSeconds() != null) {
            return Math.max(0, config.getCooldowns().getInteractionSeconds());
        }
        return 0;
    }

    private String buildCooldownAlarmName(TwInteractionConfig config, int index) {
        String configId = config != null ? config.getId() : null;
        String safeConfigId = sanitizeAlarmSegment(configId);
        return DEFAULT_COOLDOWN_ALARM_PREFIX + "_" + safeConfigId + "_" + index;
    }

    private String sanitizeAlarmSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        return safe.toString();
    }

    private void applyInteractionCooldown(ResolvedInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Store<EntityStore> store) {
        if (interaction == null || interaction.cooldownSeconds <= 0) {
            return;
        }
        if (interaction.cooldownAlarmName == null || interaction.cooldownAlarmName.isBlank()) {
            return;
        }
        NPCEntity npc = resolveNpcEntity(npcRef, store);
        if (npc == null) {
            return;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return;
        }
        Alarm alarm = alarmStore.get(npc, interaction.cooldownAlarmName);
        if (alarm == null) {
            return;
        }
        alarm.set(npcRef, Instant.now().plusSeconds(interaction.cooldownSeconds), store);
    }

    private static final class ResolvedInteraction {
        private final InteractionEntry entry;
        private final int index;
        private final int cooldownSeconds;
        private final String cooldownAlarmName;

        private ResolvedInteraction(InteractionEntry entry,
                                    int index,
                                    int cooldownSeconds,
                                    String cooldownAlarmName) {
            this.entry = entry;
            this.index = index;
            this.cooldownSeconds = cooldownSeconds;
            this.cooldownAlarmName = cooldownAlarmName;
        }
    }

    private boolean isCooldownReady(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        NPCEntity npc = resolveNpcEntity(npcRef, store);
        if (npc == null || alarmName == null || alarmName.isBlank()) {
            return false;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return false;
        }
        Alarm alarm = alarmStore.get(npc, alarmName);
        if (alarm == null) {
            return true;
        }
        if (!alarm.isSet()) {
            return true;
        }
        return alarm.hasPassed(Instant.now());
    }
}
