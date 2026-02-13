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
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
    private final StdScope globalScopeSnapshot;
    private final StdScope execScopeSnapshot;
    private final StdScope sensorScopeSnapshot;
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
        this.globalScopeSnapshot = globalSnapshot;
        this.execScopeSnapshot = execSnapshot;
        this.sensorScopeSnapshot = sensorSnapshot;
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
        String roleName = role != null ? role.getRoleName() : "<null>";
        String roleOverride = getRoleStringParam(role, DEFAULT_CONFIG_PARAM);
        logDebug(String.format(
                "TameworkInteract: role=%s configOverride=%s roleParam=%s heldItem=%s",
                roleName,
                configIdOverride,
                roleOverride,
                describeHeldItem(player)
        ));
        TwInteractionConfig config = resolveConfig(role);
        if (config == null || !config.isEnabled()) {
            logDebug(String.format(
                    "TameworkInteract: no config resolved or config disabled (role=%s).",
                    roleName
            ));
            return false;
        }
        ResolvedInteraction interaction = selectInteraction(config, npcRef, role, infoProvider, store, player);
        if (interaction == null) {
            maybeNotifyOwnerDenied(npcRef, store, player);
            logDebug(buildNoMatchSummary(config, npcRef, role, infoProvider, store, player));
            return false;
        }
        boolean applied = applyInteraction(interaction.entry, npcRef, role, infoProvider, store, player);
        if (applied) {
            applyInteractionCooldown(interaction, npcRef, store);
        }
        return applied;
    }

    private TwInteractionConfig resolveConfig(Role role) {
        DefaultAssetMap<String, TwInteractionConfig> assetMap = TwInteractionConfig.getAssetMap();
        if (assetMap == null) {
            return null;
        }
        String configId = configIdOverride;
        if (configId == null || configId.isBlank()) {
            String roleOverride = getRoleStringParam(role, DEFAULT_CONFIG_PARAM);
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
        for (TwInteractionConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate != null && candidate.isEnabled() && candidate.matchesRole(roleId)) {
                return candidate;
            }
        }
        return null;
    }

    private ResolvedInteraction selectInteraction(TwInteractionConfig config,
                                                  Ref<EntityStore> npcRef,
                                                  Role role,
                                                  InfoProvider infoProvider,
                                                  Store<EntityStore> store,
                                                  Player player) {
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
            if (requirements.requirementsMet(entry, npcRef, role, infoProvider, store, player)) {
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
                                     Player player) {
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
            double healAmount = resolveFeedHeal(feed, player);
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

    private double resolveFeedHeal(FeedInteraction feed, Player player) {
        if (feed == null) {
            return 0;
        }
        Double baseHeal = feed.getHeal();
        String heldItem = getHeldItemId(player);
        if (heldItem != null && !heldItem.isBlank()) {
            FeedItem[] feedItems = feed.getItemsInHand();
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
    private static final class EquippedSlotSelection {
        private final boolean includeArmor;
        private final boolean includeUtility;
        private final boolean[] armorSlots;

        private EquippedSlotSelection(boolean includeArmor, boolean includeUtility, boolean[] armorSlots) {
            this.includeArmor = includeArmor;
            this.includeUtility = includeUtility;
            this.armorSlots = armorSlots;
        }

        private boolean hasAny() {
            return includeArmor || includeUtility;
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

    CombinedItemContainer resolveInventoryContainer(Player player) {
        if (player == null) {
            return null;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        return inventory.getCombinedBackpackStorageHotbar();
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

    boolean isHeldItemInList(String[] items, Player player) {
        if (player == null || items == null || items.length == 0) {
            return false;
        }
        ItemStack stack = getActiveItem(player);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String itemId = stack.getItemId();
        if (itemId == null) {
            return false;
        }
        return Arrays.stream(items).anyMatch(itemId::equalsIgnoreCase);
    }

    boolean isHeldItemInList(String[] items, int quantity, Player player) {
        if (!isHeldItemInList(items, player)) {
            return false;
        }
        ItemStack stack = getActiveItem(player);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getQuantity() >= quantity;
    }

    private String getHeldItemId(Player player) {
        if (player == null) {
            return null;
        }
        ItemStack stack = getActiveItem(player);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getItemId();
    }

    private int resolveRequiredQuantity(Integer quantity) {
        if (quantity == null) {
            return 1;
        }
        return Math.max(1, quantity);
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

    boolean matchesItemsInHand(ItemsInHandRequirement requirement, Role role, Player player) {
        if (requirement == null) {
            return false;
        }
        String[] items = resolveItemsInHand(requirement, role);
        int quantity = resolveRequiredQuantity(requirement.getQuantity());
        return isHeldItemInList(items, quantity, player);
    }

    boolean matchesItemsInInventory(ItemsInInventoryRequirement requirement, Role role, Player player) {
        if (requirement == null || player == null) {
            return false;
        }
        String[] items = resolveItemsInInventory(requirement, role);
        if (items == null || items.length == 0) {
            return false;
        }
        int quantity = resolveRequiredQuantity(requirement.getQuantity());
        CombinedItemContainer container = resolveInventoryContainer(player);
        if (container == null) {
            return false;
        }
        Set<String> itemSet = normalizeItemSet(items);
        if (itemSet.isEmpty()) {
            return false;
        }
        for (String itemId : itemSet) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            if (countItemInContainer(container, itemId) >= quantity) {
                return true;
            }
        }
        return false;
    }

    boolean matchesItemsEquipped(ItemsEquippedRequirement requirement, Player player) {
        if (requirement == null || player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        EquippedSlotSelection selection = resolveEquippedSlots(requirement.getSlots());
        if (!selection.hasAny()) {
            return false;
        }
        String[] items = requirement.getItems();
        if (items == null || items.length == 0) {
            return hasAnyItemEquipped(inventory, selection);
        }
        Set<String> itemSet = normalizeItemSet(items);
        if (itemSet.isEmpty()) {
            return false;
        }
        if (selection.includeArmor) {
            ItemContainer armor = inventory.getArmor();
            if (armor != null && containsItemInArmor(armor, selection.armorSlots, itemSet)) {
                return true;
            }
        }
        if (selection.includeUtility) {
            ItemContainer utility = inventory.getUtility();
            if (utility != null && containsItemInContainer(utility, itemSet)) {
                return true;
            }
        }
        return false;
    }

    boolean matchesHarvestContext(Role role,
                                  InfoProvider infoProvider) {
        String context = hasHarvestContextOverride ? harvestContextOverride : getRoleStringParam(role, DEFAULT_HARVEST_CONTEXT_PARAM);
        return matchesInteractionContext(context, role, infoProvider, true);
    }

    boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                      Role role,
                                      InfoProvider infoProvider) {
        if (requirement == null) {
            return false;
        }
        String context = requirement.getContext();
        if ((context == null || context.isBlank()) && requirement.getParam() != null) {
            context = getRoleStringParam(role, requirement.getParam());
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

    boolean matchesAlarmState(AlarmRequirement requirement,
                                      Ref<EntityStore> npcRef,
                                      Store<EntityStore> store) {
        if (requirement == null || requirement.getName() == null || requirement.getName().isBlank()) {
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
        Alarm alarm = alarmStore.get(npc, requirement.getName());
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

    String[] resolveItemsInHand(ItemsInHandRequirement requirement, Role role) {
        if (requirement == null) {
            return new String[0];
        }
        String[] paramItems = null;
        if (requirement.getParam() != null && !requirement.getParam().isBlank()) {
            paramItems = getRoleStringArrayParam(role, requirement.getParam());
        }
        return mergeArrays(requirement.getItems(), paramItems);
    }

    String[] resolveItemsInInventory(ItemsInInventoryRequirement requirement, Role role) {
        if (requirement == null) {
            return new String[0];
        }
        String[] paramItems = null;
        if (requirement.getParam() != null && !requirement.getParam().isBlank()) {
            paramItems = getRoleStringArrayParam(role, requirement.getParam());
        }
        return mergeArrays(requirement.getItems(), paramItems);
    }

    String[] resolveLovedItems(Role role) {
        if (hasLovedItemsOverride) {
            return lovedItemsOverride != null ? lovedItemsOverride : new String[0];
        }
        String[] items = getRoleStringArrayParam(role, DEFAULT_LOVED_ITEMS_PARAM);
        return items != null ? items : new String[0];
    }

    boolean resolveIsHarvestable(Role role) {
        if (isHarvestableOverride != null) {
            return isHarvestableOverride;
        }
        return getRoleBooleanParam(role, DEFAULT_IS_HARVESTABLE_PARAM);
    }

    boolean resolveIsMountable(Role role) {
        if (isMountableOverride != null) {
            return isMountableOverride;
        }
        return getRoleBooleanParam(role, DEFAULT_IS_MOUNTABLE_PARAM);
    }

    EquippedSlotSelection resolveEquippedSlots(String[] slots) {
        boolean[] armorSlots = new boolean[ItemArmorSlot.VALUES.length];
        if (slots == null || slots.length == 0) {
            Arrays.fill(armorSlots, true);
            return new EquippedSlotSelection(true, true, armorSlots);
        }
        boolean includeArmor = false;
        boolean includeUtility = false;
        for (String slot : slots) {
            if (slot == null || slot.isBlank()) {
                continue;
            }
            String normalized = slot.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "head":
                    armorSlots[ItemArmorSlot.Head.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "chest":
                    armorSlots[ItemArmorSlot.Chest.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "hands":
                    armorSlots[ItemArmorSlot.Hands.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "legs":
                    armorSlots[ItemArmorSlot.Legs.ordinal()] = true;
                    includeArmor = true;
                    break;
                case "armor":
                case "equipped":
                    Arrays.fill(armorSlots, true);
                    includeArmor = true;
                    break;
                case "utility":
                case "accessory":
                case "accessories":
                    includeUtility = true;
                    break;
                default:
                    break;
            }
        }
        return new EquippedSlotSelection(includeArmor, includeUtility, armorSlots);
    }

    boolean containsItemInArmor(ItemContainer armor, boolean[] slots, Set<String> items) {
        if (armor == null || slots == null || items == null || items.isEmpty()) {
            return false;
        }
        int max = Math.min(slots.length, armor.getCapacity());
        for (short i = 0; i < max; i++) {
            if (!slots[i]) {
                continue;
            }
            ItemStack stack = armor.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId != null && items.contains(itemId.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    boolean hasAnyItemEquipped(Inventory inventory, EquippedSlotSelection selection) {
        if (inventory == null || selection == null || !selection.hasAny()) {
            return false;
        }
        if (selection.includeArmor) {
            ItemContainer armor = inventory.getArmor();
            if (armor != null && hasAnyItemInArmor(armor, selection.armorSlots)) {
                return true;
            }
        }
        if (selection.includeUtility) {
            ItemContainer utility = inventory.getUtility();
            if (utility != null && hasAnyItemInContainer(utility)) {
                return true;
            }
        }
        return false;
    }

    boolean hasAnyItemInArmor(ItemContainer armor, boolean[] slots) {
        if (armor == null || slots == null) {
            return false;
        }
        int max = Math.min(slots.length, armor.getCapacity());
        for (short i = 0; i < max; i++) {
            if (!slots[i]) {
                continue;
            }
            ItemStack stack = armor.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    boolean hasAnyItemInContainer(ItemContainer container) {
        if (container == null) {
            return false;
        }
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    boolean containsItemInContainer(ItemContainer container, Set<String> items) {
        if (container == null || items == null || items.isEmpty()) {
            return false;
        }
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId != null && items.contains(itemId.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    int countItemInContainer(CombinedItemContainer container, String itemId) {
        if (container == null || itemId == null || itemId.isBlank()) {
            return 0;
        }
        short capacity = container.getCapacity();
        int total = 0;
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (itemId.equalsIgnoreCase(stack.getItemId())) {
                total += stack.getQuantity();
            }
        }
        return total;
    }

    Set<String> normalizeItemSet(String[] items) {
        Set<String> set = new HashSet<>();
        if (items == null) {
            return set;
        }
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            set.add(item.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    String[] mergeArrays(String[] primary, String[] secondary) {
        String[] first = primary == null ? new String[0] : primary;
        String[] second = secondary == null ? new String[0] : secondary;
        if (first.length == 0) {
            return second;
        }
        if (second.length == 0) {
            return first;
        }
        String[] merged = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, merged, first.length, second.length);
        return merged;
    }

    String getRoleStringParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        for (StdScope scope : orderedScopes(getRoleScope(role))) {
            String value = getStringFromScope(scope, paramName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    String[] getRoleStringArrayParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        for (StdScope scope : orderedScopes(getRoleScope(role))) {
            String[] values = getStringArrayFromScope(scope, paramName);
            if (values != null && values.length > 0) {
                return values;
            }
        }
        return null;
    }

    private boolean getRoleBooleanParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return false;
        }
        for (StdScope scope : orderedScopes(getRoleScope(role))) {
            Boolean value = getBooleanFromScope(scope, paramName);
            if (value != null) {
                return value;
            }
        }
        return false;
    }

    double getRoleNumberParam(Role role, String paramName, double defaultValue) {
        if (paramName == null || paramName.isBlank()) {
            return defaultValue;
        }
        for (StdScope scope : orderedScopes(getRoleScope(role))) {
            Double value = getNumberFromScope(scope, paramName);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private StdScope getRoleScope(Role role) {
        if (role == null || role.getEntitySupport() == null) {
            return null;
        }
        return role.getEntitySupport().getSensorScope();
    }

    private StdScope[] orderedScopes(StdScope primary) {
        StdScope[] scopes = new StdScope[4];
        int count = 0;
        if (primary != null) {
            scopes[count++] = primary;
        }
        if (globalScopeSnapshot != null && globalScopeSnapshot != primary) {
            scopes[count++] = globalScopeSnapshot;
        }
        if (execScopeSnapshot != null && execScopeSnapshot != primary) {
            scopes[count++] = execScopeSnapshot;
        }
        if (sensorScopeSnapshot != null
                && sensorScopeSnapshot != primary
                && sensorScopeSnapshot != globalScopeSnapshot
                && sensorScopeSnapshot != execScopeSnapshot) {
            scopes[count++] = sensorScopeSnapshot;
        }
        return count == scopes.length ? scopes : Arrays.copyOf(scopes, count);
    }

    private String getStringFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        Supplier<String> supplier;
        try {
            supplier = scope.getStringSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return supplier != null ? supplier.get() : null;
    }

    private String[] getStringArrayFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        Supplier<String[]> arraySupplier;
        try {
            arraySupplier = scope.getStringArraySupplier(paramName);
        } catch (IllegalStateException ignored) {
            arraySupplier = null;
        }
        if (arraySupplier != null) {
            String[] values = arraySupplier.get();
            if (values != null && values.length > 0) {
                return values;
            }
        }
        Supplier<String> stringSupplier;
        try {
            stringSupplier = scope.getStringSupplier(paramName);
        } catch (IllegalStateException ignored) {
            stringSupplier = null;
        }
        if (stringSupplier == null) {
            return null;
        }
        String value = stringSupplier.get();
        if (value == null || value.isBlank()) {
            return null;
        }
        return new String[] { value };
    }

    private Boolean getBooleanFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        BooleanSupplier supplier;
        try {
            supplier = scope.getBooleanSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return supplier != null ? supplier.getAsBoolean() : null;
    }

    private Double getNumberFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        DoubleSupplier supplier;
        try {
            supplier = scope.getNumberSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return supplier != null ? supplier.getAsDouble() : null;
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
            instance.getLogger().at(Level.WARNING).log(message);
        }
    }

    private String describeHeldItem(Player player) {
        ItemStack stack = getActiveItem(player);
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
                                       Player player) {
        String roleName = role != null ? role.getRoleName() : "<null>";
        boolean tamed = isTamed(npcRef, store);
        boolean owner = isOwner(npcRef, store, player);
        boolean hasLoved = isHeldItemInList(resolveLovedItems(role), player);
        boolean isHarvestable = resolveIsHarvestable(role);
        boolean harvestReady = isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM);
        boolean harvestContext = matchesHarvestContext(role, infoProvider);
        boolean isMountable = resolveIsMountable(role);
        boolean crouching = isPlayerCrouching(role, infoProvider, store);
        return String.format(
                "TameworkInteract: no interactions matched (role=%s config=%s). " +
                        "tamed=%s owner=%s held=%s lovedMatch=%s isHarvestable=%s harvestReady=%s harvestContext=%s " +
                        "isMountable=%s crouching=%s",
                roleName,
                config.getId(),
                tamed,
                owner,
                describeHeldItem(player),
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
