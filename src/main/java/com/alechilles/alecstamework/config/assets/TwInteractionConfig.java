package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed configuration for optimized interaction rules.
 * Stored under Server/Tamework/Interactions.
 */
public class TwInteractionConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwInteractionConfig>>,
        TwParentFallbackAsset<TwInteractionConfig> {
    public enum ParamOperator {
        Equals,
        NotEquals,
        GreaterThan,
        GreaterThanOrEqual,
        LessThan,
        LessThanOrEqual
    }

    public enum MatchType {
        Any,
        All
    }

    public enum ItemsMatchOperator {
        AnyOf,
        NoneOf
    }

    public enum ParticleAttachTarget {
        Position,
        Entity,
        Node
    }

    public enum OwnerSource {
        Player,
        None,
        Custom
    }

    static final InteractionEntry[] EMPTY_INTERACTIONS = new InteractionEntry[0];
    static final ModeStep[] EMPTY_MODE_CYCLE = new ModeStep[0];
    static final FeedItem[] EMPTY_FEED_ITEMS = new FeedItem[0];
    static final ItemsInHandRequirement[] EMPTY_ITEMS_IN_HAND_REQUIREMENTS = new ItemsInHandRequirement[0];
    static final ItemsInInventoryRequirement[] EMPTY_ITEMS_IN_INVENTORY_REQUIREMENTS = new ItemsInInventoryRequirement[0];
    static final ItemsEquippedRequirement[] EMPTY_ITEMS_EQUIPPED_REQUIREMENTS = new ItemsEquippedRequirement[0];
    static final NpcHealthPercentRequirement[] EMPTY_NPC_HEALTH_PERCENT_REQUIREMENTS = new NpcHealthPercentRequirement[0];
    static final ParamRequirement[] EMPTY_PARAM_REQUIREMENTS = new ParamRequirement[0];
    static final AlarmRequirement[] EMPTY_ALARM_REQUIREMENTS = new AlarmRequirement[0];
    static final StringRequirement[] EMPTY_STRING_REQUIREMENTS = new StringRequirement[0];
    static final MovementStateRequirement[] EMPTY_MOVEMENT_STATE_REQUIREMENTS = new MovementStateRequirement[0];
    static final InteractionContextRequirement[] EMPTY_CONTEXT_REQUIREMENTS = new InteractionContextRequirement[0];
    static final CustomRequirement[] EMPTY_CUSTOM_REQUIREMENTS = new CustomRequirement[0];
    static final CustomEffect[] EMPTY_CUSTOM_EFFECTS = new CustomEffect[0];
    static final StatDelta[] EMPTY_STAT_DELTAS = new StatDelta[0];
    static final ItemQuantity[] EMPTY_ITEM_QUANTITIES = new ItemQuantity[0];

    // Codecs (defined in TwInteractionConfigCodecs)
    public static final BuilderCodec<ModeStep> MODE_STEP_CODEC = TwInteractionConfigCodecs.MODE_STEP_CODEC;
    public static final ArrayCodec<ModeStep> MODE_STEP_ARRAY_CODEC = TwInteractionConfigCodecs.MODE_STEP_ARRAY_CODEC;
    public static final BuilderCodec<InteractionEntry> INTERACTION_BASE_CODEC = TwInteractionConfigCodecs.INTERACTION_BASE_CODEC;
    public static final BuilderCodec<TameInteraction> TAME_INTERACTION_CODEC = TwInteractionConfigCodecs.TAME_INTERACTION_CODEC;
    public static final BuilderCodec<FeedItem> FEED_ITEM_CODEC = TwInteractionConfigCodecs.FEED_ITEM_CODEC;
    public static final ArrayCodec<FeedItem> FEED_ITEM_ARRAY_CODEC = TwInteractionConfigCodecs.FEED_ITEM_ARRAY_CODEC;
    public static final BuilderCodec<FeedInteraction> FEED_INTERACTION_CODEC = TwInteractionConfigCodecs.FEED_INTERACTION_CODEC;
    public static final BuilderCodec<HarvestInteraction> HARVEST_INTERACTION_CODEC = TwInteractionConfigCodecs.HARVEST_INTERACTION_CODEC;
    public static final BuilderCodec<MountInteraction> MOUNT_INTERACTION_CODEC = TwInteractionConfigCodecs.MOUNT_INTERACTION_CODEC;
    public static final BuilderCodec<ModeCycleInteraction> MODE_CYCLE_INTERACTION_CODEC = TwInteractionConfigCodecs.MODE_CYCLE_INTERACTION_CODEC;
    public static final BuilderCodec<BreedInteraction> BREED_INTERACTION_CODEC = TwInteractionConfigCodecs.BREED_INTERACTION_CODEC;
    public static final BuilderCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_CODEC = TwInteractionConfigCodecs.ITEMS_IN_HAND_REQUIREMENT_CODEC;
    public static final ArrayCodec<ItemsInHandRequirement> ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.ITEMS_IN_HAND_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<ItemsInInventoryRequirement> ITEMS_IN_INVENTORY_REQUIREMENT_CODEC = TwInteractionConfigCodecs.ITEMS_IN_INVENTORY_REQUIREMENT_CODEC;
    public static final ArrayCodec<ItemsInInventoryRequirement> ITEMS_IN_INVENTORY_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.ITEMS_IN_INVENTORY_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<ItemsEquippedRequirement> ITEMS_EQUIPPED_REQUIREMENT_CODEC = TwInteractionConfigCodecs.ITEMS_EQUIPPED_REQUIREMENT_CODEC;
    public static final ArrayCodec<ItemsEquippedRequirement> ITEMS_EQUIPPED_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.ITEMS_EQUIPPED_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<NpcHealthPercentRequirement> NPC_HEALTH_PERCENT_REQUIREMENT_CODEC = TwInteractionConfigCodecs.NPC_HEALTH_PERCENT_REQUIREMENT_CODEC;
    public static final ArrayCodec<NpcHealthPercentRequirement> NPC_HEALTH_PERCENT_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.NPC_HEALTH_PERCENT_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<ParamRequirement> PARAM_REQUIREMENT_CODEC = TwInteractionConfigCodecs.PARAM_REQUIREMENT_CODEC;
    public static final ArrayCodec<ParamRequirement> PARAM_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.PARAM_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<AlarmRequirement> ALARM_REQUIREMENT_CODEC = TwInteractionConfigCodecs.ALARM_REQUIREMENT_CODEC;
    public static final ArrayCodec<AlarmRequirement> ALARM_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.ALARM_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<StringRequirement> STRING_REQUIREMENT_CODEC = TwInteractionConfigCodecs.STRING_REQUIREMENT_CODEC;
    public static final ArrayCodec<StringRequirement> STRING_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.STRING_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<MovementStateRequirement> MOVEMENT_STATE_REQUIREMENT_CODEC = TwInteractionConfigCodecs.MOVEMENT_STATE_REQUIREMENT_CODEC;
    public static final ArrayCodec<MovementStateRequirement> MOVEMENT_STATE_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.MOVEMENT_STATE_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<InteractionContextRequirement> INTERACTION_CONTEXT_REQUIREMENT_CODEC = TwInteractionConfigCodecs.INTERACTION_CONTEXT_REQUIREMENT_CODEC;
    public static final ArrayCodec<InteractionContextRequirement> INTERACTION_CONTEXT_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.INTERACTION_CONTEXT_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<CustomRequirement> CUSTOM_REQUIREMENT_CODEC = TwInteractionConfigCodecs.CUSTOM_REQUIREMENT_CODEC;
    public static final ArrayCodec<CustomRequirement> CUSTOM_REQUIREMENT_ARRAY_CODEC = TwInteractionConfigCodecs.CUSTOM_REQUIREMENT_ARRAY_CODEC;
    public static final BuilderCodec<RequirementBucket> REQUIREMENT_BUCKET_CODEC = TwInteractionConfigCodecs.REQUIREMENT_BUCKET_CODEC;
    public static final BuilderCodec<RequirementGroup> REQUIREMENT_GROUP_CODEC = TwInteractionConfigCodecs.REQUIREMENT_GROUP_CODEC;
    public static final BuilderCodec<HookEffect> HOOK_EFFECT_CODEC = TwInteractionConfigCodecs.HOOK_EFFECT_CODEC;
    public static final BuilderCodec<FloatingTextEffect> FLOATING_TEXT_EFFECT_CODEC = TwInteractionConfigCodecs.FLOATING_TEXT_EFFECT_CODEC;
    public static final BuilderCodec<UiMessageEffect> UI_MESSAGE_EFFECT_CODEC = TwInteractionConfigCodecs.UI_MESSAGE_EFFECT_CODEC;
    public static final BuilderCodec<SpawnParticlesEffect> SPAWN_PARTICLES_EFFECT_CODEC = TwInteractionConfigCodecs.SPAWN_PARTICLES_EFFECT_CODEC;
    public static final BuilderCodec<PlaySoundEffect> PLAY_SOUND_EFFECT_CODEC = TwInteractionConfigCodecs.PLAY_SOUND_EFFECT_CODEC;
    public static final BuilderCodec<DropItemEffect> DROP_ITEM_EFFECT_CODEC = TwInteractionConfigCodecs.DROP_ITEM_EFFECT_CODEC;
    public static final BuilderCodec<SetTamedEffect> SET_TAMED_EFFECT_CODEC = TwInteractionConfigCodecs.SET_TAMED_EFFECT_CODEC;
    public static final BuilderCodec<SetOwnerEffect> SET_OWNER_EFFECT_CODEC = TwInteractionConfigCodecs.SET_OWNER_EFFECT_CODEC;
    public static final BuilderCodec<StatDelta> STAT_DELTA_CODEC = TwInteractionConfigCodecs.STAT_DELTA_CODEC;
    public static final ArrayCodec<StatDelta> STAT_DELTA_ARRAY_CODEC = TwInteractionConfigCodecs.STAT_DELTA_ARRAY_CODEC;
    public static final Codec<ModifyStatsEffect> MODIFY_STATS_EFFECT_CODEC = TwInteractionConfigCodecs.MODIFY_STATS_EFFECT_CODEC;
    public static final BuilderCodec<SetStateEffect> SET_STATE_EFFECT_CODEC = TwInteractionConfigCodecs.SET_STATE_EFFECT_CODEC;
    public static final BuilderCodec<SetRoleEffect> SET_ROLE_EFFECT_CODEC = TwInteractionConfigCodecs.SET_ROLE_EFFECT_CODEC;
    public static final BuilderCodec<RemoveItemsHandEffect> REMOVE_ITEMS_HAND_EFFECT_CODEC = TwInteractionConfigCodecs.REMOVE_ITEMS_HAND_EFFECT_CODEC;
    public static final BuilderCodec<ItemQuantity> ITEM_QUANTITY_CODEC = TwInteractionConfigCodecs.ITEM_QUANTITY_CODEC;
    public static final ArrayCodec<ItemQuantity> ITEM_QUANTITY_ARRAY_CODEC = TwInteractionConfigCodecs.ITEM_QUANTITY_ARRAY_CODEC;
    public static final BuilderCodec<RemoveItemsInventoryEffect> REMOVE_ITEMS_INVENTORY_EFFECT_CODEC = TwInteractionConfigCodecs.REMOVE_ITEMS_INVENTORY_EFFECT_CODEC;
    public static final BuilderCodec<AddItemInventoryEffect> ADD_ITEM_INVENTORY_EFFECT_CODEC = TwInteractionConfigCodecs.ADD_ITEM_INVENTORY_EFFECT_CODEC;
    public static final BuilderCodec<CustomEffect> CUSTOM_EFFECT_CODEC = TwInteractionConfigCodecs.CUSTOM_EFFECT_CODEC;
    public static final ArrayCodec<CustomEffect> CUSTOM_EFFECT_ARRAY_CODEC = TwInteractionConfigCodecs.CUSTOM_EFFECT_ARRAY_CODEC;
    public static final BuilderCodec<Effects> EFFECTS_CODEC = TwInteractionConfigCodecs.EFFECTS_CODEC;
    public static final BuilderCodec<CustomInteraction> CUSTOM_INTERACTION_CODEC = TwInteractionConfigCodecs.CUSTOM_INTERACTION_CODEC;
    public static final StringCodecMapCodec<InteractionEntry, BuilderCodec<? extends InteractionEntry>> INTERACTION_CODEC = TwInteractionConfigCodecs.INTERACTION_CODEC;
    public static final ArrayCodec<InteractionEntry> INTERACTION_ARRAY_CODEC = TwInteractionConfigCodecs.INTERACTION_ARRAY_CODEC;
    public static final BuilderCodec<Cooldowns> COOLDOWNS_CODEC = TwInteractionConfigCodecs.COOLDOWNS_CODEC;
    public static final AssetBuilderCodec<String, TwInteractionConfig> CODEC = TwInteractionConfigCodecs.CODEC;

    static AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwInteractionConfig> ROLE_CACHE = Map.of();

    AssetExtraInfo.Data data;
    String id;
    boolean enabled = true;
    int priority;
    String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    InteractionEntry[] interactions = EMPTY_INTERACTIONS;
    Cooldowns cooldowns = new Cooldowns();

    public static AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwInteractionConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwInteractionConfig> getAssetMap() {
        AssetStore<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwInteractionConfig> assetMap = (DefaultAssetMap<String, TwInteractionConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwInteractionConfig resolveForRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwInteractionConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwInteractionConfig> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap);
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        return cache.get(roleId.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, TwInteractionConfig> buildRoleCache(
            DefaultAssetMap<String, TwInteractionConfig> assetMap) {
        Map<String, TwInteractionConfig> cache = new HashMap<>();
        if (assetMap == null) {
            return cache;
        }
        for (TwInteractionConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            String[] roles = candidate.getRoleIds();
            if (roles == null || roles.length == 0) {
                continue;
            }
            for (String role : roles) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                String key = role.trim().toLowerCase(Locale.ROOT);
                TwInteractionConfig existing = cache.get(key);
                if (existing == null || candidate.getPriority() > existing.getPriority()) {
                    cache.put(key, candidate);
                }
            }
        }
        return cache;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwInteractionConfig> assetMap) {
        if (!INHERITANCE_CACHE_DIRTY || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || assetMap.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    protected TwInteractionConfig() {
    }

    public String getId() {
        return id;
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwInteractionConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwInteractionConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Interactions")) interactions = parent.interactions;
        if (!explicitTopLevelKeys.contains("Cooldowns")) {
            cooldowns = parent.cooldowns;
        } else {
            inheritCooldownsSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Cooldowns"));
        }
    }

    private void inheritCooldownsSection(@Nonnull TwInteractionConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (cooldowns == null) {
            cooldowns = parent.cooldowns;
            return;
        }
        if (parent.cooldowns == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("InteractionSeconds")) {
            cooldowns.interactionSeconds = parent.cooldowns.interactionSeconds;
        }
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public String[] getRoleIds() {
        return roleIds;
    }

    public InteractionEntry[] getInteractions() {
        return interactions == null ? EMPTY_INTERACTIONS : interactions;
    }

    public Cooldowns getCooldowns() {
        return cooldowns;
    }

    public boolean matchesRole(String roleId) {
        if (roleId == null || roleIds == null || roleIds.length == 0) {
            return false;
        }
        return Arrays.stream(roleIds).anyMatch(roleId::equalsIgnoreCase);
    }

    public static final class Cooldowns {
        Integer interactionSeconds;

        public Integer getInteractionSeconds() {
            return interactionSeconds;
        }
    }

    public static abstract class InteractionEntry {
        boolean enabled = true;
        Integer cooldownSeconds;
        String promptHint;
        Boolean showPrompt;
        RequirementGroup requires;
        Effects effects;

        public boolean isEnabled() {
            return enabled;
        }

        public Integer getCooldownSeconds() {
            return cooldownSeconds;
        }

        public String getPromptHint() {
            return promptHint;
        }

        public Boolean getShowPrompt() {
            return showPrompt;
        }

        public RequirementGroup getRequires() {
            return requires;
        }

        public Effects getEffects() {
            return effects;
        }
    }

    public static final class TameInteraction extends InteractionEntry {
        Boolean useLovedItems;
        String[] itemsInHand = ArrayUtil.EMPTY_STRING_ARRAY;
        String itemsParam;
        String role;
        String roleParam;

        public Boolean getUseLovedItems() {
            return useLovedItems;
        }

        public String[] getItemsInHand() {
            return itemsInHand == null ? ArrayUtil.EMPTY_STRING_ARRAY : itemsInHand;
        }

        public String getItemsParam() {
            return itemsParam;
        }

        public String getRole() {
            return role;
        }

        public String getRoleParam() {
            return roleParam;
        }
    }

    public static final class FeedItem {
        String item;
        Double heal;

        public FeedItem() {
        }

        public FeedItem(String item, Double heal) {
            this.item = item;
            this.heal = heal;
        }

        public String getItem() {
            return item;
        }

        public Double getHeal() {
            return heal;
        }
    }

    public static final class FeedInteraction extends InteractionEntry {
        Boolean useLovedItems;
        FeedItem[] itemsInHand = EMPTY_FEED_ITEMS;
        Double heal;
        String itemsParam;

        public Boolean getUseLovedItems() {
            return useLovedItems;
        }

        public FeedItem[] getItemsInHand() {
            return itemsInHand == null ? EMPTY_FEED_ITEMS : itemsInHand;
        }

        public Double getHeal() {
            return heal;
        }

        public String getItemsParam() {
            return itemsParam;
        }
    }

    public static final class HarvestInteraction extends InteractionEntry {
        Boolean requireTamed;
        Boolean requireHarvestable;
        Boolean requireHarvestAlarmReady;
        Boolean requireHarvestInteractionContext;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Boolean getRequireHarvestable() {
            return requireHarvestable;
        }

        public Boolean getRequireHarvestAlarmReady() {
            return requireHarvestAlarmReady;
        }

        public Boolean getRequireHarvestInteractionContext() {
            return requireHarvestInteractionContext;
        }
    }

    public static final class MountInteraction extends InteractionEntry {
        Boolean requireTamed;
        Boolean requireOwner;
        Boolean requireMountable;
        Boolean requireCrouching;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Boolean getRequireOwner() {
            return requireOwner;
        }

        public Boolean getRequireMountable() {
            return requireMountable;
        }

        public Boolean getRequireCrouching() {
            return requireCrouching;
        }
    }

    public static final class ModeCycleInteraction extends InteractionEntry {
        Boolean requireTamed;
        Boolean requireOwner;
        Boolean showFloatingText;
        Boolean showUiMessage;
        ModeStep[] cycle = EMPTY_MODE_CYCLE;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Boolean getRequireOwner() {
            return requireOwner;
        }

        public boolean isShowFloatingText() {
            return Boolean.TRUE.equals(showFloatingText);
        }

        public boolean isShowUiMessage() {
            return Boolean.TRUE.equals(showUiMessage);
        }

        public ModeStep[] getCycle() {
            return cycle == null ? EMPTY_MODE_CYCLE : cycle;
        }
    }

    public static final class BreedInteraction extends InteractionEntry {
        Boolean requireTamed;
        Double minHappiness;
        Double fertilityBonus;
        Integer manualSelectionSeconds;

        public Boolean getRequireTamed() {
            return requireTamed;
        }

        public Double getMinHappiness() {
            return minHappiness;
        }

        public Double getFertilityBonus() {
            return fertilityBonus;
        }

        public Integer getManualSelectionSeconds() {
            return manualSelectionSeconds;
        }
    }

    public static final class CustomInteraction extends InteractionEntry {
        String presetId;

        public CustomInteraction() {
        }

        public String getPresetId() {
            return presetId;
        }
    }

    public static final class RequirementGroup {
        RequirementBucket all = new RequirementBucket();
        RequirementBucket any = new RequirementBucket();

        public RequirementBucket getAll() {
            return all == null ? new RequirementBucket() : all;
        }

        public RequirementBucket getAny() {
            return any == null ? new RequirementBucket() : any;
        }
    }

    public static final class RequirementBucket {
        boolean lovedItems;
        boolean isHarvestable;
        boolean isMountable;
        boolean isTamed;
        boolean isNotTamed;
        boolean playerHandEmpty;
        boolean playerCrouching;
        boolean playerIsOwner;
        boolean harvestAlarmReady;
        boolean harvestInteractionContext;
        ItemsInHandRequirement[] itemsInHand = EMPTY_ITEMS_IN_HAND_REQUIREMENTS;
        ItemsInInventoryRequirement[] itemsInInventory = EMPTY_ITEMS_IN_INVENTORY_REQUIREMENTS;
        ItemsEquippedRequirement[] itemsEquipped = EMPTY_ITEMS_EQUIPPED_REQUIREMENTS;
        NpcHealthPercentRequirement[] npcHealthPercent = EMPTY_NPC_HEALTH_PERCENT_REQUIREMENTS;
        ParamRequirement[] parameter = EMPTY_PARAM_REQUIREMENTS;
        AlarmRequirement[] alarmState = EMPTY_ALARM_REQUIREMENTS;
        StringRequirement[] npcState = EMPTY_STRING_REQUIREMENTS;
        MovementStateRequirement[] playerMovementState = EMPTY_MOVEMENT_STATE_REQUIREMENTS;
        InteractionContextRequirement[] interactionContext = EMPTY_CONTEXT_REQUIREMENTS;
        CustomRequirement[] custom = EMPTY_CUSTOM_REQUIREMENTS;

        public boolean isLovedItems() {
            return lovedItems;
        }

        public boolean isHarvestable() {
            return isHarvestable;
        }

        public boolean isMountable() {
            return isMountable;
        }

        public boolean isTamed() {
            return isTamed;
        }

        public boolean isNotTamed() {
            return isNotTamed;
        }

        public boolean isPlayerHandEmpty() {
            return playerHandEmpty;
        }

        public boolean isPlayerCrouching() {
            return playerCrouching;
        }

        public boolean isPlayerIsOwner() {
            return playerIsOwner;
        }

        public boolean isHarvestAlarmReady() {
            return harvestAlarmReady;
        }

        public boolean isHarvestInteractionContext() {
            return harvestInteractionContext;
        }

        public ItemsInHandRequirement[] getItemsInHand() {
            return itemsInHand == null ? EMPTY_ITEMS_IN_HAND_REQUIREMENTS : itemsInHand;
        }

        public ItemsInInventoryRequirement[] getItemsInInventory() {
            return itemsInInventory == null ? EMPTY_ITEMS_IN_INVENTORY_REQUIREMENTS : itemsInInventory;
        }

        public ItemsEquippedRequirement[] getItemsEquipped() {
            return itemsEquipped == null ? EMPTY_ITEMS_EQUIPPED_REQUIREMENTS : itemsEquipped;
        }

        public NpcHealthPercentRequirement[] getNpcHealthPercent() {
            return npcHealthPercent == null ? EMPTY_NPC_HEALTH_PERCENT_REQUIREMENTS : npcHealthPercent;
        }

        public ParamRequirement[] getParameter() {
            return parameter == null ? EMPTY_PARAM_REQUIREMENTS : parameter;
        }

        public AlarmRequirement[] getAlarmState() {
            return alarmState == null ? EMPTY_ALARM_REQUIREMENTS : alarmState;
        }

        public StringRequirement[] getNpcState() {
            return npcState == null ? EMPTY_STRING_REQUIREMENTS : npcState;
        }

        public MovementStateRequirement[] getPlayerMovementState() {
            return playerMovementState == null ? EMPTY_MOVEMENT_STATE_REQUIREMENTS : playerMovementState;
        }

        public InteractionContextRequirement[] getInteractionContext() {
            return interactionContext == null ? EMPTY_CONTEXT_REQUIREMENTS : interactionContext;
        }

        public CustomRequirement[] getCustom() {
            return custom == null ? EMPTY_CUSTOM_REQUIREMENTS : custom;
        }

        public boolean isEmpty() {
            return !lovedItems
                    && !isHarvestable
                    && !isMountable
                    && !isTamed
                    && !isNotTamed
                    && !playerHandEmpty
                    && !playerCrouching
                    && !playerIsOwner
                    && !harvestAlarmReady
                    && !harvestInteractionContext
                    && getItemsInHand().length == 0
                    && getItemsInInventory().length == 0
                    && getItemsEquipped().length == 0
                    && getNpcHealthPercent().length == 0
                    && getParameter().length == 0
                    && getAlarmState().length == 0
                    && getNpcState().length == 0
                    && getPlayerMovementState().length == 0
                    && getInteractionContext().length == 0
                    && getCustom().length == 0;
        }
    }

    public static final class CustomRequirement {
        String id;
        String param;
        String[] values = ArrayUtil.EMPTY_STRING_ARRAY;
        String jsonPayload;

        public String getId() {
            return id;
        }

        public String getParam() {
            return param;
        }

        public String[] getValues() {
            return values == null ? ArrayUtil.EMPTY_STRING_ARRAY : values;
        }

        public String getJsonPayload() {
            return jsonPayload;
        }
    }

    public static final class ItemsInHandRequirement {
        String itemsParam;
        String[] items = ArrayUtil.EMPTY_STRING_ARRAY;
        Integer quantity;
        ItemsMatchOperator operator = ItemsMatchOperator.AnyOf;

        public String getItemsParam() {
            return itemsParam;
        }

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public ItemsMatchOperator getOperator() {
            return operator == null ? ItemsMatchOperator.AnyOf : operator;
        }
    }

    public static final class ItemsInInventoryRequirement {
        String itemsParam;
        String[] items = ArrayUtil.EMPTY_STRING_ARRAY;
        Integer quantity;

        public String getItemsParam() {
            return itemsParam;
        }

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }

    public static final class ItemsEquippedRequirement {
        String itemsParam;
        String[] items = ArrayUtil.EMPTY_STRING_ARRAY;
        String[] slots = ArrayUtil.EMPTY_STRING_ARRAY;

        public String getItemsParam() {
            return itemsParam;
        }

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }

        public String[] getSlots() {
            return slots == null ? ArrayUtil.EMPTY_STRING_ARRAY : slots;
        }
    }

    public static final class ParamRequirement {
        String name;
        ParamOperator operator = ParamOperator.Equals;
        MatchType match = MatchType.Any;
        String[] values = ArrayUtil.EMPTY_STRING_ARRAY;

        public String getName() {
            return name;
        }

        public ParamOperator getOperator() {
            return operator == null ? ParamOperator.Equals : operator;
        }

        public MatchType getMatch() {
            return match == null ? MatchType.Any : match;
        }

        public String[] getValues() {
            return values == null ? ArrayUtil.EMPTY_STRING_ARRAY : values;
        }
    }

    public static final class NpcHealthPercentRequirement {
        ParamOperator operator = ParamOperator.LessThanOrEqual;
        Double value;

        public ParamOperator getOperator() {
            return operator == null ? ParamOperator.LessThanOrEqual : operator;
        }

        public Double getValue() {
            return value;
        }
    }

    public static final class AlarmRequirement {
        String alarmParam;
        String name;
        String state;

        public String getAlarmParam() {
            return alarmParam;
        }

        public String getName() {
            return name;
        }

        public String getState() {
            return state;
        }
    }

    public static final class StringRequirement {
        String state;
        String subState;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }
    }

    public static final class InteractionContextRequirement {
        String context;
        String contextParam;

        public String getContext() {
            return context;
        }

        public String getContextParam() {
            return contextParam;
        }
    }

    public static final class MovementStateRequirement {
        String state;

        public String getState() {
            return state;
        }
    }

    public static final class Effects {
        SetTamedEffect setTamed;
        SetOwnerEffect setOwner;
        ModifyStatsEffect modifyStats;
        SetStateEffect setState;
        SetRoleEffect setRole;
        RemoveItemsHandEffect removeItemsHand;
        AddItemsHandEffect addItemsHand;
        RemoveItemsInventoryEffect removeItemsInventory;
        AddItemInventoryEffect addItemInventory;
        Boolean mount;
        PlaySoundEffect playSound;
        SpawnParticlesEffect spawnParticles;
        DropItemEffect dropItem;
        HookEffect triggerNpcHook;
        FloatingTextEffect showFloatingText;
        UiMessageEffect showUiMessage;
        CustomEffect[] custom = EMPTY_CUSTOM_EFFECTS;

        public SetTamedEffect getSetTamed() {
            return setTamed;
        }

        public SetOwnerEffect getSetOwner() {
            return setOwner;
        }

        public ModifyStatsEffect getModifyStats() {
            return modifyStats;
        }

        public SetStateEffect getSetState() {
            return setState;
        }

        public SetRoleEffect getSetRole() {
            return setRole;
        }

        public RemoveItemsHandEffect getRemoveItemsHand() {
            return removeItemsHand;
        }

        public AddItemsHandEffect getAddItemsHand() {
            return addItemsHand;
        }

        public RemoveItemsInventoryEffect getRemoveItemsInventory() {
            return removeItemsInventory;
        }

        public AddItemInventoryEffect getAddItemInventory() {
            return addItemInventory;
        }

        public Boolean getMount() {
            return mount;
        }

        public PlaySoundEffect getPlaySound() {
            return playSound;
        }

        public SpawnParticlesEffect getSpawnParticles() {
            return spawnParticles;
        }

        public DropItemEffect getDropItem() {
            return dropItem;
        }

        public HookEffect getTriggerNpcHook() {
            return triggerNpcHook;
        }

        public FloatingTextEffect getShowFloatingText() {
            return showFloatingText;
        }

        public UiMessageEffect getShowUiMessage() {
            return showUiMessage;
        }

        public CustomEffect[] getCustom() {
            return custom == null ? EMPTY_CUSTOM_EFFECTS : custom;
        }
    }

    public static final class CustomEffect {
        String id;
        String param;
        String[] values = ArrayUtil.EMPTY_STRING_ARRAY;
        String jsonPayload;

        public String getId() {
            return id;
        }

        public String getParam() {
            return param;
        }

        public String[] getValues() {
            return values == null ? ArrayUtil.EMPTY_STRING_ARRAY : values;
        }

        public String getJsonPayload() {
            return jsonPayload;
        }
    }

    public static final class SetTamedEffect {
        Boolean value;

        public Boolean getValue() {
            return value;
        }
    }

    public static final class SetOwnerEffect {
        OwnerSource source = OwnerSource.Player;
        String uuid;
        String name;

        public OwnerSource getSource() {
            return source == null ? OwnerSource.Player : source;
        }

        public String getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }
    }

    public static final class ModifyStatsEffect {
        StatDelta[] stats = EMPTY_STAT_DELTAS;

        public StatDelta[] getStats() {
            return stats == null ? EMPTY_STAT_DELTAS : stats;
        }
    }

    public static final class SetStateEffect {
        String state;
        String subState;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }
    }

    /**
     * Effect that swaps the NPC role.
     */
    public static final class SetRoleEffect {
        String role;
        String roleParam;

        public String getRole() {
            return role;
        }

        public String getRoleParam() {
            return roleParam;
        }
    }

    public static final class RemoveItemsHandEffect {
        String itemsParam;
        String[] items = ArrayUtil.EMPTY_STRING_ARRAY;
        Integer quantity;

        public String getItemsParam() {
            return itemsParam;
        }

        public String[] getItems() {
            return items == null ? ArrayUtil.EMPTY_STRING_ARRAY : items;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }

    public static final class AddItemsHandEffect {
        String itemsParam;
        ItemQuantity[] items = EMPTY_ITEM_QUANTITIES;

        public String getItemsParam() {
            return itemsParam;
        }

        public ItemQuantity[] getItems() {
            return items == null ? EMPTY_ITEM_QUANTITIES : items;
        }
    }

    public static final class RemoveItemsInventoryEffect {
        String itemsParam;
        ItemQuantity[] items = EMPTY_ITEM_QUANTITIES;

        public String getItemsParam() {
            return itemsParam;
        }

        public ItemQuantity[] getItems() {
            return items == null ? EMPTY_ITEM_QUANTITIES : items;
        }
    }

    public static final class AddItemInventoryEffect {
        String itemsParam;
        ItemQuantity[] items = EMPTY_ITEM_QUANTITIES;

        public String getItemsParam() {
            return itemsParam;
        }

        public ItemQuantity[] getItems() {
            return items == null ? EMPTY_ITEM_QUANTITIES : items;
        }
    }

    public static final class ItemQuantity {
        String item;
        Integer quantity;

        public ItemQuantity() {
        }

        public ItemQuantity(String item, Integer quantity) {
            this.item = item;
            this.quantity = quantity;
        }

        public String getItem() {
            return item;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }

    public static final class StatDelta {
        String statId;
        Double amount;

        public StatDelta() {
        }

        public StatDelta(String statId, Double amount) {
            this.statId = statId;
            this.amount = amount;
        }

        public String getStatId() {
            return statId;
        }

        public Double getAmount() {
            return amount;
        }
    }

    public static final class HookEffect {
        String hookId;
        boolean playerOnly;
        boolean consume;

        public String getHookId() {
            return hookId;
        }

        public boolean isPlayerOnly() {
            return playerOnly;
        }

        public boolean isConsume() {
            return consume;
        }
    }

    public static final class FloatingTextEffect {
        String message;

        public String getMessage() {
            return message;
        }
    }

    public static final class UiMessageEffect {
        String message;

        public String getMessage() {
            return message;
        }
    }

    public static final class SpawnParticlesEffect {
        String particleSystem;
        String particleSystemParam;
        Vector3d offset;
        String offsetParam;
        ParticleAttachTarget attachTarget = ParticleAttachTarget.Position;
        String attachNode;
        Color color;
        boolean playerOnly;

        public String getParticleSystem() {
            return particleSystem;
        }

        public String getParticleSystemParam() {
            return particleSystemParam;
        }

        public Vector3d getOffset() {
            return offset;
        }

        public String getOffsetParam() {
            return offsetParam;
        }

        public ParticleAttachTarget getAttachTarget() {
            return attachTarget == null ? ParticleAttachTarget.Position : attachTarget;
        }

        public String getAttachNode() {
            return attachNode;
        }

        public Color getColor() {
            return color;
        }

        public boolean isPlayerOnly() {
            return playerOnly;
        }
    }

    public static final class PlaySoundEffect {
        String soundEvent;
        Double volume;
        Double pitch;
        Vector3d offset;
        boolean playerOnly;

        public String getSoundEvent() {
            return soundEvent;
        }

        public Double getVolume() {
            return volume;
        }

        public Double getPitch() {
            return pitch;
        }

        public Vector3d getOffset() {
            return offset;
        }

        public boolean isPlayerOnly() {
            return playerOnly;
        }
    }

    public static final class DropItemEffect {
        String item;
        String dropList;
        Integer quantityMin;
        Integer quantityMax;
        Double throwSpeed;

        public String getItem() {
            return item;
        }

        public String getDropList() {
            return dropList;
        }

        public Integer getQuantityMin() {
            return quantityMin;
        }

        public Integer getQuantityMax() {
            return quantityMax;
        }

        public Double getThrowSpeed() {
            return throwSpeed;
        }
    }

    public static final class ModeStep {
        String state;
        String subState;
        String message;

        public ModeStep() {
        }

        public ModeStep(String state, String subState, String message) {
            this.state = state;
            this.subState = subState;
            this.message = message;
        }

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }

        public String getMessage() {
            return message;
        }
    }

}

