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
    private static final BuilderCodec<OwnershipProtectionSettings> OWNERSHIP_PROTECTION_CODEC = BuilderCodec.builder(
            OwnershipProtectionSettings.class,
            OwnershipProtectionSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("BlockOwnerDamage", Codec.BOOLEAN),
                    (settings, value) -> settings.blockOwnerDamage = value,
                    settings -> settings.blockOwnerDamage
            )
            .documentation("Blocks owner damage when enabled.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockAllPlayerDamageIfOwned", Codec.BOOLEAN),
                    (settings, value) -> settings.blockAllPlayerDamageIfOwned = value,
                    settings -> settings.blockAllPlayerDamageIfOwned
            )
            .documentation("Blocks all player damage if owned when enabled.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InvulnerableIfOwned", Codec.BOOLEAN),
                    (settings, value) -> settings.invulnerableIfOwned = value,
                    settings -> settings.invulnerableIfOwned
            )
            .documentation("If true, owned NPCs cannot take damage from normal sources.")
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
            .documentation("Turns this section on or off.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Priority used when multiple configs apply; higher values take precedence.")
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
            .<TwCompanionCommandSettings>append(
                    new KeyedCodec<>(
                            "Command",
                            TwCompanionCommandSettingsCodec.CODEC
                    ),
                    (asset, value) -> asset.command = value == null
                            ? new TwCompanionCommandSettings()
                            : value,
                    asset -> asset.command
            )
            .documentation("Companion command runtime settings. Inheritance: omitted section inherits from parent; when "
                    + "present, only explicitly defined nested fields override parent. Nested Revive is authoritative "
                    + "over legacy DeadRespawn fields; explicit Costs and Summon warning arrays replace parent arrays.")
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
    private TwCompanionCommandSettings command =
            new TwCompanionCommandSettings();

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

    public TwCompanionCommandSettings getCommand() {
        return command == null
                ? new TwCompanionCommandSettings()
                : command;
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
        TwCompanionCommandInheritance.inheritMissing(
                parent.getCommand(),
                getCommand(),
                nestedExplicit
        );
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
        private final TwCompanionReviveSettings revive;
        private final TwCompanionSummonSettings summon;
        private final TwCompanionFlightToggleSettings flightToggle;
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
                                  TwCompanionReviveSettings revive,
                                  TwCompanionSummonSettings summon,
                                  TwCompanionFlightToggleSettings flightToggle,
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
            this.revive = revive != null
                    ? revive.copy()
                    : new TwCompanionReviveSettings();
            this.summon = summon != null
                    ? summon.copy()
                    : new TwCompanionSummonSettings();
            this.flightToggle = flightToggle != null
                    ? flightToggle.copy()
                    : new TwCompanionFlightToggleSettings();
            this.crossWorldRecallEnabled = crossWorldRecallEnabled;
            this.onTransferFailure = onTransferFailure != null ? onTransferFailure : TransferFailurePolicy.QueueForRecall;
            this.followMasterOnWorldChange = followMasterOnWorldChange;
            this.followMasterOnWorldChangeStateFilter =
                    followMasterOnWorldChangeStateFilter != null
                            ? TwCompanionCommandSettings.TravelSettings
                                    .normalizeStateFilter(
                                            followMasterOnWorldChangeStateFilter
                                    )
                            : ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public static EffectiveSettings from(@Nullable TwCompanionConfig scoped, @Nullable TwGlobalConfig global) {
            if (scoped != null && scoped.isEnabled()) {
                OwnershipProtectionSettings ownership = scoped.getOwnershipProtection();
                TwCompanionCommandSettings command = scoped.getCommand();
                TwCompanionCommandSettings.TravelSettings travel =
                        command.getTravel();
                TwCompanionReviveSettings revive =
                        command.getRevive().copy();
                if (global != null
                        && !global.isCommandDeadRespawnEnabled()) {
                    revive.setEnabled(false);
                }
                boolean blockOwnerDamage = global != null
                        ? global.isBlockOwnerDamage()
                        : ownership.isBlockOwnerDamage();
                boolean blockAllPlayerDamageIfOwned = global != null
                        ? global.isBlockAllPlayerDamageIfOwned()
                        : ownership.isBlockAllPlayerDamageIfOwned();
                boolean invulnerableIfOwned = global != null
                        ? global.isInvulnerableIfOwned()
                        : ownership.isInvulnerableIfOwned();
                return new EffectiveSettings(
                        blockOwnerDamage,
                        blockAllPlayerDamageIfOwned,
                        invulnerableIfOwned,
                        command.getReturnHomeTeleportDistance(),
                        command.getReturnHomePathDistanceBeforeTeleport(),
                        command.getReturnHomeTeleportDelayMs(),
                        command.getRecallSafeSpawnDistance(),
                        command.getRecallForceRelocateDistance(),
                        global != null
                                ? global.isCommandDeadRespawnEnabled()
                                : command.isDeadRespawnEnabled(),
                        command.getDeadRespawnCooldownMs(),
                        command.getDeadRespawnFollowRetryDelayMs(),
                        command.getDeadRespawnDistanceClose(),
                        command.getDeadRespawnDistanceNear(),
                        command.getDeadRespawnDistanceMid(),
                        command.getDeadRespawnDistanceFar(),
                        command.getPlacementMinRelativeY(),
                        command.getPlacementMaxRelativeY(),
                        revive,
                        command.getSummon(),
                        command.getFlightToggle(),
                        travel.isCrossWorldRecallEnabled(),
                        travel.getOnTransferFailure(),
                        travel.isFollowMasterOnWorldChange(),
                        travel.getFollowMasterOnWorldChangeStateFilter()
                );
            }
            return fromGlobal(global);
        }

        public static EffectiveSettings fromGlobal(@Nullable TwGlobalConfig global) {
            TwCompanionCommandSettings defaults =
                    new TwCompanionCommandSettings();
            TwCompanionCommandSettings.TravelSettings travel =
                    defaults.getTravel();
            TwCompanionReviveSettings revive =
                    defaults.getRevive().copy();
            if (global != null) {
                revive.setEnabled(global.isCommandDeadRespawnEnabled());
                revive.setGameplayCooldownMs(
                        global.getCommandDeadRespawnCooldownMs()
                );
            }
            return new EffectiveSettings(
                    global != null && global.isBlockOwnerDamage(),
                    global != null && global.isBlockAllPlayerDamageIfOwned(),
                    global != null && global.isInvulnerableIfOwned(),
                    global != null
                            ? global.getCommandReturnHomeTeleportDistance()
                            : defaults.getReturnHomeTeleportDistance(),
                    global != null
                            ? global.getCommandReturnHomePathDistanceBeforeTeleport()
                            : defaults.getReturnHomePathDistanceBeforeTeleport(),
                    global != null
                            ? global.getCommandReturnHomeTeleportDelayMs()
                            : defaults.getReturnHomeTeleportDelayMs(),
                    global != null
                            ? global.getCommandRecallSafeSpawnDistance()
                            : defaults.getRecallSafeSpawnDistance(),
                    global != null
                            ? global.getCommandRecallForceRelocateDistance()
                            : defaults.getRecallForceRelocateDistance(),
                    global == null || global.isCommandDeadRespawnEnabled(),
                    global != null
                            ? global.getCommandDeadRespawnCooldownMs()
                            : defaults.getDeadRespawnCooldownMs(),
                    global != null
                            ? global.getCommandDeadRespawnFollowRetryDelayMs()
                            : defaults.getDeadRespawnFollowRetryDelayMs(),
                    global != null
                            ? global.getCommandDeadRespawnDistanceClose()
                            : defaults.getDeadRespawnDistanceClose(),
                    global != null
                            ? global.getCommandDeadRespawnDistanceNear()
                            : defaults.getDeadRespawnDistanceNear(),
                    global != null
                            ? global.getCommandDeadRespawnDistanceMid()
                            : defaults.getDeadRespawnDistanceMid(),
                    global != null
                            ? global.getCommandDeadRespawnDistanceFar()
                            : defaults.getDeadRespawnDistanceFar(),
                    global != null
                            ? global.getCommandPlacementMinRelativeY()
                            : defaults.getPlacementMinRelativeY(),
                    global != null
                            ? global.getCommandPlacementMaxRelativeY()
                            : defaults.getPlacementMaxRelativeY(),
                    revive,
                    defaults.getSummon(),
                    defaults.getFlightToggle(),
                    travel.isCrossWorldRecallEnabled(),
                    travel.getOnTransferFailure(),
                    travel.isFollowMasterOnWorldChange(),
                    travel.getFollowMasterOnWorldChangeStateFilter()
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

        @Nonnull
        public TwCompanionReviveSettings getRevive() {
            return revive.copy();
        }

        @Nonnull
        public TwCompanionSummonSettings getSummon() {
            return summon.copy();
        }

        @Nonnull
        public TwCompanionFlightToggleSettings getFlightToggle() {
            return flightToggle.copy();
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
            return TwCompanionCommandSettings.TravelSettings
                    .isStateAllowedByFilters(
                            state,
                            followMasterOnWorldChangeStateFilter
                    );
        }
    }
}
