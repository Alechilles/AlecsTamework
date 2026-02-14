package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import javax.annotation.Nullable;

/**
 * Asset-backed global configuration for Alec's Tamework.
 * Stored under Server/Tamework/Global.
 */
public final class TwGlobalConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwGlobalConfig>> {
    public static final String DEFAULT_CONFIG_PARAM = "InteractionConfigId";
    public static final String DEFAULT_LOVED_ITEMS_PARAM = "LovedItems";
    public static final String DEFAULT_IS_HARVESTABLE_PARAM = "IsHarvestable";
    public static final String DEFAULT_IS_MOUNTABLE_PARAM = "IsMountable";
    public static final String DEFAULT_HARVEST_CONTEXT_PARAM = "HarvestInteractionContext";
    public static final String DEFAULT_HARVEST_ALARM = "Harvest_Ready";
    public static final String DEFAULT_COOLDOWN_ALARM_PREFIX = "TameworkInteract_Cooldown";

    public static final boolean DEFAULT_BLOCK_OWNER_DAMAGE = true;
    public static final boolean DEFAULT_BLOCK_ALL_PLAYER_DAMAGE_IF_OWNED = false;
    public static final boolean DEFAULT_INVULNERABLE_IF_OWNED = false;

    public static final AssetBuilderCodec<String, TwGlobalConfig> CODEC =
            AssetBuilderCodec.builder(
                    TwGlobalConfig.class,
                    TwGlobalConfig::new,
                    Codec.STRING,
                    (asset, id) -> asset.id = id,
                    asset -> asset.id,
                    (asset, data) -> asset.data = data,
                    asset -> asset.data
            )
            .documentation("Global defaults and settings for Alec's Tamework.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value != null ? value : asset.enabled,
                    asset -> asset.enabled
            )
            .documentation("Whether this global config is active.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value != null ? value : asset.priority,
                    asset -> asset.priority
            )
            .documentation("Priority for resolving multiple global configs (higher wins).")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockOwnerDamage", Codec.BOOLEAN),
                    (asset, value) -> asset.blockOwnerDamage = value != null ? value : asset.blockOwnerDamage,
                    asset -> asset.blockOwnerDamage
            )
            .documentation("Block damage dealt by the owner to owned NPCs.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockAllPlayerDamageIfOwned", Codec.BOOLEAN),
                    (asset, value) -> asset.blockAllPlayerDamageIfOwned = value != null ? value : asset.blockAllPlayerDamageIfOwned,
                    asset -> asset.blockAllPlayerDamageIfOwned
            )
            .documentation("Block all player damage to owned NPCs.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InvulnerableIfOwned", Codec.BOOLEAN),
                    (asset, value) -> asset.invulnerableIfOwned = value != null ? value : asset.invulnerableIfOwned,
                    asset -> asset.invulnerableIfOwned
            )
            .documentation("Make owned NPCs invulnerable.")
            .add()
            .<String>append(
                    new KeyedCodec<>("InteractionConfigParam", Codec.STRING),
                    (asset, value) -> asset.interactionConfigParam = value != null ? value : asset.interactionConfigParam,
                    asset -> asset.interactionConfigParam
            )
            .documentation("Role param name that overrides interaction config id.")
            .add()
            .<String>append(
                    new KeyedCodec<>("LovedItemsParam", Codec.STRING),
                    (asset, value) -> asset.lovedItemsParam = value != null ? value : asset.lovedItemsParam,
                    asset -> asset.lovedItemsParam
            )
            .documentation("Role param name that stores loved item ids.")
            .add()
            .<String>append(
                    new KeyedCodec<>("IsHarvestableParam", Codec.STRING),
                    (asset, value) -> asset.isHarvestableParam = value != null ? value : asset.isHarvestableParam,
                    asset -> asset.isHarvestableParam
            )
            .documentation("Role param name that signals harvestable state.")
            .add()
            .<String>append(
                    new KeyedCodec<>("IsMountableParam", Codec.STRING),
                    (asset, value) -> asset.isMountableParam = value != null ? value : asset.isMountableParam,
                    asset -> asset.isMountableParam
            )
            .documentation("Role param name that signals mountable state.")
            .add()
            .<String>append(
                    new KeyedCodec<>("HarvestContextParam", Codec.STRING),
                    (asset, value) -> asset.harvestContextParam = value != null ? value : asset.harvestContextParam,
                    asset -> asset.harvestContextParam
            )
            .documentation("Role param name used for harvest interaction context.")
            .add()
            .<String>append(
                    new KeyedCodec<>("HarvestAlarmName", Codec.STRING),
                    (asset, value) -> asset.harvestAlarmName = value != null ? value : asset.harvestAlarmName,
                    asset -> asset.harvestAlarmName
            )
            .documentation("Alarm name used to gate harvest readiness.")
            .add()
            .<String>append(
                    new KeyedCodec<>("InteractionCooldownAlarmPrefix", Codec.STRING),
                    (asset, value) -> asset.interactionCooldownAlarmPrefix = value != null ? value : asset.interactionCooldownAlarmPrefix,
                    asset -> asset.interactionCooldownAlarmPrefix
            )
            .documentation("Prefix used for per-interaction cooldown alarms.")
            .add()
            .build();

    private static AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> ASSET_STORE;
    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean CACHE_DIRTY = true;
    private static volatile TwGlobalConfig ACTIVE_CONFIG;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private boolean blockOwnerDamage = DEFAULT_BLOCK_OWNER_DAMAGE;
    private boolean blockAllPlayerDamageIfOwned = DEFAULT_BLOCK_ALL_PLAYER_DAMAGE_IF_OWNED;
    private boolean invulnerableIfOwned = DEFAULT_INVULNERABLE_IF_OWNED;
    private String interactionConfigParam = DEFAULT_CONFIG_PARAM;
    private String lovedItemsParam = DEFAULT_LOVED_ITEMS_PARAM;
    private String isHarvestableParam = DEFAULT_IS_HARVESTABLE_PARAM;
    private String isMountableParam = DEFAULT_IS_MOUNTABLE_PARAM;
    private String harvestContextParam = DEFAULT_HARVEST_CONTEXT_PARAM;
    private String harvestAlarmName = DEFAULT_HARVEST_ALARM;
    private String interactionCooldownAlarmPrefix = DEFAULT_COOLDOWN_ALARM_PREFIX;

    public static AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwGlobalConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwGlobalConfig> getAssetMap() {
        AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, TwGlobalConfig>) store.getAssetMap();
    }

    // Clears the cached active global config.
    public static void clearCache() {
        CACHE_DIRTY = true;
    }

    // Returns the active global config (or defaults if none exist).
    public static TwGlobalConfig resolveActive() {
        DefaultAssetMap<String, TwGlobalConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return defaultConfig();
        }
        TwGlobalConfig cached = ACTIVE_CONFIG;
        if (CACHE_DIRTY || cached == null) {
            synchronized (CACHE_LOCK) {
                if (CACHE_DIRTY || ACTIVE_CONFIG == null) {
                    ACTIVE_CONFIG = selectBest(assetMap);
                    CACHE_DIRTY = false;
                }
                cached = ACTIVE_CONFIG;
            }
        }
        return cached != null ? cached : defaultConfig();
    }

    // Builds a default global config without relying on asset store state.
    public static TwGlobalConfig defaultConfig() {
        return new TwGlobalConfig();
    }

    private static TwGlobalConfig selectBest(DefaultAssetMap<String, TwGlobalConfig> assetMap) {
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwGlobalConfig best = null;
        int bestPriority = Integer.MIN_VALUE;
        String bestId = null;
        for (TwGlobalConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            int candidatePriority = candidate.getPriority();
            if (best == null || candidatePriority > bestPriority) {
                best = candidate;
                bestPriority = candidatePriority;
                bestId = candidate.getId();
                continue;
            }
            if (candidatePriority == bestPriority) {
                String candidateId = candidate.getId();
                if (compareIds(candidateId, bestId) < 0) {
                    best = candidate;
                    bestId = candidateId;
                }
            }
        }
        return best;
    }

    private static int compareIds(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    protected TwGlobalConfig() {
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isBlockOwnerDamage() {
        return blockOwnerDamage;
    }

    public boolean isBlockAllPlayerDamageIfOwned() {
        return blockAllPlayerDamageIfOwned;
    }

    public boolean isInvulnerableIfOwned() {
        return invulnerableIfOwned;
    }

    public String getInteractionConfigParam() {
        return defaultIfBlank(interactionConfigParam, DEFAULT_CONFIG_PARAM);
    }

    public String getLovedItemsParam() {
        return defaultIfBlank(lovedItemsParam, DEFAULT_LOVED_ITEMS_PARAM);
    }

    public String getIsHarvestableParam() {
        return defaultIfBlank(isHarvestableParam, DEFAULT_IS_HARVESTABLE_PARAM);
    }

    public String getIsMountableParam() {
        return defaultIfBlank(isMountableParam, DEFAULT_IS_MOUNTABLE_PARAM);
    }

    public String getHarvestContextParam() {
        return defaultIfBlank(harvestContextParam, DEFAULT_HARVEST_CONTEXT_PARAM);
    }

    public String getHarvestAlarmName() {
        return defaultIfBlank(harvestAlarmName, DEFAULT_HARVEST_ALARM);
    }

    public String getInteractionCooldownAlarmPrefix() {
        return defaultIfBlank(interactionCooldownAlarmPrefix, DEFAULT_COOLDOWN_ALARM_PREFIX);
    }

    private static String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
