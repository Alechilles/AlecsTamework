package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed global configuration for Alec's Tamework.
 * Stored under Server/Tamework/Global.
 */
public final class TwGlobalConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwGlobalConfig>>,
        TwParentFallbackAsset<TwGlobalConfig> {
    private static final int MILLIS_PER_MINUTE = 60_000;
    private static final BuilderCodec<GeneralSection> GENERAL_SECTION_CODEC = BuilderCodec.builder(
                    GeneralSection.class, GeneralSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (section, value) -> section.enabled = value,
                    section -> section.enabled
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (section, value) -> section.priority = value,
                    section -> section.priority
            )
            .add()
            .build();

    private static final BuilderCodec<OwnershipProtectionSection> OWNERSHIP_PROTECTION_SECTION_CODEC = BuilderCodec.builder(
                    OwnershipProtectionSection.class, OwnershipProtectionSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("BlockOwnerDamage", Codec.BOOLEAN),
                    (section, value) -> section.blockOwnerDamage = value,
                    section -> section.blockOwnerDamage
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockAllPlayerDamageIfOwned", Codec.BOOLEAN),
                    (section, value) -> section.blockAllPlayerDamageIfOwned = value,
                    section -> section.blockAllPlayerDamageIfOwned
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InvulnerableIfOwned", Codec.BOOLEAN),
                    (section, value) -> section.invulnerableIfOwned = value,
                    section -> section.invulnerableIfOwned
            )
            .add()
            .build();

    private static final BuilderCodec<InteractionDefaultsSection> INTERACTION_DEFAULTS_SECTION_CODEC = BuilderCodec.builder(
                    InteractionDefaultsSection.class, InteractionDefaultsSection::new
            )
            .<String>append(
                    new KeyedCodec<>("InteractionConfigParam", Codec.STRING),
                    (section, value) -> section.interactionConfigParam = value,
                    section -> section.interactionConfigParam
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("LovedItemsParam", Codec.STRING),
                    (section, value) -> section.lovedItemsParam = value,
                    section -> section.lovedItemsParam
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("IsHarvestableParam", Codec.STRING),
                    (section, value) -> section.isHarvestableParam = value,
                    section -> section.isHarvestableParam
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("IsMountableParam", Codec.STRING),
                    (section, value) -> section.isMountableParam = value,
                    section -> section.isMountableParam
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("HarvestContextParam", Codec.STRING),
                    (section, value) -> section.harvestContextParam = value,
                    section -> section.harvestContextParam
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("HarvestAlarmName", Codec.STRING),
                    (section, value) -> section.harvestAlarmName = value,
                    section -> section.harvestAlarmName
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("InteractionCooldownAlarmPrefix", Codec.STRING),
                    (section, value) -> section.interactionCooldownAlarmPrefix = value,
                    section -> section.interactionCooldownAlarmPrefix
            )
            .add()
            .build();

    private static final BuilderCodec<CommandSection> COMMAND_SECTION_CODEC = BuilderCodec.builder(
                    CommandSection.class, CommandSection::new
            )
            .<Double>append(
                    new KeyedCodec<>("ReturnHomeTeleportDistance", Codec.DOUBLE),
                    (section, value) -> section.returnHomeTeleportDistance = value,
                    section -> section.returnHomeTeleportDistance
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("ReturnHomePathDistanceBeforeTeleport", Codec.DOUBLE),
                    (section, value) -> section.returnHomePathDistanceBeforeTeleport = value,
                    section -> section.returnHomePathDistanceBeforeTeleport
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ReturnHomeTeleportDelayMs", Codec.INTEGER),
                    (section, value) -> section.returnHomeTeleportDelayMs = value,
                    section -> section.returnHomeTeleportDelayMs
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallSafeSpawnDistance", Codec.DOUBLE),
                    (section, value) -> section.recallSafeSpawnDistance = value,
                    section -> section.recallSafeSpawnDistance
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallForceRelocateDistance", Codec.DOUBLE),
                    (section, value) -> section.recallForceRelocateDistance = value,
                    section -> section.recallForceRelocateDistance
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("RelocationRetryIntervalMs", Codec.INTEGER),
                    (section, value) -> section.relocationRetryIntervalMs = value,
                    section -> section.relocationRetryIntervalMs
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("RelocationMaxWaitMs", Codec.INTEGER),
                    (section, value) -> section.relocationMaxWaitMs = value,
                    section -> section.relocationMaxWaitMs
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("RelocationMaxRetryAttempts", Codec.INTEGER),
                    (section, value) -> section.relocationMaxRetryAttempts = value,
                    section -> section.relocationMaxRetryAttempts
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("DeadRespawnEnabled", Codec.BOOLEAN),
                    (section, value) -> section.deadRespawnEnabled = value,
                    section -> section.deadRespawnEnabled
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnCooldownMs", Codec.INTEGER),
                    (section, value) -> section.deadRespawnCooldownMs = value,
                    section -> section.deadRespawnCooldownMs
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnCooldownMins", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnCooldownMins = value,
                    section -> section.deadRespawnCooldownMins
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnFollowRetryDelayMs", Codec.INTEGER),
                    (section, value) -> section.deadRespawnFollowRetryDelayMs = value,
                    section -> section.deadRespawnFollowRetryDelayMs
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceClose", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceClose = value,
                    section -> section.deadRespawnDistanceClose
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceNear", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceNear = value,
                    section -> section.deadRespawnDistanceNear
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceMid", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceMid = value,
                    section -> section.deadRespawnDistanceMid
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceFar", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceFar = value,
                    section -> section.deadRespawnDistanceFar
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMinRelativeY", Codec.DOUBLE),
                    (section, value) -> section.placementMinRelativeY = value,
                    section -> section.placementMinRelativeY
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMaxRelativeY", Codec.DOUBLE),
                    (section, value) -> section.placementMaxRelativeY = value,
                    section -> section.placementMaxRelativeY
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("LinkedPanelRequireUnlinkConfirm", Codec.BOOLEAN),
                    (section, value) -> section.linkedPanelRequireUnlinkConfirm = value,
                    section -> section.linkedPanelRequireUnlinkConfirm
            )
            .add()
            .build();

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
            .<GeneralSection>append(
                    new KeyedCodec<>("General", GENERAL_SECTION_CODEC),
                    TwGlobalConfig::applyGeneralSection,
                    TwGlobalConfig::toGeneralSection
            )
            .documentation("Organized section for global enabled/priority settings.")
            .add()
            .<OwnershipProtectionSection>append(
                    new KeyedCodec<>("OwnershipProtection", OWNERSHIP_PROTECTION_SECTION_CODEC),
                    TwGlobalConfig::applyOwnershipProtectionSection,
                    TwGlobalConfig::toOwnershipProtectionSection
            )
            .documentation("Organized section for owner-damage and ownership protection settings.")
            .add()
            .<InteractionDefaultsSection>append(
                    new KeyedCodec<>("InteractionDefaults", INTERACTION_DEFAULTS_SECTION_CODEC),
                    TwGlobalConfig::applyInteractionDefaultsSection,
                    TwGlobalConfig::toInteractionDefaultsSection
            )
            .documentation("Organized section for interaction parameter defaults.")
            .add()
            .<CommandSection>append(
                    new KeyedCodec<>("Command", COMMAND_SECTION_CODEC),
                    TwGlobalConfig::applyCommandSection,
                    TwGlobalConfig::toCommandSection
            )
            .documentation("Organized section for command runtime and respawn tuning settings.")
            .add()
            .build();

    private static AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
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
    private boolean commandDeadRespawnEnabled;
    private int commandDeadRespawnCooldownMs = 60000;
    private int commandDeadRespawnFollowRetryDelayMs = 1250;
    private double commandDeadRespawnDistanceClose = 5.0;
    private double commandDeadRespawnDistanceNear = 8.0;
    private double commandDeadRespawnDistanceMid = 12.0;
    private double commandDeadRespawnDistanceFar = 16.0;
    private double commandPlacementMinRelativeY = -2.0;
    private double commandPlacementMaxRelativeY = 4.0;
    private boolean commandLinkedPanelRequireUnlinkConfirm = true;

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
        DefaultAssetMap<String, TwGlobalConfig> assetMap = (DefaultAssetMap<String, TwGlobalConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    // Clears the cached active global config.
    public static void clearCache() {
        INHERITANCE_CACHE_DIRTY = true;
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

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwGlobalConfig> assetMap) {
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

    protected TwGlobalConfig() {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwGlobalConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwGlobalConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        inheritGeneralSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritOwnershipProtectionSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritInteractionDefaultsSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritCommandSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
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

    public boolean isCommandDeadRespawnEnabled() {
        return commandDeadRespawnEnabled;
    }

    public int getCommandDeadRespawnCooldownMs() {
        return commandDeadRespawnCooldownMs;
    }

    public int getCommandDeadRespawnFollowRetryDelayMs() {
        return commandDeadRespawnFollowRetryDelayMs;
    }

    public double getCommandDeadRespawnDistanceClose() {
        return commandDeadRespawnDistanceClose;
    }

    public double getCommandDeadRespawnDistanceNear() {
        return commandDeadRespawnDistanceNear;
    }

    public double getCommandDeadRespawnDistanceMid() {
        return commandDeadRespawnDistanceMid;
    }

    public double getCommandDeadRespawnDistanceFar() {
        return commandDeadRespawnDistanceFar;
    }

    public double getCommandPlacementMinRelativeY() {
        return commandPlacementMinRelativeY;
    }

    public double getCommandPlacementMaxRelativeY() {
        return commandPlacementMaxRelativeY;
    }

    public boolean isCommandLinkedPanelRequireUnlinkConfirm() {
        return commandLinkedPanelRequireUnlinkConfirm;
    }

    private void applyGeneralSection(@Nullable GeneralSection section) {
        if (section == null) {
            return;
        }
        if (section.enabled != null) {
            enabled = section.enabled;
        }
        if (section.priority != null) {
            priority = section.priority;
        }
    }

    private GeneralSection toGeneralSection() {
        GeneralSection section = new GeneralSection();
        section.enabled = enabled;
        section.priority = priority;
        return section;
    }

    private void applyOwnershipProtectionSection(@Nullable OwnershipProtectionSection section) {
        if (section == null) {
            return;
        }
        if (section.blockOwnerDamage != null) {
            blockOwnerDamage = section.blockOwnerDamage;
        }
        if (section.blockAllPlayerDamageIfOwned != null) {
            blockAllPlayerDamageIfOwned = section.blockAllPlayerDamageIfOwned;
        }
        if (section.invulnerableIfOwned != null) {
            invulnerableIfOwned = section.invulnerableIfOwned;
        }
    }

    private OwnershipProtectionSection toOwnershipProtectionSection() {
        OwnershipProtectionSection section = new OwnershipProtectionSection();
        section.blockOwnerDamage = blockOwnerDamage;
        section.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
        section.invulnerableIfOwned = invulnerableIfOwned;
        return section;
    }

    private void applyInteractionDefaultsSection(@Nullable InteractionDefaultsSection section) {
        if (section == null) {
            return;
        }
        if (section.interactionConfigParam != null) {
            interactionConfigParam = section.interactionConfigParam;
        }
        if (section.lovedItemsParam != null) {
            lovedItemsParam = section.lovedItemsParam;
        }
        if (section.isHarvestableParam != null) {
            isHarvestableParam = section.isHarvestableParam;
        }
        if (section.isMountableParam != null) {
            isMountableParam = section.isMountableParam;
        }
        if (section.harvestContextParam != null) {
            harvestContextParam = section.harvestContextParam;
        }
        if (section.harvestAlarmName != null) {
            harvestAlarmName = section.harvestAlarmName;
        }
        if (section.interactionCooldownAlarmPrefix != null) {
            interactionCooldownAlarmPrefix = section.interactionCooldownAlarmPrefix;
        }
    }

    private InteractionDefaultsSection toInteractionDefaultsSection() {
        InteractionDefaultsSection section = new InteractionDefaultsSection();
        section.interactionConfigParam = interactionConfigParam;
        section.lovedItemsParam = lovedItemsParam;
        section.isHarvestableParam = isHarvestableParam;
        section.isMountableParam = isMountableParam;
        section.harvestContextParam = harvestContextParam;
        section.harvestAlarmName = harvestAlarmName;
        section.interactionCooldownAlarmPrefix = interactionCooldownAlarmPrefix;
        return section;
    }

    private void applyCommandSection(@Nullable CommandSection section) {
        if (section == null) {
            return;
        }
        if (section.returnHomeTeleportDistance != null) {
            commandReturnHomeTeleportDistance = section.returnHomeTeleportDistance;
        }
        if (section.returnHomePathDistanceBeforeTeleport != null) {
            commandReturnHomePathDistanceBeforeTeleport = section.returnHomePathDistanceBeforeTeleport;
        }
        if (section.returnHomeTeleportDelayMs != null) {
            commandReturnHomeTeleportDelayMs = section.returnHomeTeleportDelayMs;
        }
        if (section.recallSafeSpawnDistance != null) {
            commandRecallSafeSpawnDistance = section.recallSafeSpawnDistance;
        }
        if (section.recallForceRelocateDistance != null) {
            commandRecallForceRelocateDistance = section.recallForceRelocateDistance;
        }
        if (section.relocationRetryIntervalMs != null) {
            commandRelocationRetryIntervalMs = section.relocationRetryIntervalMs;
        }
        if (section.relocationMaxWaitMs != null) {
            commandRelocationMaxWaitMs = section.relocationMaxWaitMs;
        }
        if (section.relocationMaxRetryAttempts != null) {
            commandRelocationMaxRetryAttempts = section.relocationMaxRetryAttempts;
        }
        if (section.deadRespawnEnabled != null) {
            commandDeadRespawnEnabled = section.deadRespawnEnabled;
        }
        if (section.deadRespawnCooldownMins != null) {
            commandDeadRespawnCooldownMs = minutesToMillis(
                    section.deadRespawnCooldownMins,
                    commandDeadRespawnCooldownMs
            );
        } else if (section.deadRespawnCooldownMs != null) {
            commandDeadRespawnCooldownMs = section.deadRespawnCooldownMs;
        }
        if (section.deadRespawnFollowRetryDelayMs != null) {
            commandDeadRespawnFollowRetryDelayMs = section.deadRespawnFollowRetryDelayMs;
        }
        if (section.deadRespawnDistanceClose != null) {
            commandDeadRespawnDistanceClose = section.deadRespawnDistanceClose;
        }
        if (section.deadRespawnDistanceNear != null) {
            commandDeadRespawnDistanceNear = section.deadRespawnDistanceNear;
        }
        if (section.deadRespawnDistanceMid != null) {
            commandDeadRespawnDistanceMid = section.deadRespawnDistanceMid;
        }
        if (section.deadRespawnDistanceFar != null) {
            commandDeadRespawnDistanceFar = section.deadRespawnDistanceFar;
        }
        if (section.placementMinRelativeY != null) {
            commandPlacementMinRelativeY = section.placementMinRelativeY;
        }
        if (section.placementMaxRelativeY != null) {
            commandPlacementMaxRelativeY = section.placementMaxRelativeY;
        }
        if (section.linkedPanelRequireUnlinkConfirm != null) {
            commandLinkedPanelRequireUnlinkConfirm = section.linkedPanelRequireUnlinkConfirm;
        }
    }

    private CommandSection toCommandSection() {
        CommandSection section = new CommandSection();
        section.returnHomeTeleportDistance = commandReturnHomeTeleportDistance;
        section.returnHomePathDistanceBeforeTeleport = commandReturnHomePathDistanceBeforeTeleport;
        section.returnHomeTeleportDelayMs = commandReturnHomeTeleportDelayMs;
        section.recallSafeSpawnDistance = commandRecallSafeSpawnDistance;
        section.recallForceRelocateDistance = commandRecallForceRelocateDistance;
        section.relocationRetryIntervalMs = commandRelocationRetryIntervalMs;
        section.relocationMaxWaitMs = commandRelocationMaxWaitMs;
        section.relocationMaxRetryAttempts = commandRelocationMaxRetryAttempts;
        section.deadRespawnEnabled = commandDeadRespawnEnabled;
        section.deadRespawnCooldownMs = commandDeadRespawnCooldownMs;
        section.deadRespawnFollowRetryDelayMs = commandDeadRespawnFollowRetryDelayMs;
        section.deadRespawnDistanceClose = commandDeadRespawnDistanceClose;
        section.deadRespawnDistanceNear = commandDeadRespawnDistanceNear;
        section.deadRespawnDistanceMid = commandDeadRespawnDistanceMid;
        section.deadRespawnDistanceFar = commandDeadRespawnDistanceFar;
        section.placementMinRelativeY = commandPlacementMinRelativeY;
        section.placementMaxRelativeY = commandPlacementMaxRelativeY;
        section.linkedPanelRequireUnlinkConfirm = commandLinkedPanelRequireUnlinkConfirm;
        return section;
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

    private void inheritGeneralSection(@Nonnull TwGlobalConfig parent,
                                       @Nonnull Set<String> explicitTopLevelKeys,
                                       @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("General")) {
            enabled = parent.enabled;
            priority = parent.priority;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("General");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("Enabled")) {
            enabled = parent.enabled;
        }
        if (!nestedExplicit.contains("Priority")) {
            priority = parent.priority;
        }
    }

    private void inheritOwnershipProtectionSection(@Nonnull TwGlobalConfig parent,
                                                   @Nonnull Set<String> explicitTopLevelKeys,
                                                   @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("OwnershipProtection")) {
            blockOwnerDamage = parent.blockOwnerDamage;
            blockAllPlayerDamageIfOwned = parent.blockAllPlayerDamageIfOwned;
            invulnerableIfOwned = parent.invulnerableIfOwned;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("OwnershipProtection");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("BlockOwnerDamage")) {
            blockOwnerDamage = parent.blockOwnerDamage;
        }
        if (!nestedExplicit.contains("BlockAllPlayerDamageIfOwned")) {
            blockAllPlayerDamageIfOwned = parent.blockAllPlayerDamageIfOwned;
        }
        if (!nestedExplicit.contains("InvulnerableIfOwned")) {
            invulnerableIfOwned = parent.invulnerableIfOwned;
        }
    }

    private void inheritInteractionDefaultsSection(@Nonnull TwGlobalConfig parent,
                                                   @Nonnull Set<String> explicitTopLevelKeys,
                                                   @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("InteractionDefaults")) {
            interactionConfigParam = parent.interactionConfigParam;
            lovedItemsParam = parent.lovedItemsParam;
            isHarvestableParam = parent.isHarvestableParam;
            isMountableParam = parent.isMountableParam;
            harvestContextParam = parent.harvestContextParam;
            harvestAlarmName = parent.harvestAlarmName;
            interactionCooldownAlarmPrefix = parent.interactionCooldownAlarmPrefix;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("InteractionDefaults");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("InteractionConfigParam")) {
            interactionConfigParam = parent.interactionConfigParam;
        }
        if (!nestedExplicit.contains("LovedItemsParam")) {
            lovedItemsParam = parent.lovedItemsParam;
        }
        if (!nestedExplicit.contains("IsHarvestableParam")) {
            isHarvestableParam = parent.isHarvestableParam;
        }
        if (!nestedExplicit.contains("IsMountableParam")) {
            isMountableParam = parent.isMountableParam;
        }
        if (!nestedExplicit.contains("HarvestContextParam")) {
            harvestContextParam = parent.harvestContextParam;
        }
        if (!nestedExplicit.contains("HarvestAlarmName")) {
            harvestAlarmName = parent.harvestAlarmName;
        }
        if (!nestedExplicit.contains("InteractionCooldownAlarmPrefix")) {
            interactionCooldownAlarmPrefix = parent.interactionCooldownAlarmPrefix;
        }
    }

    private void inheritCommandSection(@Nonnull TwGlobalConfig parent,
                                       @Nonnull Set<String> explicitTopLevelKeys,
                                       @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Command")) {
            commandReturnHomeTeleportDistance = parent.commandReturnHomeTeleportDistance;
            commandReturnHomePathDistanceBeforeTeleport = parent.commandReturnHomePathDistanceBeforeTeleport;
            commandReturnHomeTeleportDelayMs = parent.commandReturnHomeTeleportDelayMs;
            commandRecallSafeSpawnDistance = parent.commandRecallSafeSpawnDistance;
            commandRecallForceRelocateDistance = parent.commandRecallForceRelocateDistance;
            commandRelocationRetryIntervalMs = parent.commandRelocationRetryIntervalMs;
            commandRelocationMaxWaitMs = parent.commandRelocationMaxWaitMs;
            commandRelocationMaxRetryAttempts = parent.commandRelocationMaxRetryAttempts;
            commandDeadRespawnEnabled = parent.commandDeadRespawnEnabled;
            commandDeadRespawnCooldownMs = parent.commandDeadRespawnCooldownMs;
            commandDeadRespawnFollowRetryDelayMs = parent.commandDeadRespawnFollowRetryDelayMs;
            commandDeadRespawnDistanceClose = parent.commandDeadRespawnDistanceClose;
            commandDeadRespawnDistanceNear = parent.commandDeadRespawnDistanceNear;
            commandDeadRespawnDistanceMid = parent.commandDeadRespawnDistanceMid;
            commandDeadRespawnDistanceFar = parent.commandDeadRespawnDistanceFar;
            commandPlacementMinRelativeY = parent.commandPlacementMinRelativeY;
            commandPlacementMaxRelativeY = parent.commandPlacementMaxRelativeY;
            commandLinkedPanelRequireUnlinkConfirm = parent.commandLinkedPanelRequireUnlinkConfirm;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("Command");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("ReturnHomeTeleportDistance")) {
            commandReturnHomeTeleportDistance = parent.commandReturnHomeTeleportDistance;
        }
        if (!nestedExplicit.contains("ReturnHomePathDistanceBeforeTeleport")) {
            commandReturnHomePathDistanceBeforeTeleport = parent.commandReturnHomePathDistanceBeforeTeleport;
        }
        if (!nestedExplicit.contains("ReturnHomeTeleportDelayMs")) {
            commandReturnHomeTeleportDelayMs = parent.commandReturnHomeTeleportDelayMs;
        }
        if (!nestedExplicit.contains("RecallSafeSpawnDistance")) {
            commandRecallSafeSpawnDistance = parent.commandRecallSafeSpawnDistance;
        }
        if (!nestedExplicit.contains("RecallForceRelocateDistance")) {
            commandRecallForceRelocateDistance = parent.commandRecallForceRelocateDistance;
        }
        if (!nestedExplicit.contains("RelocationRetryIntervalMs")) {
            commandRelocationRetryIntervalMs = parent.commandRelocationRetryIntervalMs;
        }
        if (!nestedExplicit.contains("RelocationMaxWaitMs")) {
            commandRelocationMaxWaitMs = parent.commandRelocationMaxWaitMs;
        }
        if (!nestedExplicit.contains("RelocationMaxRetryAttempts")) {
            commandRelocationMaxRetryAttempts = parent.commandRelocationMaxRetryAttempts;
        }
        if (!nestedExplicit.contains("DeadRespawnEnabled")) {
            commandDeadRespawnEnabled = parent.commandDeadRespawnEnabled;
        }
        if (!hasDeadRespawnCooldownOverride(nestedExplicit)) {
            commandDeadRespawnCooldownMs = parent.commandDeadRespawnCooldownMs;
        }
        if (!nestedExplicit.contains("DeadRespawnFollowRetryDelayMs")) {
            commandDeadRespawnFollowRetryDelayMs = parent.commandDeadRespawnFollowRetryDelayMs;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceClose")) {
            commandDeadRespawnDistanceClose = parent.commandDeadRespawnDistanceClose;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceNear")) {
            commandDeadRespawnDistanceNear = parent.commandDeadRespawnDistanceNear;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceMid")) {
            commandDeadRespawnDistanceMid = parent.commandDeadRespawnDistanceMid;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceFar")) {
            commandDeadRespawnDistanceFar = parent.commandDeadRespawnDistanceFar;
        }
        if (!nestedExplicit.contains("PlacementMinRelativeY")) {
            commandPlacementMinRelativeY = parent.commandPlacementMinRelativeY;
        }
        if (!nestedExplicit.contains("PlacementMaxRelativeY")) {
            commandPlacementMaxRelativeY = parent.commandPlacementMaxRelativeY;
        }
        if (!nestedExplicit.contains("LinkedPanelRequireUnlinkConfirm")) {
            commandLinkedPanelRequireUnlinkConfirm = parent.commandLinkedPanelRequireUnlinkConfirm;
        }
    }

    private static boolean hasDeadRespawnCooldownOverride(@Nonnull Set<String> nestedExplicit) {
        return nestedExplicit.contains("DeadRespawnCooldownMs")
                || nestedExplicit.contains("DeadRespawnCooldownMins");
    }

    private static int minutesToMillis(double minutes, int fallbackMs) {
        if (!Double.isFinite(minutes) || minutes < 0) {
            return fallbackMs;
        }
        double millis = minutes * MILLIS_PER_MINUTE;
        if (millis >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(millis);
    }

    private static final class GeneralSection {
        private Boolean enabled;
        private Integer priority;
    }

    private static final class OwnershipProtectionSection {
        private Boolean blockOwnerDamage;
        private Boolean blockAllPlayerDamageIfOwned;
        private Boolean invulnerableIfOwned;
    }

    private static final class InteractionDefaultsSection {
        private String interactionConfigParam;
        private String lovedItemsParam;
        private String isHarvestableParam;
        private String isMountableParam;
        private String harvestContextParam;
        private String harvestAlarmName;
        private String interactionCooldownAlarmPrefix;
    }

    private static final class CommandSection {
        private Double returnHomeTeleportDistance;
        private Double returnHomePathDistanceBeforeTeleport;
        private Integer returnHomeTeleportDelayMs;
        private Double recallSafeSpawnDistance;
        private Double recallForceRelocateDistance;
        private Integer relocationRetryIntervalMs;
        private Integer relocationMaxWaitMs;
        private Integer relocationMaxRetryAttempts;
        private Boolean deadRespawnEnabled;
        private Integer deadRespawnCooldownMs;
        private Double deadRespawnCooldownMins;
        private Integer deadRespawnFollowRetryDelayMs;
        private Double deadRespawnDistanceClose;
        private Double deadRespawnDistanceNear;
        private Double deadRespawnDistanceMid;
        private Double deadRespawnDistanceFar;
        private Double placementMinRelativeY;
        private Double placementMaxRelativeY;
        private Boolean linkedPanelRequireUnlinkConfirm;
    }
}
