package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AlarmRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HookEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionContextRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsEquippedRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HarvestInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeStep;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeCycleInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MountInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.MovementStateRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamOperator;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementBucket;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RequirementGroup;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StringRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
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
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

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
    private static final String HEALTH_STAT_ID = "Health";
    private static final ModeStep[] DEFAULT_MODE_CYCLE = new ModeStep[] {
            new ModeStep("Hold", null, null),
            new ModeStep("Idle", null, null),
            new ModeStep("Defend", null, null)
    };

    private final String configIdOverride;
    private final boolean hasLovedItemsOverride;
    private final String[] lovedItemsOverride;
    private final Boolean isMountableOverride;
    private final Boolean isHarvestableOverride;
    private final boolean hasHarvestContextOverride;
    private final String harvestContextOverride;

    public ActionTameworkInteract(BuilderActionTameworkInteract builder, BuilderSupport support) {
        super(builder);
        this.configIdOverride = builder.hasConfigIdOverride() ? builder.getConfigId(support) : null;
        this.hasLovedItemsOverride = builder.hasLovedItemsOverride();
        this.lovedItemsOverride = hasLovedItemsOverride ? builder.getLovedItems(support) : null;
        this.isMountableOverride = builder.hasIsMountableOverride() ? builder.getIsMountable(support) : null;
        this.isHarvestableOverride = builder.hasIsHarvestableOverride() ? builder.getIsHarvestable(support) : null;
        this.hasHarvestContextOverride = builder.hasHarvestInteractionContextOverride();
        this.harvestContextOverride = hasHarvestContextOverride ? builder.getHarvestInteractionContext(support) : null;
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
        logDebug("TameworkInteract: execute called.");
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
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
        InteractionEntry entry = selectInteraction(config, npcRef, role, infoProvider, store, player);
        if (entry == null) {
            logDebug(buildNoMatchSummary(config, npcRef, role, infoProvider, store, player));
            return false;
        }
        return applyInteraction(entry, config, npcRef, role, infoProvider, store, player);
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
        if (entry instanceof CustomInteraction) {
            RequirementGroup requires = ((CustomInteraction) entry).getRequires();
            if (requires == null) {
                return true;
            }
            return evaluateRequirementGroup(requires, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof TameInteraction) {
            return meetsTameRequirements((TameInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof FeedInteraction) {
            return meetsFeedRequirements((FeedInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof HarvestInteraction) {
            return meetsHarvestRequirements((HarvestInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof MountInteraction) {
            return meetsMountRequirements((MountInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof ModeCycleInteraction) {
            return meetsModeCycleRequirements((ModeCycleInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof BreedInteraction) {
            return meetsBreedRequirements((BreedInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        return false;
    }

    private boolean evaluateRequirementGroup(RequirementGroup group,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player) {
        if (group == null) {
            return false;
        }
        if (!evaluateAllBucket(group.getAll(), npcRef, role, infoProvider, store, player)) {
            return false;
        }
        RequirementBucket any = group.getAny();
        if (any == null || any.isEmpty()) {
            return true;
        }
        return evaluateAnyBucket(any, npcRef, role, infoProvider, store, player);
    }

    private boolean evaluateAllBucket(RequirementBucket bucket,
                                      Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Player player) {
        if (bucket == null) {
            return true;
        }
        if (bucket.isLovedItems()
                && !isHeldItemInList(resolveLovedItems(role), player)) {
            return false;
        }
        if (bucket.isHarvestable()
                && !resolveIsHarvestable(role)) {
            return false;
        }
        if (bucket.isMountable()
                && !resolveIsMountable(role)) {
            return false;
        }
        if (bucket.isTamed()
                && !isTamed(npcRef, store)) {
            return false;
        }
        if (bucket.isNotTamed()
                && isTamed(npcRef, store)) {
            return false;
        }
        if (bucket.isPlayerCrouching()
                && !isPlayerCrouching(role, infoProvider, store, player)) {
            return false;
        }
        if (bucket.isPlayerIsOwner()
                && !isOwner(npcRef, store, player)) {
            return false;
        }
        if (bucket.isHarvestAlarmReady()
                && !isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM)) {
            return false;
        }
        if (bucket.isHarvestInteractionContext()
                && !matchesHarvestContext(npcRef, role, infoProvider, store)) {
            return false;
        }
        if (!requireAnyMatch(bucket.getItemsInHand(),
                requirement -> matchesItemsInHand(requirement, role, player))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getItemsEquipped(),
                requirement -> matchesItemsEquipped(requirement, player))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getParameter(),
                requirement -> matchesParamRequirement(requirement, role))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getAlarmState(),
                requirement -> matchesAlarmState(requirement, npcRef, store))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getNpcState(),
                requirement -> matchesNpcState(requirement, role))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getPlayerMovementState(),
                requirement -> matchesMovementState(requirement, role, infoProvider, store))) {
            return false;
        }
        if (!requireAnyMatch(bucket.getInteractionContext(),
                requirement -> matchesInteractionContext(requirement, npcRef, role, infoProvider, store))) {
            return false;
        }
        return true;
    }

    private boolean evaluateAnyBucket(RequirementBucket bucket,
                                      Ref<EntityStore> npcRef,
                                      Role role,
                                      InfoProvider infoProvider,
                                      Store<EntityStore> store,
                                      Player player) {
        if (bucket == null) {
            return false;
        }
        if (bucket.isLovedItems()
                && isHeldItemInList(resolveLovedItems(role), player)) {
            return true;
        }
        if (bucket.isHarvestable()
                && resolveIsHarvestable(role)) {
            return true;
        }
        if (bucket.isMountable()
                && resolveIsMountable(role)) {
            return true;
        }
        if (bucket.isTamed()
                && isTamed(npcRef, store)) {
            return true;
        }
        if (bucket.isNotTamed()
                && !isTamed(npcRef, store)) {
            return true;
        }
        if (bucket.isPlayerCrouching()
                && isPlayerCrouching(role, infoProvider, store, player)) {
            return true;
        }
        if (bucket.isPlayerIsOwner()
                && isOwner(npcRef, store, player)) {
            return true;
        }
        if (bucket.isHarvestAlarmReady()
                && isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM)) {
            return true;
        }
        if (bucket.isHarvestInteractionContext()
                && matchesHarvestContext(npcRef, role, infoProvider, store)) {
            return true;
        }
        if (anyMatch(bucket.getItemsInHand(),
                requirement -> matchesItemsInHand(requirement, role, player))) {
            return true;
        }
        if (anyMatch(bucket.getItemsEquipped(),
                requirement -> matchesItemsEquipped(requirement, player))) {
            return true;
        }
        if (anyMatch(bucket.getParameter(),
                requirement -> matchesParamRequirement(requirement, role))) {
            return true;
        }
        if (anyMatch(bucket.getAlarmState(),
                requirement -> matchesAlarmState(requirement, npcRef, store))) {
            return true;
        }
        if (anyMatch(bucket.getNpcState(),
                requirement -> matchesNpcState(requirement, role))) {
            return true;
        }
        if (anyMatch(bucket.getPlayerMovementState(),
                requirement -> matchesMovementState(requirement, role, infoProvider, store))) {
            return true;
        }
        if (anyMatch(bucket.getInteractionContext(),
                requirement -> matchesInteractionContext(requirement, npcRef, role, infoProvider, store))) {
            return true;
        }
        return false;
    }

    private <T> boolean requireAnyMatch(T[] requirements, Predicate<T> matcher) {
        if (requirements == null || requirements.length == 0) {
            return true;
        }
        for (T requirement : requirements) {
            if (requirement != null && matcher.test(requirement)) {
                return true;
            }
        }
        return false;
    }

    private <T> boolean anyMatch(T[] requirements, Predicate<T> matcher) {
        if (requirements == null || requirements.length == 0) {
            return false;
        }
        for (T requirement : requirements) {
            if (requirement != null && matcher.test(requirement)) {
                return true;
            }
        }
        return false;
    }

    private boolean meetsTameRequirements(TameInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player) {
        if (isTamed(npcRef, store)) {
            return false;
        }
        return matchesPresetItems(
                interaction.getUseLovedItems(),
                interaction.getItemsInHand(),
                interaction.getItemsParam(),
                role,
                player,
                true
        );
    }

    private boolean meetsFeedRequirements(FeedInteraction interaction,
                                          Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store,
                                          Player player) {
        if (!isTamed(npcRef, store)) {
            return false;
        }
        String[] explicitItems = resolveFeedItemIds(interaction);
        return matchesPresetItems(
                interaction.getUseLovedItems(),
                explicitItems,
                interaction.getItemsParam(),
                role,
                player,
                true
        );
    }

    private boolean meetsHarvestRequirements(HarvestInteraction interaction,
                                             Ref<EntityStore> npcRef,
                                             Role role,
                                             InfoProvider infoProvider,
                                             Store<EntityStore> store,
                                             Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireHarvestable = optionOrDefault(interaction.getRequireHarvestable(), true);
        boolean requireAlarm = optionOrDefault(interaction.getRequireHarvestAlarmReady(), true);
        boolean requireContext = optionOrDefault(interaction.getRequireHarvestInteractionContext(), true);
        if (requireTamed && !isTamed(npcRef, store)) {
            return false;
        }
        if (requireHarvestable && !resolveIsHarvestable(role)) {
            return false;
        }
        if (requireAlarm && !isAlarmReady(npcRef, store, DEFAULT_HARVEST_ALARM)) {
            return false;
        }
        if (requireContext && !matchesHarvestContext(npcRef, role, infoProvider, store)) {
            return false;
        }
        return true;
    }

    private boolean meetsMountRequirements(MountInteraction interaction,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireOwner = optionOrDefault(interaction.getRequireOwner(), true);
        boolean requireMountable = optionOrDefault(interaction.getRequireMountable(), true);
        boolean requireCrouching = optionOrDefault(interaction.getRequireCrouching(), true);
        if (requireTamed && !isTamed(npcRef, store)) {
            return false;
        }
        if (requireOwner && !isOwner(npcRef, store, player)) {
            return false;
        }
        if (requireMountable && !resolveIsMountable(role)) {
            return false;
        }
        if (requireCrouching && !isPlayerCrouching(role, infoProvider, store, player)) {
            return false;
        }
        return true;
    }

    private boolean meetsModeCycleRequirements(ModeCycleInteraction interaction,
                                               Ref<EntityStore> npcRef,
                                               Role role,
                                               InfoProvider infoProvider,
                                               Store<EntityStore> store,
                                               Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        boolean requireOwner = optionOrDefault(interaction.getRequireOwner(), true);
        if (requireTamed && !isTamed(npcRef, store)) {
            return false;
        }
        if (requireOwner && !isOwner(npcRef, store, player)) {
            return false;
        }
        return true;
    }

    private boolean meetsBreedRequirements(BreedInteraction interaction,
                                           Ref<EntityStore> npcRef,
                                           Role role,
                                           InfoProvider infoProvider,
                                           Store<EntityStore> store,
                                           Player player) {
        boolean requireTamed = optionOrDefault(interaction.getRequireTamed(), true);
        if (requireTamed && !isTamed(npcRef, store)) {
            return false;
        }
        // Breeding logic will live in the breeding system; for now this just gates the preset.
        return true;
    }

    private boolean matchesPresetItems(Boolean useLovedItemsFlag,
                                       String[] explicitItems,
                                       String paramName,
                                       Role role,
                                       Player player,
                                       boolean defaultUseLovedItems) {
        boolean useLovedItems = optionOrDefault(useLovedItemsFlag, defaultUseLovedItems);
        boolean hasExplicitItems = explicitItems != null && explicitItems.length > 0;
        boolean hasParam = paramName != null && !paramName.isBlank();
        boolean requiresItems = useLovedItems || hasExplicitItems || hasParam;
        if (!requiresItems) {
            return true;
        }
        String[] resolvedItems = resolvePresetItemsInHand(useLovedItems, explicitItems, paramName, role);
        return isHeldItemInList(resolvedItems, player);
    }

    private String[] resolvePresetItemsInHand(boolean useLovedItems,
                                              String[] explicitItems,
                                              String paramName,
                                              Role role) {
        Set<String> merged = new HashSet<>();
        if (useLovedItems) {
            String[] loved = resolveLovedItems(role);
            if (loved != null) {
                merged.addAll(Arrays.asList(loved));
            }
        }
        if (explicitItems != null) {
            for (String item : explicitItems) {
                if (item != null && !item.isBlank()) {
                    merged.add(item);
                }
            }
        }
        if (paramName != null && !paramName.isBlank()) {
            String[] paramItems = getRoleStringArrayParam(role, paramName);
            if (paramItems != null) {
                merged.addAll(Arrays.asList(paramItems));
            }
        }
        return merged.toArray(new String[0]);
    }

    private String[] resolveFeedItemIds(FeedInteraction interaction) {
        if (interaction == null) {
            return new String[0];
        }
        FeedItem[] feedItems = interaction.getItemsInHand();
        if (feedItems == null || feedItems.length == 0) {
            return new String[0];
        }
        ArrayList<String> ids = new ArrayList<>();
        for (FeedItem feedItem : feedItems) {
            if (feedItem == null) {
                continue;
            }
            String item = feedItem.getItem();
            if (item != null && !item.isBlank()) {
                ids.add(item);
            }
        }
        return ids.toArray(new String[0]);
    }

    private double resolveFeedHeal(FeedInteraction interaction, Role role, Player player) {
        if (interaction == null) {
            return 0.0;
        }
        double fallbackHeal = interaction.getHeal() != null ? interaction.getHeal() : 0.0;
        String heldItemId = getHeldItemId(player);
        if (heldItemId == null || heldItemId.isBlank()) {
            return fallbackHeal;
        }
        FeedItem[] items = interaction.getItemsInHand();
        if (items == null || items.length == 0) {
            return fallbackHeal;
        }
        for (FeedItem item : items) {
            if (item == null || item.getItem() == null) {
                continue;
            }
            if (heldItemId.equalsIgnoreCase(item.getItem()) && item.getHeal() != null) {
                return item.getHeal();
            }
        }
        return fallbackHeal;
    }

    private double resolveEffectFeedHeal(Effects effects) {
        if (effects == null || effects.getFeedHeal() == null) {
            return 0.0;
        }
        return effects.getFeedHeal();
    }

    private boolean optionOrDefault(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private boolean applyInteraction(InteractionEntry entry,
                                     TwInteractionConfig config,
                                     Ref<EntityStore> npcRef,
                                     Role role,
                                     InfoProvider infoProvider,
                                     Store<EntityStore> store,
                                     Player player) {
        if (entry instanceof CustomInteraction) {
            return applyCustomEffects((CustomInteraction) entry, npcRef, role, infoProvider, store, player);
        }
        if (entry instanceof TameInteraction) {
            boolean applied = applyStartTaming(npcRef, store, player);
            TameInteraction tame = (TameInteraction) entry;
            if (optionOrDefault(tame.getConsumeItem(), true)) {
                consumeHeldItem(player);
            }
            return applied;
        }
        if (entry instanceof FeedInteraction) {
            FeedInteraction feed = (FeedInteraction) entry;
            double healAmount = resolveFeedHeal(feed, role, player);
            boolean applied = applyFeeding(npcRef, store, healAmount);
            if (optionOrDefault(feed.getConsumeItem(), true)) {
                consumeHeldItem(player);
            }
            return applied;
        }
        if (entry instanceof HarvestInteraction) {
            return applyStartHarvest(npcRef, role, store);
        }
        if (entry instanceof MountInteraction) {
            logUnsupported("Mount preset effect not yet implemented.");
            return false;
        }
        if (entry instanceof ModeCycleInteraction) {
            ModeCycleInteraction cycle = (ModeCycleInteraction) entry;
            return applyToggleMode(cycle.getCycle(), npcRef, role, store);
        }
        if (entry instanceof BreedInteraction) {
            return applyStartBreeding(npcRef, role, store);
        }
        return false;
    }

    private boolean applyCustomEffects(CustomInteraction entry,
                                       Ref<EntityStore> npcRef,
                                       Role role,
                                       InfoProvider infoProvider,
                                       Store<EntityStore> store,
                                       Player player) {
        Effects effects = entry.getEffects();
        if (effects == null) {
            return false;
        }
        boolean applied = false;
        if (Boolean.TRUE.equals(effects.getStartTaming())) {
            applied |= applyStartTaming(npcRef, store, player);
        }
        if (Boolean.TRUE.equals(effects.getStartBreeding())) {
            applied |= applyStartBreeding(npcRef, role, store);
        }
        if (Boolean.TRUE.equals(effects.getApplyFeeding())) {
            double healAmount = resolveEffectFeedHeal(effects);
            applied |= applyFeeding(npcRef, store, healAmount);
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
            applied |= applyToggleMode(effects.getModeCycle(), npcRef, role, store);
        }
        HookEffect hookEffect = effects.getTriggerNpcHook();
        if (hookEffect != null) {
            applied |= applyTriggerNpcHook(hookEffect, npcRef, store, player);
        }
        return applied;
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

    private boolean applyFeeding(Ref<EntityStore> npcRef, Store<EntityStore> store, double healAmount) {
        if (healAmount > 0) {
            applyHeal(npcRef, store, healAmount);
        }
        return true;
    }

    private boolean applyHeal(Ref<EntityStore> npcRef, Store<EntityStore> store, double healAmount) {
        if (npcRef == null || store == null || healAmount <= 0) {
            return false;
        }
        ComponentType<EntityStore, EntityStatMap> type = EntityStatMap.getComponentType();
        if (type == null) {
            return false;
        }
        EntityStatMap statMap = store.getComponent(npcRef, type);
        if (statMap == null) {
            return false;
        }
        int statIndex = EntityStatType.getAssetMap().getIndex(HEALTH_STAT_ID);
        if (statIndex < 0) {
            return false;
        }
        statMap.addStatValue(statIndex, (float) healAmount);
        return true;
    }

    private boolean applyStartHarvest(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String subState = "";
        if (role.getStateSupport().getStateHelper() != null) {
            String defaultSub = role.getStateSupport().getStateHelper().getDefaultSubState();
            if (defaultSub != null && !defaultSub.isBlank()) {
                subState = defaultSub;
            }
        }
        role.getStateSupport().setState(npcRef, "$Harvest", subState, store);
        return true;
    }

    private boolean applyStartBreeding(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        logUnsupported("StartBreeding effect not yet implemented.");
        return false;
    }

    private boolean applyTriggerNpcHook(HookEffect hookEffect,
                                        Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Player player) {
        if (hookEffect == null) {
            return false;
        }
        String hookId = hookEffect.getHookId();
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        if (hookEffect.isPlayerOnly() && player == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkHookComponent> type = TameworkHookComponent.getComponentType();
        if (type == null) {
            return false;
        }
        UUID playerId = null;
        String playerName = null;
        String heldItemId = null;
        if (player != null) {
            playerId = player.getUuid();
            PlayerRef ref = player.getPlayerRef();
            if (ref != null) {
                playerName = ref.getUsername();
            }
            ItemStack stack = getActiveItem(player);
            if (stack != null) {
                heldItemId = stack.getItemId();
            }
        }
        long timestampMs = System.currentTimeMillis();
        TameworkHookComponent component = new TameworkHookComponent(
                hookId,
                playerId,
                playerName,
                heldItemId,
                timestampMs,
                hookEffect.isConsume()
        );
        store.putComponent(npcRef, type, component);
        return true;
    }

    private boolean applyToggleMode(ModeStep[] cycle,
                                    Ref<EntityStore> npcRef,
                                    Role role,
                                    Store<EntityStore> store) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String defaultSub = resolveDefaultSubState(role);
        ModeStep[] resolvedCycle = (cycle == null || cycle.length == 0) ? DEFAULT_MODE_CYCLE : cycle;
        ResolvedModeStep[] resolved = resolveValidModeSteps(resolvedCycle, role, defaultSub);
        if (resolved.length == 0) {
            logDebug("ModeToggle: no valid mode cycle states found for role " + role.getRoleName());
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
            logDebug("ModeToggle: message=" + next.message);
        }
        return true;
    }

    private String resolveDefaultSubState(Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return "";
        }
        String sub = role.getStateSupport().getStateHelper().getDefaultSubState();
        return sub == null ? "" : sub;
    }

    private ResolvedModeStep[] resolveValidModeSteps(ModeStep[] cycle, Role role, String defaultSub) {
        if (cycle == null || cycle.length == 0 || role == null || role.getStateSupport() == null) {
            return new ResolvedModeStep[0];
        }
        StateSupport stateSupport = role.getStateSupport();
        if (stateSupport.getStateHelper() == null) {
            return new ResolvedModeStep[0];
        }
        java.util.ArrayList<ResolvedModeStep> resolved = new java.util.ArrayList<>();
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

    private boolean matchesItemsInHand(ItemsInHandRequirement requirement, Role role, Player player) {
        if (requirement == null) {
            return false;
        }
        String[] items = resolveItemsInHand(requirement, role);
        return isHeldItemInList(items, player);
    }

    private boolean matchesItemsEquipped(ItemsEquippedRequirement requirement, Player player) {
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

    private boolean matchesHarvestContext(Ref<EntityStore> npcRef,
                                          Role role,
                                          InfoProvider infoProvider,
                                          Store<EntityStore> store) {
        String context = hasHarvestContextOverride ? harvestContextOverride : getRoleStringParam(role, DEFAULT_HARVEST_CONTEXT_PARAM);
        return matchesInteractionContext(context, npcRef, role, infoProvider, store, true);
    }

    private boolean matchesInteractionContext(InteractionContextRequirement requirement,
                                              Ref<EntityStore> npcRef,
                                              Role role,
                                              InfoProvider infoProvider,
                                              Store<EntityStore> store) {
        if (requirement == null) {
            return false;
        }
        String context = requirement.getContext();
        if ((context == null || context.isBlank()) && requirement.getParam() != null) {
            context = getRoleStringParam(role, requirement.getParam());
        }
        return matchesInteractionContext(context, npcRef, role, infoProvider, store, false);
    }

    private boolean matchesInteractionContext(String context,
                                              Ref<EntityStore> npcRef,
                                              Role role,
                                              InfoProvider infoProvider,
                                              Store<EntityStore> store,
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

    private boolean matchesMovementState(MovementStateRequirement requirement,
                                         Role role,
                                         InfoProvider infoProvider,
                                         Store<EntityStore> store) {
        if (requirement == null || requirement.getState() == null || requirement.getState().isBlank()) {
            return false;
        }
        MovementStates states = statesForPlayer(role, infoProvider, store);
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

    private boolean matchesAlarmState(AlarmRequirement requirement,
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

    private boolean matchesParamRequirement(ParamRequirement requirement, Role role) {
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

    private boolean matchesNpcState(StringRequirement requirement, Role role) {
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

    private String[] resolveItemsInHand(ItemsInHandRequirement requirement, Role role) {
        if (requirement == null) {
            return new String[0];
        }
        String[] paramItems = null;
        if (requirement.getParam() != null && !requirement.getParam().isBlank()) {
            paramItems = getRoleStringArrayParam(role, requirement.getParam());
        }
        return mergeArrays(requirement.getItems(), paramItems);
    }

    private String[] resolveLovedItems(Role role) {
        if (hasLovedItemsOverride) {
            return lovedItemsOverride != null ? lovedItemsOverride : new String[0];
        }
        String[] items = getRoleStringArrayParam(role, DEFAULT_LOVED_ITEMS_PARAM);
        return items != null ? items : new String[0];
    }

    private boolean resolveIsHarvestable(Role role) {
        if (isHarvestableOverride != null) {
            return isHarvestableOverride;
        }
        return getRoleBooleanParam(role, DEFAULT_IS_HARVESTABLE_PARAM);
    }

    private boolean resolveIsMountable(Role role) {
        if (isMountableOverride != null) {
            return isMountableOverride;
        }
        return getRoleBooleanParam(role, DEFAULT_IS_MOUNTABLE_PARAM);
    }

    private EquippedSlotSelection resolveEquippedSlots(String[] slots) {
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

    private boolean containsItemInArmor(ItemContainer armor, boolean[] slots, Set<String> items) {
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

    private boolean hasAnyItemEquipped(Inventory inventory, EquippedSlotSelection selection) {
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

    private boolean hasAnyItemInArmor(ItemContainer armor, boolean[] slots) {
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

    private boolean hasAnyItemInContainer(ItemContainer container) {
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

    private boolean containsItemInContainer(ItemContainer container, Set<String> items) {
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

    private Set<String> normalizeItemSet(String[] items) {
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

    private String[] mergeArrays(String[] primary, String[] secondary) {
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

    private String getRoleStringParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return null;
        }
        Supplier<String> supplier;
        try {
            supplier = scope.getStringSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
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

    private boolean getRoleBooleanParam(Role role, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return false;
        }
        StdScope scope = getRoleScope(role);
        if (scope == null) {
            return false;
        }
        BooleanSupplier supplier;
        try {
            supplier = scope.getBooleanSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return false;
        }
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

    private void logDebug(String message) {
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
        boolean harvestContext = matchesHarvestContext(npcRef, role, infoProvider, store);
        boolean isMountable = resolveIsMountable(role);
        boolean crouching = isPlayerCrouching(role, infoProvider, store, player);
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
}
