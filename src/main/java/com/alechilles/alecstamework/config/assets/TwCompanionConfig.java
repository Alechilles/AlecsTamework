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

    private static final BuilderCodec<OwnershipProtectionSettings> OWNERSHIP_PROTECTION_CODEC = BuilderCodec.builder(
            OwnershipProtectionSettings.class,
            OwnershipProtectionSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("BlockOwnerDamage", Codec.BOOLEAN),
                    (settings, value) -> settings.blockOwnerDamage = value,
                    settings -> settings.blockOwnerDamage
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockAllPlayerDamageIfOwned", Codec.BOOLEAN),
                    (settings, value) -> settings.blockAllPlayerDamageIfOwned = value,
                    settings -> settings.blockAllPlayerDamageIfOwned
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InvulnerableIfOwned", Codec.BOOLEAN),
                    (settings, value) -> settings.invulnerableIfOwned = value,
                    settings -> settings.invulnerableIfOwned
            )
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
            .add()
            .<Double>append(
                    new KeyedCodec<>("ReturnHomePathDistanceBeforeTeleport", Codec.DOUBLE),
                    (settings, value) -> settings.returnHomePathDistanceBeforeTeleport = value,
                    settings -> settings.returnHomePathDistanceBeforeTeleport
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ReturnHomeTeleportDelayMs", Codec.INTEGER),
                    (settings, value) -> settings.returnHomeTeleportDelayMs = value,
                    settings -> settings.returnHomeTeleportDelayMs
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallSafeSpawnDistance", Codec.DOUBLE),
                    (settings, value) -> settings.recallSafeSpawnDistance = value,
                    settings -> settings.recallSafeSpawnDistance
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallForceRelocateDistance", Codec.DOUBLE),
                    (settings, value) -> settings.recallForceRelocateDistance = value,
                    settings -> settings.recallForceRelocateDistance
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("DeadRespawnEnabled", Codec.BOOLEAN),
                    (settings, value) -> settings.deadRespawnEnabled = value,
                    settings -> settings.deadRespawnEnabled
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnCooldownMs", Codec.INTEGER),
                    (settings, value) -> settings.deadRespawnCooldownMs = value,
                    settings -> settings.deadRespawnCooldownMs
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnFollowRetryDelayMs", Codec.INTEGER),
                    (settings, value) -> settings.deadRespawnFollowRetryDelayMs = value,
                    settings -> settings.deadRespawnFollowRetryDelayMs
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceClose", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceClose = value,
                    settings -> settings.deadRespawnDistanceClose
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceNear", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceNear = value,
                    settings -> settings.deadRespawnDistanceNear
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceMid", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceMid = value,
                    settings -> settings.deadRespawnDistanceMid
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceFar", Codec.DOUBLE),
                    (settings, value) -> settings.deadRespawnDistanceFar = value,
                    settings -> settings.deadRespawnDistanceFar
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMinRelativeY", Codec.DOUBLE),
                    (settings, value) -> settings.placementMinRelativeY = value,
                    settings -> settings.placementMinRelativeY
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMaxRelativeY", Codec.DOUBLE),
                    (settings, value) -> settings.placementMaxRelativeY = value,
                    settings -> settings.placementMaxRelativeY
            )
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
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds
            )
            .add()
            .<OwnershipProtectionSettings>append(
                    new KeyedCodec<>("OwnershipProtection", OWNERSHIP_PROTECTION_CODEC),
                    (asset, value) -> asset.ownershipProtection = value == null
                            ? new OwnershipProtectionSettings()
                            : value,
                    asset -> asset.ownershipProtection
            )
            .add()
            .<CommandSettings>append(
                    new KeyedCodec<>("Command", COMMAND_CODEC),
                    (asset, value) -> asset.command = value == null ? new CommandSettings() : value,
                    asset -> asset.command
            )
            .add()
            .build();

    private static AssetStore<String, TwCompanionConfig, DefaultAssetMap<String, TwCompanionConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwCompanionConfig> ROLE_CACHE = Map.of();

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
        Map<String, TwCompanionConfig> cache = ROLE_CACHE;
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
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        return EffectiveSettings.from(scoped, global);
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
            if (candidateRoles == null || candidateRoles.length == 0) {
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
        if (!nestedExplicit.contains("DeadRespawnCooldownMs")) {
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
                                  double placementMaxRelativeY) {
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
        }

        public static EffectiveSettings from(@Nullable TwCompanionConfig scoped, @Nullable TwGlobalConfig global) {
            if (scoped != null && scoped.isEnabled()) {
                OwnershipProtectionSettings ownership = scoped.getOwnershipProtection();
                CommandSettings command = scoped.getCommand();
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
                        command.getPlacementMaxRelativeY()
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
                    global != null ? global.getCommandPlacementMaxRelativeY() : DEFAULT_PLACEMENT_MAX_RELATIVE_Y
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
    }
}
