package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionType;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Requirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementGroup;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementSource;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementType;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.sensorinfo.EntityPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Prototype action that executes a TwInteractionConfig-driven interaction flow.
 */
public final class ActionTameworkInteract extends TameworkActionBase {
    private static final String DEFAULT_CONFIG_PARAM = "InteractionConfigId";
    private static final String DEFAULT_LOVED_ITEMS_PARAM = "LovedItems";
    private static final String DEFAULT_IS_HARVESTABLE_PARAM = "IsHarvestable";
    private static final String DEFAULT_IS_MOUNTABLE_PARAM = "IsMountable";
    private static final String DEFAULT_HARVEST_CONTEXT_PARAM = "HarvestInteractionContext";
    private static final String DEFAULT_HARVEST_ALARM = "Harvest_Ready";

    private final String configIdOverride;

    public ActionTameworkInteract(BuilderActionTameworkInteract builder, BuilderSupport support) {
        super(builder);
        this.configIdOverride = builder.getConfigId();
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid();
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        TwInteractionConfig config = resolveConfig(role);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        InteractionEntry entry = selectInteraction(config, npcRef, role, infoProvider, store, player);
        if (entry == null) {
            return false;
        }
        return applyInteraction(entry, npcRef, role, infoProvider, store, player);
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

    private InteractionEntry selectInteraction(TwInteractionConfig config,
                                               Ref<EntityStore> npcRef,
                                               Role role,
                                               InfoProvider infoProvider,
                                               Store<EntityStore> store,
                                               Player player) {
        for (InteractionEntry entry : config.getInteractions()) {
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            if (requirementsMet(entry, npcRef, role, infoProvider, store, player)) {
                return entry;
            }
        }
        return null;
    }

    private boolean requirementsMet(InteractionEntry entry,
                                    Ref<EntityStore> npcRef,
                                    Role role,
                                    InfoProvider infoProvider,
                                    Store<EntityStore> store,
                                    Player player) {
        RequirementGroup requires = entry.getRequires();
        if (requires == null) {
            return entry.useDefaults() && checkDefaultRequirements(entry.getType(), npcRef, role, infoProvider, store, player);
        }
        return evaluateRequirementGroup(requires, npcRef, role, infoProvider, store, player);
    }

    private boolean evaluateRequirementGroup(RequirementGroup group,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player) {
        Requirement[] all = group.getAll();
        for (Requirement requirement : all) {
            if (!evaluateRequirement(requirement, npcRef, role, infoProvider, store, player)) {
                return false;
            }
        }
        Requirement[] any = group.getAny();
        if (any.length == 0) {
            return true;
        }
        for (Requirement requirement : any) {
            if (evaluateRequirement(requirement, npcRef, role, infoProvider, store, player)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateRequirement(Requirement requirement,
                                        Ref<EntityStore> npcRef,
                                        Role role,
                                        InfoProvider infoProvider,
                                        Store<EntityStore> store,
                                        Player player) {
        if (requirement == null || requirement.getType() == null) {
            return false;
        }
        switch (requirement.getType()) {
            case NpcIsTamed:
                return isTamed(npcRef, store);
            case NpcNotTamed:
                return !isTamed(npcRef, store);
            case PlayerIsOwner:
                return isOwner(npcRef, store, player);
            case LovedItems:
                return isHeldItemInList(
                        resolveItemsForRequirement(requirement, role, DEFAULT_LOVED_ITEMS_PARAM),
                        player
                );
            case Items:
                return isHeldItemInList(resolveItemsForRequirement(requirement, role, null), player);
            case IsHarvestable:
                return getRoleBooleanParam(role, resolveParamName(requirement, DEFAULT_IS_HARVESTABLE_PARAM));
            case IsMountable:
                return getRoleBooleanParam(role, resolveParamName(requirement, DEFAULT_IS_MOUNTABLE_PARAM));
            case HarvestAlarmReady:
                return isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM);
            case HarvestInteractionContext:
                return matchesHarvestContext(requirement, npcRef, role, infoProvider, store);
            case PlayerMovementState:
                return matchesMovementState(requirement, role, infoProvider, store, player);
            case AlarmState:
                return matchesAlarmState(requirement, npcRef, store);
            case CustomParamEquals:
                return matchesCustomParam(requirement, role);
            case NpcState:
                return matchesNpcState(requirement, role);
            default:
                return false;
        }
    }

    private boolean checkDefaultRequirements(InteractionType type,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case Taming:
                return !isTamed(npcRef, store)
                        && isHeldItemInList(getRoleStringArrayParam(role, DEFAULT_LOVED_ITEMS_PARAM), player);
            case Feeding:
                return isTamed(npcRef, store)
                        && isHeldItemInList(getRoleStringArrayParam(role, DEFAULT_LOVED_ITEMS_PARAM), player);
            case Harvesting:
                return isTamed(npcRef, store)
                        && getRoleBooleanParam(role, DEFAULT_IS_HARVESTABLE_PARAM)
                        && isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM)
                        && matchesHarvestContext(null, npcRef, role, infoProvider, store);
            case Mounting:
                return isTamed(npcRef, store)
                        && isOwner(npcRef, store, player)
                        && getRoleBooleanParam(role, DEFAULT_IS_MOUNTABLE_PARAM)
                        && isPlayerCrouching(role, infoProvider, store, player);
            case ModeToggle:
                return isTamed(npcRef, store) && isOwner(npcRef, store, player);
            default:
                return false;
        }
    }

    private boolean applyInteraction(InteractionEntry entry,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     InfoProvider infoProvider,
                                     Store<EntityStore> store,
                                     Player player) {
        Effects effects = entry.getEffects();
        if (effects == null && entry.useDefaults()) {
            return applyDefaultEffects(entry.getType(), npcRef, role, infoProvider, store, player);
        }
        boolean applied = false;
        if (effects != null) {
            if (Boolean.TRUE.equals(effects.getStartTaming())) {
                applied |= applyStartTaming(npcRef, store, player);
            }
            if (Boolean.TRUE.equals(effects.getApplyFeeding())) {
                applied |= applyFeeding(npcRef, store, player);
            }
            if (Boolean.TRUE.equals(effects.getStartHarvest())) {
                applied |= applyStartHarvest(npcRef, role, store);
            }
            if (Boolean.TRUE.equals(effects.getConsumeItem())) {
                applied |= consumeHeldItem(player);
            }
            if (Boolean.TRUE.equals(effects.getMount())) {
                logUnsupported("Mount effect not yet implemented.");
            }
            if (Boolean.TRUE.equals(effects.getToggleMode())) {
                logUnsupported("ToggleMode effect not yet implemented.");
            }
        }
        return applied;
    }

    private boolean applyDefaultEffects(InteractionType type,
                                        Ref<EntityStore> npcRef,
                                        Role role,
                                        InfoProvider infoProvider,
                                        Store<EntityStore> store,
                                        Player player) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case Taming:
                boolean tamed = applyStartTaming(npcRef, store, player);
                consumeHeldItem(player);
                return tamed;
            case Feeding:
                boolean fed = applyFeeding(npcRef, store, player);
                consumeHeldItem(player);
                return fed;
            case Harvesting:
                return applyStartHarvest(npcRef, role, store);
            case Mounting:
                logUnsupported("Mounting default effect not yet implemented.");
                return false;
            case ModeToggle:
                logUnsupported("ModeToggle default effect not yet implemented.");
                return false;
            default:
                return false;
        }
    }

    private boolean applyStartTaming(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(npcRef, tamedType, new TameworkTamedComponent(true));
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null && player != null) {
            PlayerRef ref = player.getPlayerRef();
            UUID ownerId = player.getUuid();
            String ownerName = ref != null ? ref.getUsername() : null;
            store.putComponent(npcRef, ownerType, new TameworkOwnerComponent(ownerId, ownerName));
        }
        return true;
    }

    private boolean applyFeeding(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        // Prototype: consume item only. Hook in heal/happiness logic later.
        return true;
    }

    private boolean applyStartHarvest(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        role.getStateSupport().setState(npcRef, "$Harvest", "", store);
        return true;
    }

    private boolean consumeHeldItem(Player player) {
        if (player == null) {
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
        hotbar.removeItemStackFromSlot(slot, 1);
        return true;
    }

    private boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkTamedComponent component = store.getComponent(npcRef, type);
        return component != null && component.isTamed();
    }

    private boolean isOwner(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        if (player == null) {
            return false;
        }
        UUID ownerId = resolveOwnerUuid(npcRef, store);
        return ownerId != null && ownerId.equals(getPlayerUuid(player));
    }

    private boolean isHeldItemInList(String[] items, Player player) {
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

    private boolean isAlarmReady(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
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

    private boolean matchesHarvestContext(Requirement requirement,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store) {
        String context = requirement != null ? requirement.getContext() : null;
        if (context == null || context.isBlank()) {
            context = getRoleStringParam(role, DEFAULT_HARVEST_CONTEXT_PARAM);
        }
        if (context == null || context.isBlank()) {
            return true;
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

    private boolean matchesMovementState(Requirement requirement,
                                         Role role,
                                         InfoProvider infoProvider,
                                         Store<EntityStore> store,
                                         Player player) {
        if (requirement == null || requirement.getState() == null || requirement.getState().isBlank()) {
            return false;
        }
        Ref<EntityStore> playerRef = resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        MovementStatesComponent component = store.getComponent(playerRef, MovementStatesComponent.getComponentType());
        if (component == null) {
            return false;
        }
        MovementStates states = component.getMovementStates();
        if (states == null) {
            return false;
        }
        return matchesMovementState(states, requirement.getState());
    }

    private boolean isPlayerCrouching(Role role, InfoProvider infoProvider, Store<EntityStore> store, Player player) {
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

    private boolean matchesMovementState(MovementStates states, String state) {
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

    private boolean matchesAlarmState(Requirement requirement,
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
        switch (state) {
            case "unset":
                return !alarm.isSet();
            case "passed":
                return alarm.isSet() && alarm.hasPassed(Instant.now());
            case "active":
                return alarm.isSet() && !alarm.hasPassed(Instant.now());
            default:
                return false;
        }
    }

    private boolean matchesCustomParam(Requirement requirement, Role role) {
        if (requirement == null || requirement.getName() == null || requirement.getName().isBlank()) {
            return false;
        }
        String target = requirement.getState();
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return false;
        }
        if (target != null && (target.equalsIgnoreCase("true") || target.equalsIgnoreCase("false"))) {
            BooleanSupplier supplier = scope.getBooleanSupplier(requirement.getName());
            if (supplier != null) {
                return supplier.getAsBoolean() == Boolean.parseBoolean(target);
            }
        }
        DoubleSupplier numberSupplier = scope.getNumberSupplier(requirement.getName());
        if (numberSupplier != null && target != null) {
            try {
                double value = Double.parseDouble(target);
                return Double.compare(numberSupplier.getAsDouble(), value) == 0;
            } catch (NumberFormatException ignored) {
                // fallthrough to string compare
            }
        }
        Supplier<String> stringSupplier = scope.getStringSupplier(requirement.getName());
        if (stringSupplier != null) {
            String value = stringSupplier.get();
            return value != null && value.equalsIgnoreCase(target);
        }
        return false;
    }

    private boolean matchesNpcState(Requirement requirement, Role role) {
        if (requirement == null || requirement.getState() == null || requirement.getState().isBlank()) {
            return false;
        }
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String state = requirement.getState();
        if (state.contains(".")) {
            String[] parts = state.split("\\.", 2);
            return role.getStateSupport().inState(parts[0], parts[1]);
        }
        return role.getStateSupport().inState(state, "");
    }

    private String resolveParamName(Requirement requirement, String fallback) {
        if (requirement == null) {
            return fallback;
        }
        String param = requirement.getParam();
        if (param != null && !param.isBlank()) {
            return param;
        }
        return fallback;
    }

    private String[] resolveItemsForRequirement(Requirement requirement, Role role, String fallbackParam) {
        if (requirement == null) {
            return new String[0];
        }
        if (requirement.getSource() == RequirementSource.RoleParam) {
            String paramName = resolveParamName(requirement, fallbackParam);
            if (paramName != null) {
                String[] items = getRoleStringArrayParam(role, paramName);
                if (items != null && items.length > 0) {
                    return items;
                }
            }
        }
        return requirement.getItems();
    }

    private String getRoleStringParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return null;
        }
        Supplier<String> supplier = scope.getStringSupplier(paramName);
        if (supplier == null) {
            return null;
        }
        return supplier.get();
    }

    private String[] getRoleStringArrayParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return null;
        }
        Supplier<String[]> supplier = scope.getStringArraySupplier(paramName);
        if (supplier == null) {
            return null;
        }
        return supplier.get();
    }

    private boolean getRoleBooleanParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return false;
        }
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return false;
        }
        BooleanSupplier supplier = scope.getBooleanSupplier(paramName);
        return supplier != null && supplier.getAsBoolean();
    }

    private StdScope getRoleScope(Role role) {
        if (role == null || role.getEntitySupport() == null) {
            return null;
        }
        return role.getEntitySupport().getSensorScope();
    }

    private Ref<EntityStore> resolveInteractionTarget(Role role, InfoProvider infoProvider) {
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

    private void logUnsupported(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(java.util.logging.Level.WARNING).log(message);
        }
    }
}
