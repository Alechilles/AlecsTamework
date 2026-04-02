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
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped companion behavior policy for ownership protection and command behavior.
 *
 * <p>Stored under {@code Server/Tamework/Companion}.
 */
public final class TwCompanionConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCompanionConfig>>,
        TwParentFallbackAsset<TwCompanionConfig> {
    private static final int MILLIS_PER_MINUTE = 60_000;
    private static final double DEFAULT_RETURN_HOME_TELEPORT_DISTANCE = 96.0;
    private static final double DEFAULT_RETURN_HOME_PATH_DISTANCE_BEFORE_TELEPORT = 24.0;
    private static final int DEFAULT_RETURN_HOME_TELEPORT_DELAY_MS = 2500;
    private static final double DEFAULT_RECALL_SAFE_SPAWN_DISTANCE = 20.0;
    private static final double DEFAULT_RECALL_FORCE_RELOCATE_DISTANCE = 80.0;
    private static final boolean DEFAULT_DEAD_RESPAWN_ENABLED = true;
    private static final int DEFAULT_DEAD_RESPAWN_COOLDOWN_MS = 10000;
    private static final int DEFAULT_DEAD_RESPAWN_FOLLOW_RETRY_DELAY_MS = 1250;
    private static final double DEFAULT_DEAD_RESPAWN_DISTANCE_CLOSE = 5.0;
    private static final double DEFAULT_DEAD_RESPAWN_DISTANCE_NEAR = 8.0;
    private static final double DEFAULT_DEAD_RESPAWN_DISTANCE_MID = 12.0;
    private static final double DEFAULT_DEAD_RESPAWN_DISTANCE_FAR = 16.0;
    private static final double DEFAULT_PLACEMENT_MIN_RELATIVE_Y = -2.0;
    private static final double DEFAULT_PLACEMENT_MAX_RELATIVE_Y = 4.0;
    private static final boolean DEFAULT_CROSS_WORLD_RECALL_ENABLED = true;
    private static final boolean DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE = true;
    private static final String[] DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE_STATE_FILTER =
            new String[] { "Follow", "Defend", "Aggressive" };

    private static final BuilderCodec<OwnershipProtectionSettings> OWNERSHIP_PROTECTION_CODEC = BuilderCodec.builder(
            OwnershipProtectionSettings.class,
            OwnershipProtectionSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("BlockOwnerDamage", Codec.BOOLEAN),
                    (settings, value) -> settings.blockOwnerDamage = value,
                    settings -> settings.blockOwnerDamage
            )
            .documentation("Config field: BlockOwnerDamage.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockAllPlayerDamageIfOwned", Codec.BOOLEAN),
                    (settings, value) -> settings.blockAllPlayerDamageIfOwned = value,
                    settings -> settings.blockAllPlayerDamageIfOwned
            )
            .documentation("Config field: BlockAllPlayerDamageIfOwned.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InvulnerableIfOwned", Codec.BOOLEAN),
                    (settings, value) -> settings.invulnerableIfOwned = value,
                    settings -> settings.invulnerableIfOwned
            )
            .documentation("Config field: InvulnerableIfOwned.")
            .add()
            .build();

    private static final BuilderCodec<TravelSettings> TRAVEL_CODEC = BuilderCodec.builder(
            TravelSettings.class,
            TravelSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("CrossWorldRecallEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.crossWorldRecallEnabled = value != null && value,
                    settings -> settings.crossWorldRecallEnabled
            )
            .documentation("Config field: CrossWorldRecallEnabled.")
            .add()
            .<String>append(
                    new KeyedCodec<>("OnTransferFailure", Codec.STRING),
                    (settings, value) -> settings.onTransferFailure =
                            TransferFailurePolicy.parse(value, settings.onTransferFailure),
                    settings -> settings.onTransferFailure.name()
            )
            .documentation("Config field: OnTransferFailure.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("FollowMasterOnWorldChange", Codec.BOOLEAN),
                    (settings, value) -> settings.followMasterOnWorldChange = value != null && value,
                    settings -> settings.followMasterOnWorldChange
            )
            .documentation("Config field: FollowMasterOnWorldChange.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("FollowMasterOnWorldChangeStateFilter", Codec.STRING_ARRAY),
                    (settings, value) -> settings.followMasterOnWorldChangeStateFilter = normalizeStateFilter(value),
                    settings -> settings.followMasterOnWorldChangeStateFilter
            )
            .documentation("Config field: FollowMasterOnWorldChangeStateFilter.")
            .add()
            .build();

    private static final BuilderCodec<CommandSettings> COMMAND_CODEC = BuilderCodec.builder(
            CommandSettings.class,
            CommandSettings::new
    )
            .<Double>append(
                    new KeyedCodec<>("ReturnHomeTeleportDistance", Codec.DOUBLE),
                    (settings, value) -> settings.returnHomeTeleportDistance = value,
                    settings -> settings.returnHomeTeleportDistance
            )
            .documentation("Config field: ReturnHomeTeleportDistance.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("ReturnHomePathDistanceBeforeTeleport", Codec.DOUBLE),
                    (settings, value) -> settings.returnHomePathDistanceBeforeTeleport = value,
                    settings -> settings.returnHomePathDistanceBeforeTeleport
            )
            .documentation("Config field: ReturnHomePathDistanceBeforeTeleport.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ReturnHomeTeleportDelayMs", Codec.INTEGER),
                    (settings, value) -> settings.returnHomeTeleportDelayMs = value,
                    settings -> settings.returnHomeTeleportDelayMs
            )
            .documentation("Config field: ReturnHomeTeleportDelayMs.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallSafeSpawnDistance", Codec.DOUBLE),
                    (settings, value) -> settings.recallSafeSpawnDistance = value,
                    settings -> settings.recallSafeSpawnDistance
            )
            .documentation("Config field: RecallSafeSpawnDistance.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallForceRelocateDistance", Codec.DOUBLE),
                    (settings, value) -> settings.recallForceRelocateDistance = value,
                    settings -> settings.recallForceRelocateDistance
            )
            .documentation("Config field: RecallForceRelocateDistance.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("DeadRespawnEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.deadRespawnEnabled = value,
                    settings -> settings.deadRespawnEnabled
            )
            .documentation("Config field: DeadRespawnEnabled.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnCooldownMs", Codec.INTEGER),
                    (settings, value) -> settings.deadRespawnCooldownMs = value,
                    settings -> settings.deadRespawnCooldownMs
            )
            .documentation("Config field: DeadRespawnCooldownMs.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnCooldownMins", Codec.DOUBLE),
                    (settings, value) -> {
                        if (value != null) {
                            settings.deadRespawnCooldownMs = minutesToMillis(value, settings.deadRespawnCooldownMs);
                        }
                    },
                    settings -> null
            )
            .documentation("Config field: DeadRespawnCooldownMins.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnFollowRetryDelayMs", Codec.INTEGER),
                    (settings, value) -> settings.deadRespawnFollowRetryDelayMs = value,
                    settings -> settings.deadRespawnFollowRetryDelayMs
            )
            .documentation("Config field: DeadRespawnFollowRetryDelayMs.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceClose", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceClose = value,
                    settings -> settings.deadRespawnDistanceClose
            )
            .documentation("Config field: DeadRespawnDistanceClose.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceNear", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceNear = value,
                    settings -> settings.deadRespawnDistanceNear
            )
            .documentation("Config field: DeadRespawnDistanceNear.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceMid", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceMid = value,
                    settings -> settings.deadRespawnDistanceMid
            )
            .documentation("Config field: DeadRespawnDistanceMid.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceFar", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceFar = value,
                    settings -> settings.deadRespawnDistanceFar
            )
            .documentation("Config field: DeadRespawnDistanceFar.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMinRelativeY", Codec.DOUBLE),
                    (settings, value) -> settings.placementMinRelativeY = value,
                    settings -> settings.placementMinRelativeY
            )
            .documentation("Config field: PlacementMinRelativeY.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMaxRelativeY", Codec.DOUBLE),
                    (settings, value) -> settings.placementMaxRelativeY = value,
                    settings -> settings.placementMaxRelativeY
            )
            .documentation("Config field: PlacementMaxRelativeY.")
            .add()
            .<TravelSettings>append(
                    new KeyedCodec<>("Travel", TRAVEL_CODEC),
                    (settings, value) -> settings.travel = value == null ? new TravelSettings() : value,
                    settings -> settings.travel
            )
            .documentation("Config field: Travel.")
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwCompanionConfig> CODEC = AssetBuilderCodec.builder(
            TwCompanionConfig.class,
            TwCompanionConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped companion behavior policy for command and ownership behavior.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled
            )
            .documentation("Config field: Enabled.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Config field: Priority.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds
            )
            .documentation("NPC role IDs this config applies to. Inheritance: omitted value inherits from parent; "
                    + "explicit array replaces parent value (no merge).")
            .add()
            .<OwnershipProtectionSettings>append(
                    new KeyedCodec<>("OwnershipProtection", OWNERSHIP_PROTECTION_CODEC),
                    (asset, value) -> asset.ownershipProtection = value == null
                            ? new OwnershipProtectionSettings()
                            : value,
                    asset -> asset.ownershipProtection
            )
            .documentation("Owner-damage protection settings. Inheritance: omitted section inherits from parent; when "
                    + "present, only explicitly defined nested fields override parent.")
            .add()
            .<CommandSettings>append(
                    new KeyedCodec<>("Command", COMMAND_CODEC),
                    (asset, value) -> asset.command = value == null ? new CommandSettings() : value,
                    asset -> asset.command
            )
            .documentation("Companion command runtime settings. Inheritance: omitted section inherits from parent; when "
                    + "present, only explicitly defined nested fields override parent.")
            .add()
            .build();

    private static AssetStore<String, TwCompanionConfig, DefaultAssetMap<String, TwCompanionConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwCompanionConfig> ROLE_CACHE = Map.of();
    private static volatile TwCompanionConfig ROLELESS_DEFAULT_CONFIG;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private OwnershipProtectionSettings ownershipProtection = new OwnershipProtectionSettings();
    private CommandSettings command = new CommandSettings();

    public static AssetStore<String, TwCompanionConfig, DefaultAssetMap<String, TwCompanionConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwCompanionConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCompanionConfig> getAssetMap() {
        AssetStore<String, TwCompanionConfig, DefaultAssetMap<String, TwCompanionConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwCompanionConfig> assetMap =
                (DefaultAssetMap<String, TwCompanionConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
        ROLELESS_DEFAULT_CONFIG = null;
    }

    @Nullable
    public static TwCompanionConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwCompanionConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        ensureRoleCacheBuilt(assetMap);
        Map<String, TwCompanionConfig> cache = ROLE_CACHE;
        return cache.get(roleId.trim().toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static TwCompanionConfig resolveDefaultConfig() {
        DefaultAssetMap<String, TwCompanionConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        ensureRoleCacheBuilt(assetMap);
        return ROLELESS_DEFAULT_CONFIG;
    }

    @Nullable
    public static TwCompanionConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwCompanionConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwCompanionConfig> map = assetMap.getAssetMap();
        TwCompanionConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwCompanionConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    public static EffectiveSettings resolveEffectiveForRole(@Nullable String roleId) {
        TwCompanionConfig scoped = resolveForRole(roleId);
        if (scoped == null) {
            scoped = resolveDefaultConfig();
        }
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        return EffectiveSettings.from(scoped, global);
    }

    private static void ensureRoleCacheBuilt(@Nullable DefaultAssetMap<String, TwCompanionConfig> assetMap) {
        if (assetMap == null) {
            return;
        }
        Map<String, TwCompanionConfig> cache = ROLE_CACHE;
        if (!ROLE_CACHE_DIRTY && cache != null) {
            return;
        }
        synchronized (ROLE_CACHE_LOCK) {
            if (!ROLE_CACHE_DIRTY && ROLE_CACHE != null) {
                return;
            }
            ROLE_CACHE = buildRoleCache(assetMap);
            ROLELESS_DEFAULT_CONFIG = selectRolelessDefaultConfig(assetMap);
            ROLE_CACHE_DIRTY = false;
        }
    }

    private static Map<String, TwCompanionConfig> buildRoleCache(@Nullable DefaultAssetMap<String, TwCompanionConfig> assetMap) {
        Map<String, TwCompanionConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwCompanionConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            String[] candidateRoles = candidate.getRoleIds();
            if (!hasAnyRoleIds(candidateRoles)) {
                continue;
            }
            for (String roleId : candidateRoles) {
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                String normalizedRole = roleId.trim().toLowerCase(Locale.ROOT);
                TwCompanionConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    @Nullable
    private static TwCompanionConfig selectRolelessDefaultConfig(
            @Nullable DefaultAssetMap<String, TwCompanionConfig> assetMap) {
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwCompanionConfig best = null;
        for (TwCompanionConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            if (hasAnyRoleIds(candidate.getRoleIds())) {
                continue;
            }
            if (shouldReplaceCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean hasAnyRoleIds(@Nullable String[] roleIds) {
        if (roleIds == null || roleIds.length == 0) {
            return false;
        }
        for (String roleId : roleIds) {
            if (roleId != null && !roleId.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwCompanionConfig candidate,
                                                  @Nullable TwCompanionConfig existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        int candidatePriority = candidate.getPriority();
        int existingPriority = existing.getPriority();
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority;
        }
        return compareIds(candidate.getId(), existing.getId()) < 0;
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwCompanionConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwCompanionConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCompanionConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        inheritOwnershipProtectionSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritCommandSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
    }

    protected TwCompanionConfig() {
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

    public String[] getRoleIds() {
        return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
    }

    public OwnershipProtectionSettings getOwnershipProtection() {
        return ownershipProtection == null ? new OwnershipProtectionSettings() : ownershipProtection;
    }

    public CommandSettings getCommand() {
        return command == null ? new CommandSettings() : command;
    }

    public boolean isBlockOwnerDamage() {
        return getOwnershipProtection().isBlockOwnerDamage();
    }

    public boolean isBlockAllPlayerDamageIfOwned() {
        return getOwnershipProtection().isBlockAllPlayerDamageIfOwned();
    }

    public boolean isInvulnerableIfOwned() {
        return getOwnershipProtection().isInvulnerableIfOwned();
    }

    private void inheritOwnershipProtectionSection(@Nonnull TwCompanionConfig parent,
                                                   @Nonnull Set<String> explicitTopLevelKeys,
                                                   @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("OwnershipProtection")) {
            ownershipProtection = parent.getOwnershipProtection().copy();
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("OwnershipProtection");
        if (nestedExplicit == null) {
            return;
        }
        OwnershipProtectionSettings parentSettings = parent.getOwnershipProtection();
        OwnershipProtectionSettings currentSettings = getOwnershipProtection();
        if (!nestedExplicit.contains("BlockOwnerDamage")) {
            currentSettings.blockOwnerDamage = parentSettings.blockOwnerDamage;
        }
        if (!nestedExplicit.contains("BlockAllPlayerDamageIfOwned")) {
            currentSettings.blockAllPlayerDamageIfOwned = parentSettings.blockAllPlayerDamageIfOwned;
        }
        if (!nestedExplicit.contains("InvulnerableIfOwned")) {
            currentSettings.invulnerableIfOwned = parentSettings.invulnerableIfOwned;
        }
    }

    private void inheritCommandSection(@Nonnull TwCompanionConfig parent,
                                       @Nonnull Set<String> explicitTopLevelKeys,
                                       @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Command")) {
            command = parent.getCommand().copy();
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("Command");
        if (nestedExplicit == null) {
            return;
        }
        CommandSettings parentCommand = parent.getCommand();
        CommandSettings currentCommand = getCommand();
        if (!nestedExplicit.contains("ReturnHomeTeleportDistance")) {
            currentCommand.returnHomeTeleportDistance = parentCommand.returnHomeTeleportDistance;
        }
        if (!nestedExplicit.contains("ReturnHomePathDistanceBeforeTeleport")) {
            currentCommand.returnHomePathDistanceBeforeTeleport = parentCommand.returnHomePathDistanceBeforeTeleport;
        }
        if (!nestedExplicit.contains("ReturnHomeTeleportDelayMs")) {
            currentCommand.returnHomeTeleportDelayMs = parentCommand.returnHomeTeleportDelayMs;
        }
        if (!nestedExplicit.contains("RecallSafeSpawnDistance")) {
            currentCommand.recallSafeSpawnDistance = parentCommand.recallSafeSpawnDistance;
        }
        if (!nestedExplicit.contains("RecallForceRelocateDistance")) {
            currentCommand.recallForceRelocateDistance = parentCommand.recallForceRelocateDistance;
        }
        if (!nestedExplicit.contains("DeadRespawnEnabled")) {
            currentCommand.deadRespawnEnabled = parentCommand.deadRespawnEnabled;
        }
        if (!hasDeadRespawnCooldownOverride(nestedExplicit)) {
            currentCommand.deadRespawnCooldownMs = parentCommand.deadRespawnCooldownMs;
        }
        if (!nestedExplicit.contains("DeadRespawnFollowRetryDelayMs")) {
            currentCommand.deadRespawnFollowRetryDelayMs = parentCommand.deadRespawnFollowRetryDelayMs;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceClose")) {
            currentCommand.deadRespawnDistanceClose = parentCommand.deadRespawnDistanceClose;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceNear")) {
            currentCommand.deadRespawnDistanceNear = parentCommand.deadRespawnDistanceNear;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceMid")) {
            currentCommand.deadRespawnDistanceMid = parentCommand.deadRespawnDistanceMid;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceFar")) {
            currentCommand.deadRespawnDistanceFar = parentCommand.deadRespawnDistanceFar;
        }
        if (!nestedExplicit.contains("PlacementMinRelativeY")) {
            currentCommand.placementMinRelativeY = parentCommand.placementMinRelativeY;
        }
        if (!nestedExplicit.contains("PlacementMaxRelativeY")) {
            currentCommand.placementMaxRelativeY = parentCommand.placementMaxRelativeY;
        }
        if (!nestedExplicit.contains("Travel")) {
            currentCommand.travel = parentCommand.travel.copy();
        } else if (currentCommand.travel != null && parentCommand.travel != null) {
            if (!nestedExplicit.contains("Travel.CrossWorldRecallEnabled")) {
                currentCommand.travel.crossWorldRecallEnabled = parentCommand.travel.crossWorldRecallEnabled;
            }
            if (!nestedExplicit.contains("Travel.OnTransferFailure")) {
                currentCommand.travel.onTransferFailure = parentCommand.travel.onTransferFailure;
            }
        } else if (currentCommand.travel == null) {
            currentCommand.travel = parentCommand.travel == null ? null : parentCommand.travel.copy();
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

    private static String[] normalizeStateFilter(@Nullable String[] rawStates) {
        if (rawStates == null || rawStates.length == 0) {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
        ArrayList<String> normalized = new ArrayList<>(rawStates.length);
        for (String value : rawStates) {
            String normalizedValue = normalizeStateKey(value);
            if (normalizedValue != null) {
                normalized.add(normalizedValue);
            }
        }
        return normalized.isEmpty() ? ArrayUtil.EMPTY_STRING_ARRAY : normalized.toArray(String[]::new);
    }

    @Nullable
    private static String normalizeStateKey(@Nullable String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String normalized = state.trim();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean isStateAllowedByFilters(@Nullable String state, @Nullable String[] filters) {
        if (filters == null || filters.length == 0) {
            return true;
        }
        String normalizedState = normalizeStateKey(state);
        if (normalizedState == null) {
            return false;
        }
        for (String filter : filters) {
            if (matchesStateFilter(normalizedState, filter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesStateFilter(@Nonnull String normalizedState, @Nullable String filter) {
        String normalizedFilter = normalizeStateKey(filter);
        if (normalizedFilter == null) {
            return false;
        }
        if (normalizedState.equals(normalizedFilter) || normalizedState.startsWith(normalizedFilter)) {
            return true;
        }
        String[] segments = normalizedState.split("[^a-z0-9]+");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (segment.equals(normalizedFilter) || segment.startsWith(normalizedFilter)) {
                return true;
            }
        }
        return false;
    }

    public enum TransferFailurePolicy {
        QueueForRecall,
        MarkLost,
        Ignore;

        @Nonnull
        static TransferFailurePolicy parse(@Nullable String raw, @Nullable TransferFailurePolicy fallback) {
            if (raw != null && !raw.isBlank()) {
                for (TransferFailurePolicy candidate : values()) {
                    if (candidate.name().equalsIgnoreCase(raw.trim())) {
                        return candidate;
                    }
                }
            }
            return fallback != null ? fallback : QueueForRecall;
        }
    }

    public static final class OwnershipProtectionSettings {
        private boolean blockOwnerDamage = true;
        private boolean blockAllPlayerDamageIfOwned;
        private boolean invulnerableIfOwned;

        public boolean isBlockOwnerDamage() {
            return blockOwnerDamage;
        }

        public boolean isBlockAllPlayerDamageIfOwned() {
            return blockAllPlayerDamageIfOwned;
        }

        public boolean isInvulnerableIfOwned() {
            return invulnerableIfOwned;
        }

        private OwnershipProtectionSettings copy() {
            OwnershipProtectionSettings copy = new OwnershipProtectionSettings();
            copy.blockOwnerDamage = blockOwnerDamage;
            copy.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
            copy.invulnerableIfOwned = invulnerableIfOwned;
            return copy;
        }
    }

    public static final class TravelSettings {
        private boolean crossWorldRecallEnabled = DEFAULT_CROSS_WORLD_RECALL_ENABLED;
        private TransferFailurePolicy onTransferFailure = TransferFailurePolicy.QueueForRecall;
        private boolean followMasterOnWorldChange = DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE;
        private String[] followMasterOnWorldChangeStateFilter =
                normalizeStateFilter(DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE_STATE_FILTER);

        public boolean isCrossWorldRecallEnabled() {
            return crossWorldRecallEnabled;
        }

        @Nonnull
        public TransferFailurePolicy getOnTransferFailure() {
            return onTransferFailure != null ? onTransferFailure : TransferFailurePolicy.QueueForRecall;
        }

        public boolean isFollowMasterOnWorldChange() {
            return followMasterOnWorldChange;
        }

        public String[] getFollowMasterOnWorldChangeStateFilter() {
            return followMasterOnWorldChangeStateFilter != null
                    ? followMasterOnWorldChangeStateFilter.clone()
                    : ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public boolean isStateAllowedForWorldChange(@Nullable String state) {
            if (!followMasterOnWorldChange) {
                return false;
            }
            return isStateAllowedByFilters(state, followMasterOnWorldChangeStateFilter);
        }

        private TravelSettings copy() {
            TravelSettings copy = new TravelSettings();
            copy.crossWorldRecallEnabled = crossWorldRecallEnabled;
            copy.onTransferFailure = getOnTransferFailure();
            copy.followMasterOnWorldChange = followMasterOnWorldChange;
            copy.followMasterOnWorldChangeStateFilter = getFollowMasterOnWorldChangeStateFilter();
            return copy;
        }
    }

    public static final class CommandSettings {
        private double returnHomeTeleportDistance = DEFAULT_RETURN_HOME_TELEPORT_DISTANCE;
        private double returnHomePathDistanceBeforeTeleport = DEFAULT_RETURN_HOME_PATH_DISTANCE_BEFORE_TELEPORT;
        private int returnHomeTeleportDelayMs = DEFAULT_RETURN_HOME_TELEPORT_DELAY_MS;
        private double recallSafeSpawnDistance = DEFAULT_RECALL_SAFE_SPAWN_DISTANCE;
        private double recallForceRelocateDistance = DEFAULT_RECALL_FORCE_RELOCATE_DISTANCE;
        private boolean deadRespawnEnabled = DEFAULT_DEAD_RESPAWN_ENABLED;
        private int deadRespawnCooldownMs = DEFAULT_DEAD_RESPAWN_COOLDOWN_MS;
        private int deadRespawnFollowRetryDelayMs = DEFAULT_DEAD_RESPAWN_FOLLOW_RETRY_DELAY_MS;
        private double deadRespawnDistanceClose = DEFAULT_DEAD_RESPAWN_DISTANCE_CLOSE;
        private double deadRespawnDistanceNear = DEFAULT_DEAD_RESPAWN_DISTANCE_NEAR;
        private double deadRespawnDistanceMid = DEFAULT_DEAD_RESPAWN_DISTANCE_MID;
        private double deadRespawnDistanceFar = DEFAULT_DEAD_RESPAWN_DISTANCE_FAR;
        private double placementMinRelativeY = DEFAULT_PLACEMENT_MIN_RELATIVE_Y;
        private double placementMaxRelativeY = DEFAULT_PLACEMENT_MAX_RELATIVE_Y;
        private TravelSettings travel = new TravelSettings();

        public double getReturnHomeTeleportDistance() {
            return returnHomeTeleportDistance;
        }

        public double getReturnHomePathDistanceBeforeTeleport() {
            return returnHomePathDistanceBeforeTeleport;
        }

        public int getReturnHomeTeleportDelayMs() {
            return returnHomeTeleportDelayMs;
        }

        public double getRecallSafeSpawnDistance() {
            return recallSafeSpawnDistance;
        }

        public double getRecallForceRelocateDistance() {
            return recallForceRelocateDistance;
        }

        public boolean isDeadRespawnEnabled() {
            return deadRespawnEnabled;
        }

        public int getDeadRespawnCooldownMs() {
            return deadRespawnCooldownMs;
        }

        public int getDeadRespawnFollowRetryDelayMs() {
            return deadRespawnFollowRetryDelayMs;
        }

        public double getDeadRespawnDistanceClose() {
            return deadRespawnDistanceClose;
        }

        public double getDeadRespawnDistanceNear() {
            return deadRespawnDistanceNear;
        }

        public double getDeadRespawnDistanceMid() {
            return deadRespawnDistanceMid;
        }

        public double getDeadRespawnDistanceFar() {
            return deadRespawnDistanceFar;
        }

        public double getPlacementMinRelativeY() {
            return placementMinRelativeY;
        }

        public double getPlacementMaxRelativeY() {
            return placementMaxRelativeY;
        }

        public TravelSettings getTravel() {
            return travel != null ? travel : new TravelSettings();
        }

        private CommandSettings copy() {
            CommandSettings copy = new CommandSettings();
            copy.returnHomeTeleportDistance = returnHomeTeleportDistance;
            copy.returnHomePathDistanceBeforeTeleport = returnHomePathDistanceBeforeTeleport;
            copy.returnHomeTeleportDelayMs = returnHomeTeleportDelayMs;
            copy.recallSafeSpawnDistance = recallSafeSpawnDistance;
            copy.recallForceRelocateDistance = recallForceRelocateDistance;
            copy.deadRespawnEnabled = deadRespawnEnabled;
            copy.deadRespawnCooldownMs = deadRespawnCooldownMs;
            copy.deadRespawnFollowRetryDelayMs = deadRespawnFollowRetryDelayMs;
            copy.deadRespawnDistanceClose = deadRespawnDistanceClose;
            copy.deadRespawnDistanceNear = deadRespawnDistanceNear;
            copy.deadRespawnDistanceMid = deadRespawnDistanceMid;
            copy.deadRespawnDistanceFar = deadRespawnDistanceFar;
            copy.placementMinRelativeY = placementMinRelativeY;
            copy.placementMaxRelativeY = placementMaxRelativeY;
            copy.travel = getTravel().copy();
            return copy;
        }
    }

    /**
     * Fully-resolved companion settings for a specific role.
     *
     * <p>When no role-scoped config is found, values fall back to {@link TwGlobalConfig}
     * to preserve backward compatibility.
     */
    public static final class EffectiveSettings {
        private final boolean blockOwnerDamage;
        private final boolean blockAllPlayerDamageIfOwned;
        private final boolean invulnerableIfOwned;
        private final double returnHomeTeleportDistance;
        private final double returnHomePathDistanceBeforeTeleport;
        private final int returnHomeTeleportDelayMs;
        private final double recallSafeSpawnDistance;
        private final double recallForceRelocateDistance;
        private final boolean deadRespawnEnabled;
        private final int deadRespawnCooldownMs;
        private final int deadRespawnFollowRetryDelayMs;
        private final double deadRespawnDistanceClose;
        private final double deadRespawnDistanceNear;
        private final double deadRespawnDistanceMid;
        private final double deadRespawnDistanceFar;
        private final double placementMinRelativeY;
        private final double placementMaxRelativeY;
        private final boolean crossWorldRecallEnabled;
        private final TransferFailurePolicy onTransferFailure;
        private final boolean followMasterOnWorldChange;
        private final String[] followMasterOnWorldChangeStateFilter;

        private EffectiveSettings(boolean blockOwnerDamage,
                                  boolean blockAllPlayerDamageIfOwned,
                                  boolean invulnerableIfOwned,
                                  double returnHomeTeleportDistance,
                                  double returnHomePathDistanceBeforeTeleport,
                                  int returnHomeTeleportDelayMs,
                                  double recallSafeSpawnDistance,
                                  double recallForceRelocateDistance,
                                  boolean deadRespawnEnabled,
                                  int deadRespawnCooldownMs,
                                  int deadRespawnFollowRetryDelayMs,
                                  double deadRespawnDistanceClose,
                                  double deadRespawnDistanceNear,
                                  double deadRespawnDistanceMid,
                                  double deadRespawnDistanceFar,
                                  double placementMinRelativeY,
                                  double placementMaxRelativeY,
                                  boolean crossWorldRecallEnabled,
                                  TransferFailurePolicy onTransferFailure,
                                  boolean followMasterOnWorldChange,
                                  @Nullable String[] followMasterOnWorldChangeStateFilter) {
            this.blockOwnerDamage = blockOwnerDamage;
            this.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
            this.invulnerableIfOwned = invulnerableIfOwned;
            this.returnHomeTeleportDistance = returnHomeTeleportDistance;
            this.returnHomePathDistanceBeforeTeleport = returnHomePathDistanceBeforeTeleport;
            this.returnHomeTeleportDelayMs = returnHomeTeleportDelayMs;
            this.recallSafeSpawnDistance = recallSafeSpawnDistance;
            this.recallForceRelocateDistance = recallForceRelocateDistance;
            this.deadRespawnEnabled = deadRespawnEnabled;
            this.deadRespawnCooldownMs = deadRespawnCooldownMs;
            this.deadRespawnFollowRetryDelayMs = deadRespawnFollowRetryDelayMs;
            this.deadRespawnDistanceClose = deadRespawnDistanceClose;
            this.deadRespawnDistanceNear = deadRespawnDistanceNear;
            this.deadRespawnDistanceMid = deadRespawnDistanceMid;
            this.deadRespawnDistanceFar = deadRespawnDistanceFar;
            this.placementMinRelativeY = placementMinRelativeY;
            this.placementMaxRelativeY = placementMaxRelativeY;
            this.crossWorldRecallEnabled = crossWorldRecallEnabled;
            this.onTransferFailure = onTransferFailure != null ? onTransferFailure : TransferFailurePolicy.QueueForRecall;
            this.followMasterOnWorldChange = followMasterOnWorldChange;
            this.followMasterOnWorldChangeStateFilter =
                    followMasterOnWorldChangeStateFilter != null
                            ? normalizeStateFilter(followMasterOnWorldChangeStateFilter)
                            : ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public static EffectiveSettings from(@Nullable TwCompanionConfig scoped, @Nullable TwGlobalConfig global) {
            if (scoped != null && scoped.isEnabled()) {
                OwnershipProtectionSettings ownership = scoped.getOwnershipProtection();
                CommandSettings command = scoped.getCommand();
                TravelSettings travel = command.getTravel();
                return new EffectiveSettings(
                        ownership.isBlockOwnerDamage(),
                        ownership.isBlockAllPlayerDamageIfOwned(),
                        ownership.isInvulnerableIfOwned(),
                        command.getReturnHomeTeleportDistance(),
                        command.getReturnHomePathDistanceBeforeTeleport(),
                        command.getReturnHomeTeleportDelayMs(),
                        command.getRecallSafeSpawnDistance(),
                        command.getRecallForceRelocateDistance(),
                        command.isDeadRespawnEnabled(),
                        command.getDeadRespawnCooldownMs(),
                        command.getDeadRespawnFollowRetryDelayMs(),
                        command.getDeadRespawnDistanceClose(),
                        command.getDeadRespawnDistanceNear(),
                        command.getDeadRespawnDistanceMid(),
                        command.getDeadRespawnDistanceFar(),
                        command.getPlacementMinRelativeY(),
                        command.getPlacementMaxRelativeY(),
                        travel.isCrossWorldRecallEnabled(),
                        travel.getOnTransferFailure(),
                        travel.isFollowMasterOnWorldChange(),
                        travel.getFollowMasterOnWorldChangeStateFilter()
                );
            }
            return fromGlobal(global);
        }

        public static EffectiveSettings fromGlobal(@Nullable TwGlobalConfig global) {
            return new EffectiveSettings(
                    global != null && global.isBlockOwnerDamage(),
                    global != null && global.isBlockAllPlayerDamageIfOwned(),
                    global != null && global.isInvulnerableIfOwned(),
                    global != null ? global.getCommandReturnHomeTeleportDistance() : DEFAULT_RETURN_HOME_TELEPORT_DISTANCE,
                    global != null
                            ? global.getCommandReturnHomePathDistanceBeforeTeleport()
                            : DEFAULT_RETURN_HOME_PATH_DISTANCE_BEFORE_TELEPORT,
                    global != null ? global.getCommandReturnHomeTeleportDelayMs() : DEFAULT_RETURN_HOME_TELEPORT_DELAY_MS,
                    global != null ? global.getCommandRecallSafeSpawnDistance() : DEFAULT_RECALL_SAFE_SPAWN_DISTANCE,
                    global != null
                            ? global.getCommandRecallForceRelocateDistance()
                            : DEFAULT_RECALL_FORCE_RELOCATE_DISTANCE,
                    global == null || global.isCommandDeadRespawnEnabled(),
                    global != null ? global.getCommandDeadRespawnCooldownMs() : DEFAULT_DEAD_RESPAWN_COOLDOWN_MS,
                    global != null
                            ? global.getCommandDeadRespawnFollowRetryDelayMs()
                            : DEFAULT_DEAD_RESPAWN_FOLLOW_RETRY_DELAY_MS,
                    global != null ? global.getCommandDeadRespawnDistanceClose() : DEFAULT_DEAD_RESPAWN_DISTANCE_CLOSE,
                    global != null ? global.getCommandDeadRespawnDistanceNear() : DEFAULT_DEAD_RESPAWN_DISTANCE_NEAR,
                    global != null ? global.getCommandDeadRespawnDistanceMid() : DEFAULT_DEAD_RESPAWN_DISTANCE_MID,
                    global != null ? global.getCommandDeadRespawnDistanceFar() : DEFAULT_DEAD_RESPAWN_DISTANCE_FAR,
                    global != null ? global.getCommandPlacementMinRelativeY() : DEFAULT_PLACEMENT_MIN_RELATIVE_Y,
                    global != null ? global.getCommandPlacementMaxRelativeY() : DEFAULT_PLACEMENT_MAX_RELATIVE_Y,
                    DEFAULT_CROSS_WORLD_RECALL_ENABLED,
                    TransferFailurePolicy.QueueForRecall,
                    DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE,
                    DEFAULT_FOLLOW_MASTER_ON_WORLD_CHANGE_STATE_FILTER
            );
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

        public double getReturnHomeTeleportDistance() {
            return returnHomeTeleportDistance;
        }

        public double getReturnHomePathDistanceBeforeTeleport() {
            return returnHomePathDistanceBeforeTeleport;
        }

        public int getReturnHomeTeleportDelayMs() {
            return returnHomeTeleportDelayMs;
        }

        public double getRecallSafeSpawnDistance() {
            return recallSafeSpawnDistance;
        }

        public double getRecallForceRelocateDistance() {
            return recallForceRelocateDistance;
        }

        public boolean isDeadRespawnEnabled() {
            return deadRespawnEnabled;
        }

        public int getDeadRespawnCooldownMs() {
            return deadRespawnCooldownMs;
        }

        public int getDeadRespawnFollowRetryDelayMs() {
            return deadRespawnFollowRetryDelayMs;
        }

        public double getDeadRespawnDistanceClose() {
            return deadRespawnDistanceClose;
        }

        public double getDeadRespawnDistanceNear() {
            return deadRespawnDistanceNear;
        }

        public double getDeadRespawnDistanceMid() {
            return deadRespawnDistanceMid;
        }

        public double getDeadRespawnDistanceFar() {
            return deadRespawnDistanceFar;
        }

        public double getPlacementMinRelativeY() {
            return placementMinRelativeY;
        }

        public double getPlacementMaxRelativeY() {
            return placementMaxRelativeY;
        }

        public boolean isCrossWorldRecallEnabled() {
            return crossWorldRecallEnabled;
        }

        @Nonnull
        public TransferFailurePolicy getOnTransferFailure() {
            return onTransferFailure;
        }

        public boolean isFollowMasterOnWorldChange() {
            return followMasterOnWorldChange;
        }

        public String[] getFollowMasterOnWorldChangeStateFilter() {
            return followMasterOnWorldChangeStateFilter.clone();
        }

        public boolean isWorldChangeStateAllowed(@Nullable String state) {
            if (!followMasterOnWorldChange) {
                return false;
            }
            return isStateAllowedByFilters(state, followMasterOnWorldChangeStateFilter);
        }
    }
}

