package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Asset-backed global configuration for Alec's Tamework.
 * Stored under Server/Tamework/Global.
 */
public final class TwGlobalConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwGlobalConfig>> {
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
            .<Double>append(
                    new KeyedCodec<>("CommandReturnHomeTeleportDistance", Codec.DOUBLE),
                    (asset, value) -> asset.commandReturnHomeTeleportDistance = value != null ? value : asset.commandReturnHomeTeleportDistance,
                    asset -> asset.commandReturnHomeTeleportDistance
            )
            .documentation("Distance threshold where Return Home switches from pure pathing to hybrid path-then-teleport.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("CommandReturnHomePathDistanceBeforeTeleport", Codec.DOUBLE),
                    (asset, value) -> asset.commandReturnHomePathDistanceBeforeTeleport = value != null ? value : asset.commandReturnHomePathDistanceBeforeTeleport,
                    asset -> asset.commandReturnHomePathDistanceBeforeTeleport
            )
            .documentation("Visible path distance to travel before deferred Return Home teleport.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("CommandReturnHomeTeleportDelayMs", Codec.INTEGER),
                    (asset, value) -> asset.commandReturnHomeTeleportDelayMs = value != null ? value : asset.commandReturnHomeTeleportDelayMs,
                    asset -> asset.commandReturnHomeTeleportDelayMs
            )
            .documentation("Delay before deferred Return Home teleport occurs.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("CommandRecallSafeSpawnDistance", Codec.DOUBLE),
                    (asset, value) -> asset.commandRecallSafeSpawnDistance = value != null ? value : asset.commandRecallSafeSpawnDistance,
                    asset -> asset.commandRecallSafeSpawnDistance
            )
            .documentation("Distance from owner used when recalling far NPCs to a safe nearby position.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("CommandRecallForceRelocateDistance", Codec.DOUBLE),
                    (asset, value) -> asset.commandRecallForceRelocateDistance = value != null ? value : asset.commandRecallForceRelocateDistance,
                    asset -> asset.commandRecallForceRelocateDistance
            )
            .documentation("Distance beyond which loaded recalled NPCs are force-relocated near owner before pathing in.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("CommandRelocationRetryIntervalMs", Codec.INTEGER),
                    (asset, value) -> asset.commandRelocationRetryIntervalMs = value != null ? value : asset.commandRelocationRetryIntervalMs,
                    asset -> asset.commandRelocationRetryIntervalMs
            )
            .documentation("Retry interval for queued off-screen command relocations.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("CommandRelocationMaxWaitMs", Codec.INTEGER),
                    (asset, value) -> asset.commandRelocationMaxWaitMs = value != null ? value : asset.commandRelocationMaxWaitMs,
                    asset -> asset.commandRelocationMaxWaitMs
            )
            .documentation("Maximum time to keep trying a queued off-screen command relocation.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("CommandRelocationMaxRetryAttempts", Codec.INTEGER),
                    (asset, value) -> asset.commandRelocationMaxRetryAttempts = value != null ? value : asset.commandRelocationMaxRetryAttempts,
                    asset -> asset.commandRelocationMaxRetryAttempts
            )
            .documentation("Maximum retry attempts for queued off-screen command relocations.")
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
    private boolean blockOwnerDamage;
    private boolean blockAllPlayerDamageIfOwned;
    private boolean invulnerableIfOwned;
    private String interactionConfigParam;
    private String lovedItemsParam;
    private String isHarvestableParam;
    private String isMountableParam;
    private String harvestContextParam;
    private String harvestAlarmName;
    private String interactionCooldownAlarmPrefix;
    private double commandReturnHomeTeleportDistance = 96.0;
    private double commandReturnHomePathDistanceBeforeTeleport = 24.0;
    private int commandReturnHomeTeleportDelayMs = 2500;
    private double commandRecallSafeSpawnDistance = 20.0;
    private double commandRecallForceRelocateDistance = 80.0;
    private int commandRelocationRetryIntervalMs = 2000;
    private int commandRelocationMaxWaitMs = 120000;
    private int commandRelocationMaxRetryAttempts = 60;

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
        return interactionConfigParam;
    }

    public String getLovedItemsParam() {
        return lovedItemsParam;
    }

    public String getIsHarvestableParam() {
        return isHarvestableParam;
    }

    public String getIsMountableParam() {
        return isMountableParam;
    }

    public String getHarvestContextParam() {
        return harvestContextParam;
    }

    public String getHarvestAlarmName() {
        return harvestAlarmName;
    }

    public String getInteractionCooldownAlarmPrefix() {
        return interactionCooldownAlarmPrefix;
    }

    public double getCommandReturnHomeTeleportDistance() {
        return commandReturnHomeTeleportDistance;
    }

    public double getCommandReturnHomePathDistanceBeforeTeleport() {
        return commandReturnHomePathDistanceBeforeTeleport;
    }

    public int getCommandReturnHomeTeleportDelayMs() {
        return commandReturnHomeTeleportDelayMs;
    }

    public double getCommandRecallSafeSpawnDistance() {
        return commandRecallSafeSpawnDistance;
    }

    public double getCommandRecallForceRelocateDistance() {
        return commandRecallForceRelocateDistance;
    }

    public int getCommandRelocationRetryIntervalMs() {
        return commandRelocationRetryIntervalMs;
    }

    public int getCommandRelocationMaxWaitMs() {
        return commandRelocationMaxWaitMs;
    }

    public int getCommandRelocationMaxRetryAttempts() {
        return commandRelocationMaxRetryAttempts;
    }

    // Returns the names of required string fields that are missing or blank.
    public String[] listMissingRequiredFields() {
        List<String> missing = new ArrayList<>();
        collectMissing(missing, "InteractionConfigParam", interactionConfigParam);
        collectMissing(missing, "LovedItemsParam", lovedItemsParam);
        collectMissing(missing, "IsHarvestableParam", isHarvestableParam);
        collectMissing(missing, "IsMountableParam", isMountableParam);
        collectMissing(missing, "HarvestContextParam", harvestContextParam);
        collectMissing(missing, "HarvestAlarmName", harvestAlarmName);
        collectMissing(missing, "InteractionCooldownAlarmPrefix", interactionCooldownAlarmPrefix);
        return missing.isEmpty() ? new String[0] : missing.toArray(new String[0]);
    }

    private void collectMissing(List<String> missing, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            missing.add(fieldName);
        }
    }
}
