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
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed coop runtime configuration for Tamework managed coop behavior.
 * Stored under {@code Server/Tamework/Items/Coops}.
 */
public final class TwCoopConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCoopConfig>>,
        TwParentFallbackAsset<TwCoopConfig> {
    private static final BuilderCodec<CapturePolicySettings> CAPTURE_POLICY_CODEC = BuilderCodec.builder(
            CapturePolicySettings.class,
            CapturePolicySettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
                    (settings, value) -> settings.requireTamed = value,
                    settings -> settings.requireTamed
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("OwnerRestricted", Codec.BOOLEAN),
                    (settings, value) -> settings.ownerRestricted = value,
                    settings -> settings.ownerRestricted
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
                    (settings, value) -> settings.requireOwner = value,
                    settings -> settings.requireOwner
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("ParticleSystem", Codec.STRING),
                    (settings, value) -> settings.particleSystem = value,
                    settings -> settings.particleSystem
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("SoundEvent", Codec.STRING),
                    (settings, value) -> settings.soundEvent = value,
                    settings -> settings.soundEvent
            )
            .add()
            .build();

    private static final BuilderCodec<SpawnOffsetSettings> SPAWN_OFFSET_CODEC = BuilderCodec.builder(
            SpawnOffsetSettings.class,
            SpawnOffsetSettings::new
    )
            .<Double>append(
                    new KeyedCodec<>("X", Codec.DOUBLE),
                    (settings, value) -> settings.x = value,
                    settings -> settings.x
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("Y", Codec.DOUBLE),
                    (settings, value) -> settings.y = value,
                    settings -> settings.y
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("Z", Codec.DOUBLE),
                    (settings, value) -> settings.z = value,
                    settings -> settings.z
            )
            .add()
            .build();

    private static final BuilderCodec<LifecycleRules> LIFECYCLE_RULES_CODEC = BuilderCodec.builder(
            LifecycleRules.class,
            LifecycleRules::new
    )
            .<Integer>append(
                    new KeyedCodec<>("MaxResidents", Codec.INTEGER),
                    (rules, value) -> rules.maxResidents = value,
                    rules -> rules.maxResidents
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ResidentRoamStartHour", Codec.INTEGER),
                    (rules, value) -> rules.residentRoamStartHour = value,
                    rules -> rules.residentRoamStartHour
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ResidentRoamEndHour", Codec.INTEGER),
                    (rules, value) -> rules.residentRoamEndHour = value,
                    rules -> rules.residentRoamEndHour
            )
            .add()
            .<SpawnOffsetSettings>append(
                    new KeyedCodec<>("ResidentSpawnOffset", SPAWN_OFFSET_CODEC),
                    (rules, value) -> rules.residentSpawnOffset = value == null ? new SpawnOffsetSettings() : value,
                    rules -> rules.residentSpawnOffset
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("CaptureWildNPCsInRange", Codec.BOOLEAN),
                    (rules, value) -> rules.captureWildNPCsInRange = value,
                    rules -> rules.captureWildNPCsInRange
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("WildCaptureRadius", Codec.DOUBLE),
                    (rules, value) -> rules.wildCaptureRadius = value,
                    rules -> rules.wildCaptureRadius
            )
            .add()
            .<String[]>append(
                    new KeyedCodec<>("AcceptedRoleIds", Codec.STRING_ARRAY),
                    (rules, value) -> rules.acceptedRoleIds = value == null ? new String[0] : value,
                    rules -> rules.acceptedRoleIds
            )
            .add()
            .build();

    private static final BuilderCodec<ProduceRules> PRODUCE_RULES_CODEC = BuilderCodec.builder(
            ProduceRules.class,
            ProduceRules::new
    )
            .<Map<String, String>>append(
                    new KeyedCodec<>("DropsByRole", MapCodec.STRING_HASH_MAP_CODEC),
                    (rules, value) -> rules.dropsByRole = value == null ? Map.of() : value,
                    rules -> rules.dropsByRole
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("IntervalGameHours", Codec.INTEGER),
                    (rules, value) -> rules.intervalGameHours = value,
                    rules -> rules.intervalGameHours
            )
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ItemsPerTick", Codec.INTEGER),
                    (rules, value) -> rules.itemsPerTick = value,
                    rules -> rules.itemsPerTick
            )
            .add()
            .build();

    private static final BuilderCodec<IdentityRules> IDENTITY_RULES_CODEC = BuilderCodec.builder(
            IdentityRules.class,
            IdentityRules::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("RequireSnapshotOnRelease", Codec.BOOLEAN),
                    (rules, value) -> rules.requireSnapshotOnRelease = value,
                    rules -> rules.requireSnapshotOnRelease
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("PreserveUUID", Codec.BOOLEAN),
                    (rules, value) -> rules.preserveUUID = value,
                    rules -> rules.preserveUUID
            )
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwCoopConfig> CODEC = AssetBuilderCodec.builder(
            TwCoopConfig.class,
            TwCoopConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Coop runtime settings for Alec's Tamework managed coop behavior.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value,
                    asset -> asset.enabled
            )
            .documentation("Enables this managed coop config.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value,
                    asset -> asset.priority
            )
            .documentation("Priority when multiple configs target the same coop id.")
            .add()
            .<String>append(
                    new KeyedCodec<>("CoopId", Codec.STRING),
                    (asset, value) -> asset.coopId = value,
                    asset -> asset.coopId
            )
            .documentation("Farming coop asset id to bind this config to.")
            .add()
            .<CapturePolicySettings>append(
                    new KeyedCodec<>("CapturePolicy", CAPTURE_POLICY_CODEC),
                    (asset, value) -> asset.capturePolicy = value == null ? new CapturePolicySettings() : value,
                    asset -> asset.capturePolicy
            )
            .documentation("Capture policy checks and effects. Inheritance: omitted section inherits from parent; "
                    + "when present, missing nested fields inherit.")
            .add()
            .<LifecycleRules>append(
                    new KeyedCodec<>("LifecycleRules", LIFECYCLE_RULES_CODEC),
                    (asset, value) -> asset.lifecycleRules = value == null ? new LifecycleRules() : value,
                    asset -> asset.lifecycleRules
            )
            .documentation("Managed coop lifecycle rules. Inheritance: omitted section inherits from parent; "
                    + "when present, missing nested fields inherit. Explicit arrays/maps replace parent values.")
            .add()
            .<ProduceRules>append(
                    new KeyedCodec<>("ProduceRules", PRODUCE_RULES_CODEC),
                    (asset, value) -> asset.produceRules = value == null ? new ProduceRules() : value,
                    asset -> asset.produceRules
            )
            .documentation("Managed coop produce rules. Inheritance: omitted section inherits from parent; "
                    + "when present, missing nested fields inherit. Explicit maps replace parent values.")
            .add()
            .<IdentityRules>append(
                    new KeyedCodec<>("IdentityRules", IDENTITY_RULES_CODEC),
                    (asset, value) -> asset.identityRules = value == null ? new IdentityRules() : value,
                    asset -> asset.identityRules
            )
            .documentation("Identity restoration rules. Inheritance: omitted section inherits from parent; "
                    + "when present, missing nested fields inherit.")
            .add()
            .build();

    private static AssetStore<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object COOP_CACHE_LOCK = new Object();
    private static volatile boolean COOP_CACHE_DIRTY = true;
    private static volatile Map<String, TwCoopConfig> COOP_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String coopId;
    private CapturePolicySettings capturePolicy = new CapturePolicySettings();
    private LifecycleRules lifecycleRules = new LifecycleRules();
    private ProduceRules produceRules = new ProduceRules();
    private IdentityRules identityRules = new IdentityRules();

    public static AssetStore<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwCoopConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCoopConfig> getAssetMap() {
        AssetStore<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwCoopConfig> assetMap = (DefaultAssetMap<String, TwCoopConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearCoopCache() {
        INHERITANCE_CACHE_DIRTY = true;
        COOP_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwCoopConfig resolveForCoop(@Nullable String coopId) {
        String key = normalizeIdentifier(coopId);
        if (key == null) {
            return null;
        }
        DefaultAssetMap<String, TwCoopConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwCoopConfig> cache = COOP_CACHE;
        if (COOP_CACHE_DIRTY || cache == null) {
            synchronized (COOP_CACHE_LOCK) {
                if (COOP_CACHE_DIRTY || COOP_CACHE == null) {
                    COOP_CACHE = buildCoopCache(assetMap);
                    COOP_CACHE_DIRTY = false;
                }
                cache = COOP_CACHE;
            }
        }
        return cache.get(key);
    }

    @Nullable
    public static TwCoopConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwCoopConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwCoopConfig> map = assetMap.getAssetMap();
        TwCoopConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        for (TwCoopConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(configId)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwCoopConfig> buildCoopCache(DefaultAssetMap<String, TwCoopConfig> assetMap) {
        Map<String, TwCoopConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwCoopConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            String key = normalizeIdentifier(candidate.getCoopId());
            if (key == null) {
                continue;
            }
            TwCoopConfig existing = cache.get(key);
            if (existing == null || shouldPreferCandidate(candidate, existing)) {
                cache.put(key, candidate);
            }
        }
        return cache;
    }

    private static boolean shouldPreferCandidate(@Nonnull TwCoopConfig candidate,
                                                 @Nonnull TwCoopConfig existing) {
        int candidatePriority = candidate.getPriority();
        int existingPriority = existing.getPriority();
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority;
        }
        String candidateId = candidate.getId();
        String existingId = existing.getId();
        if (candidateId == null && existingId == null) {
            return false;
        }
        if (candidateId == null) {
            return false;
        }
        if (existingId == null) {
            return true;
        }
        return candidateId.compareToIgnoreCase(existingId) < 0;
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwCoopConfig> assetMap) {
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

    @Nullable
    private static String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    protected TwCoopConfig() {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwCoopConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCoopConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("CoopId")) coopId = parent.coopId;

        if (!explicitTopLevelKeys.contains("CapturePolicy")) {
            capturePolicy = parent.capturePolicy;
        } else {
            inheritCapturePolicySection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "CapturePolicy"));
        }

        if (!explicitTopLevelKeys.contains("LifecycleRules")) {
            lifecycleRules = parent.lifecycleRules;
        } else {
            inheritLifecycleSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "LifecycleRules"));
        }

        if (!explicitTopLevelKeys.contains("ProduceRules")) {
            produceRules = parent.produceRules;
        } else {
            inheritProduceSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "ProduceRules"));
        }

        if (!explicitTopLevelKeys.contains("IdentityRules")) {
            identityRules = parent.identityRules;
        } else {
            inheritIdentitySection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "IdentityRules"));
        }
    }

    private void inheritCapturePolicySection(@Nonnull TwCoopConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (capturePolicy == null) {
            capturePolicy = parent.capturePolicy;
            return;
        }
        if (parent.capturePolicy == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("RequireTamed")) capturePolicy.requireTamed = parent.capturePolicy.requireTamed;
        if (!nestedExplicitKeys.contains("OwnerRestricted")) capturePolicy.ownerRestricted = parent.capturePolicy.ownerRestricted;
        if (!nestedExplicitKeys.contains("RequireOwner")) capturePolicy.requireOwner = parent.capturePolicy.requireOwner;
        if (!nestedExplicitKeys.contains("ParticleSystem")) capturePolicy.particleSystem = parent.capturePolicy.particleSystem;
        if (!nestedExplicitKeys.contains("SoundEvent")) capturePolicy.soundEvent = parent.capturePolicy.soundEvent;
    }

    private void inheritLifecycleSection(@Nonnull TwCoopConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (lifecycleRules == null) {
            lifecycleRules = parent.lifecycleRules;
            return;
        }
        if (parent.lifecycleRules == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("MaxResidents")) lifecycleRules.maxResidents = parent.lifecycleRules.maxResidents;
        if (!nestedExplicitKeys.contains("ResidentRoamStartHour")) lifecycleRules.residentRoamStartHour = parent.lifecycleRules.residentRoamStartHour;
        if (!nestedExplicitKeys.contains("ResidentRoamEndHour")) lifecycleRules.residentRoamEndHour = parent.lifecycleRules.residentRoamEndHour;
        if (!nestedExplicitKeys.contains("CaptureWildNPCsInRange")) lifecycleRules.captureWildNPCsInRange = parent.lifecycleRules.captureWildNPCsInRange;
        if (!nestedExplicitKeys.contains("WildCaptureRadius")) lifecycleRules.wildCaptureRadius = parent.lifecycleRules.wildCaptureRadius;
        if (!nestedExplicitKeys.contains("AcceptedRoleIds")) lifecycleRules.acceptedRoleIds = parent.lifecycleRules.acceptedRoleIds;

        if (!nestedExplicitKeys.contains("ResidentSpawnOffset")) {
            lifecycleRules.residentSpawnOffset = parent.lifecycleRules.residentSpawnOffset;
        } else if (lifecycleRules.residentSpawnOffset == null) {
            lifecycleRules.residentSpawnOffset = parent.lifecycleRules.residentSpawnOffset;
        }
    }

    private void inheritProduceSection(@Nonnull TwCoopConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (produceRules == null) {
            produceRules = parent.produceRules;
            return;
        }
        if (parent.produceRules == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("DropsByRole")) produceRules.dropsByRole = parent.produceRules.dropsByRole;
        if (!nestedExplicitKeys.contains("IntervalGameHours")) produceRules.intervalGameHours = parent.produceRules.intervalGameHours;
        if (!nestedExplicitKeys.contains("ItemsPerTick")) produceRules.itemsPerTick = parent.produceRules.itemsPerTick;
    }

    private void inheritIdentitySection(@Nonnull TwCoopConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (identityRules == null) {
            identityRules = parent.identityRules;
            return;
        }
        if (parent.identityRules == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("RequireSnapshotOnRelease")) {
            identityRules.requireSnapshotOnRelease = parent.identityRules.requireSnapshotOnRelease;
        }
        if (!nestedExplicitKeys.contains("PreserveUUID")) {
            identityRules.preserveUUID = parent.identityRules.preserveUUID;
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

    public String getCoopId() {
        return coopId;
    }

    public CapturePolicySettings getCapturePolicy() {
        return capturePolicy == null ? new CapturePolicySettings() : capturePolicy;
    }

    public LifecycleRules getLifecycleRules() {
        return lifecycleRules == null ? new LifecycleRules() : lifecycleRules;
    }

    public ProduceRules getProduceRules() {
        return produceRules == null ? new ProduceRules() : produceRules;
    }

    public IdentityRules getIdentityRules() {
        return identityRules == null ? new IdentityRules() : identityRules;
    }

    /** Additional intake checks/effects applied before managed coop admission. */
    public static final class CapturePolicySettings {
        private boolean requireTamed;
        private boolean ownerRestricted;
        private boolean requireOwner;
        private String particleSystem;
        private String soundEvent;

        public boolean isRequireTamed() {
            return requireTamed;
        }

        public boolean isOwnerRestricted() {
            return ownerRestricted;
        }

        public boolean isRequireOwner() {
            return requireOwner;
        }

        public String getParticleSystem() {
            return particleSystem;
        }

        public String getSoundEvent() {
            return soundEvent;
        }
    }

    /** Managed lifecycle behavior for this coop id. */
    public static final class LifecycleRules {
        private int maxResidents = 6;
        private int residentRoamStartHour = 6;
        private int residentRoamEndHour = 18;
        private SpawnOffsetSettings residentSpawnOffset = new SpawnOffsetSettings();
        private boolean captureWildNPCsInRange = true;
        private double wildCaptureRadius = 10.0;
        private String[] acceptedRoleIds = new String[0];

        public int getMaxResidents() {
            return Math.max(1, maxResidents);
        }

        public int getResidentRoamStartHour() {
            return clampHour(residentRoamStartHour);
        }

        public int getResidentRoamEndHour() {
            return clampHour(residentRoamEndHour);
        }

        public SpawnOffsetSettings getResidentSpawnOffset() {
            return residentSpawnOffset == null ? new SpawnOffsetSettings() : residentSpawnOffset;
        }

        public boolean isCaptureWildNPCsInRange() {
            return captureWildNPCsInRange;
        }

        public double getWildCaptureRadius() {
            return Math.max(0.0, wildCaptureRadius);
        }

        public String[] getAcceptedRoleIds() {
            return acceptedRoleIds == null ? new String[0] : acceptedRoleIds;
        }
    }

    /** Produce generation rules for residents stored in managed coop state. */
    public static final class ProduceRules {
        private Map<String, String> dropsByRole = Map.of();
        private int intervalGameHours = 1;
        private int itemsPerTick = 1;

        public Map<String, String> getDropsByRole() {
            return dropsByRole == null ? Map.of() : dropsByRole;
        }

        public int getIntervalGameHours() {
            return Math.max(1, intervalGameHours);
        }

        public int getItemsPerTick() {
            return Math.max(1, itemsPerTick);
        }
    }

    /** Identity behavior for managed coop residents. */
    public static final class IdentityRules {
        private boolean requireSnapshotOnRelease = true;
        private boolean preserveUUID;

        public boolean isRequireSnapshotOnRelease() {
            return requireSnapshotOnRelease;
        }

        public boolean isPreserveUUID() {
            return preserveUUID;
        }
    }

    /** Spawn offset helper for managed coop release placement. */
    public static final class SpawnOffsetSettings {
        private double x;
        private double y;
        private double z = 3.0;

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }
    }

    private static int clampHour(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 23) {
            return 23;
        }
        return value;
    }
}
