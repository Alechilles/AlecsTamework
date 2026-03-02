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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Asset-backed trait configuration for role-scoped companion trait pools.
 * Stored under Server/Tamework/Traits.
 */
public final class TwTraitConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwTraitConfig>> {
    private static final TraitDefinition[] EMPTY_TRAITS = new TraitDefinition[0];

    private static final BuilderCodec<RollCountWeights> ROLL_COUNT_WEIGHTS_CODEC = BuilderCodec.builder(
            RollCountWeights.class,
            RollCountWeights::new
    )
        .<Double>append(
            new KeyedCodec<>("Count0", Codec.DOUBLE),
            (weights, value) -> weights.count0 = value,
            weights -> weights.count0
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Count1", Codec.DOUBLE),
            (weights, value) -> weights.count1 = value,
            weights -> weights.count1
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Count2", Codec.DOUBLE),
            (weights, value) -> weights.count2 = value,
            weights -> weights.count2
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Count3", Codec.DOUBLE),
            (weights, value) -> weights.count3 = value,
            weights -> weights.count3
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Count4", Codec.DOUBLE),
            (weights, value) -> weights.count4 = value,
            weights -> weights.count4
        )
        .add()
        .build();

    private static final BuilderCodec<SelectionSettings> SELECTION_CODEC = BuilderCodec.builder(
            SelectionSettings.class,
            SelectionSettings::new
    )
        .<Integer>append(
            new KeyedCodec<>("MaxTraitsPerNpc", Codec.INTEGER),
            (settings, value) -> settings.maxTraitsPerNpc = value,
            settings -> settings.maxTraitsPerNpc
        )
        .add()
        .<RollCountWeights>append(
            new KeyedCodec<>("RollCountWeights", ROLL_COUNT_WEIGHTS_CODEC),
            (settings, value) -> settings.rollCountWeights = value == null ? new RollCountWeights() : value,
            settings -> settings.rollCountWeights
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("AllowDuplicateTraits", Codec.BOOLEAN),
            (settings, value) -> settings.allowDuplicateTraits = value,
            settings -> settings.allowDuplicateTraits
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("UseSeededRandom", Codec.BOOLEAN),
            (settings, value) -> settings.useSeededRandom = value,
            settings -> settings.useSeededRandom
        )
        .add()
        .build();

    private static final BuilderCodec<InheritanceSettings> INHERITANCE_CODEC = BuilderCodec.builder(
            InheritanceSettings.class,
            InheritanceSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("AllowInheritance", Codec.BOOLEAN),
            (settings, value) -> settings.allowInheritance = value,
            settings -> settings.allowInheritance
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("InheritanceChance", Codec.DOUBLE),
            (settings, value) -> settings.inheritanceChance = value,
            settings -> settings.inheritanceChance
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MutationChance", Codec.DOUBLE),
            (settings, value) -> settings.mutationChance = value,
            settings -> settings.mutationChance
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("PairAlignmentRangeInfluence", Codec.DOUBLE),
            (settings, value) -> settings.pairAlignmentRangeInfluence = value,
            settings -> settings.pairAlignmentRangeInfluence
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PreferParentTraits", Codec.BOOLEAN),
            (settings, value) -> settings.preferParentTraits = value,
            settings -> settings.preferParentTraits
        )
        .add()
        .build();

    private static final BuilderCodec<TraitDefinition> TRAIT_DEFINITION_CODEC = BuilderCodec.builder(
            TraitDefinition.class,
            TraitDefinition::new
    )
        .<String>append(
            new KeyedCodec<>("Id", Codec.STRING),
            (definition, value) -> definition.id = value,
            definition -> definition.id
        )
        .add()
        .<String>append(
            new KeyedCodec<>("DisplayName", Codec.STRING),
            (definition, value) -> definition.displayName = value,
            definition -> definition.displayName
        )
        .add()
        .<String>append(
            new KeyedCodec<>("EffectKey", Codec.STRING),
            (definition, value) -> definition.effectKey = value,
            definition -> definition.effectKey
        )
        .add()
        .<String>append(
            new KeyedCodec<>("IconPath", Codec.STRING),
            (definition, value) -> definition.iconPath = value,
            definition -> definition.iconPath
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Weight", Codec.DOUBLE),
            (definition, value) -> definition.weight = value,
            definition -> definition.weight
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("InheritanceWeight", Codec.DOUBLE),
            (definition, value) -> definition.inheritanceWeight = value,
            definition -> definition.inheritanceWeight
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("NaturalMin", Codec.DOUBLE),
            (definition, value) -> definition.naturalMin = value,
            definition -> definition.naturalMin
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("NaturalMax", Codec.DOUBLE),
            (definition, value) -> definition.naturalMax = value,
            definition -> definition.naturalMax
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("BreedingMin", Codec.DOUBLE),
            (definition, value) -> definition.breedingMin = value,
            definition -> definition.breedingMin
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("BreedingMax", Codec.DOUBLE),
            (definition, value) -> definition.breedingMax = value,
            definition -> definition.breedingMax
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Default", Codec.DOUBLE),
            (definition, value) -> definition.defaultValue = value,
            definition -> definition.defaultValue
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Flags", Codec.STRING_ARRAY),
            (definition, value) -> definition.flags = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            definition -> definition.flags
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("ConflictsWith", Codec.STRING_ARRAY),
            (definition, value) -> definition.conflictsWith = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            definition -> definition.conflictsWith
        )
        .add()
        .build();

    private static final ArrayCodec<TraitDefinition> TRAIT_DEFINITION_ARRAY_CODEC =
            new ArrayCodec<>(TRAIT_DEFINITION_CODEC, TraitDefinition[]::new);

    public static final AssetBuilderCodec<String, TwTraitConfig> CODEC = AssetBuilderCodec.builder(
            TwTraitConfig.class,
            TwTraitConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
        .documentation("Trait configuration for Alec's Tamework companions.")
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
        .<SelectionSettings>append(
            new KeyedCodec<>("Selection", SELECTION_CODEC),
            (asset, value) -> asset.selection = value == null ? new SelectionSettings() : value,
            asset -> asset.selection
        )
        .add()
        .<InheritanceSettings>append(
            new KeyedCodec<>("Inheritance", INHERITANCE_CODEC),
            (asset, value) -> asset.inheritance = value == null ? new InheritanceSettings() : value,
            asset -> asset.inheritance
        )
        .add()
        .<TraitDefinition[]>append(
            new KeyedCodec<>("Traits", TRAIT_DEFINITION_ARRAY_CODEC),
            (asset, value) -> asset.traits = value == null ? EMPTY_TRAITS : value,
            asset -> asset.traits
        )
        .add()
        .build();

    private static AssetStore<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> ASSET_STORE;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwTraitConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private SelectionSettings selection = new SelectionSettings();
    private InheritanceSettings inheritance = new InheritanceSettings();
    private TraitDefinition[] traits = EMPTY_TRAITS;

    public static AssetStore<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwTraitConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwTraitConfig> getAssetMap() {
        AssetStore<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, TwTraitConfig>) store.getAssetMap();
    }

    public static void clearRoleCache() {
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwTraitConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwTraitConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwTraitConfig> cache = ROLE_CACHE;
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
    public static TwTraitConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwTraitConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwTraitConfig> map = assetMap.getAssetMap();
        TwTraitConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwTraitConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwTraitConfig> buildRoleCache(
            @Nullable DefaultAssetMap<String, TwTraitConfig> assetMap) {
        Map<String, TwTraitConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwTraitConfig candidate : assetMap.getAssetMap().values()) {
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
                TwTraitConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwTraitConfig candidate,
                                                  @Nullable TwTraitConfig existing) {
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

    protected TwTraitConfig() {
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

    public SelectionSettings getSelection() {
        return selection == null ? new SelectionSettings() : selection;
    }

    public InheritanceSettings getInheritance() {
        return inheritance == null ? new InheritanceSettings() : inheritance;
    }

    public TraitDefinition[] getTraits() {
        return traits == null ? EMPTY_TRAITS : traits;
    }

    /** Trait roll counts, duplicate policy, and randomization controls. */
    public static final class SelectionSettings {
        private int maxTraitsPerNpc = 3;
        private RollCountWeights rollCountWeights = new RollCountWeights();
        private boolean allowDuplicateTraits;
        private boolean useSeededRandom = true;

        public int getMaxTraitsPerNpc() {
            return maxTraitsPerNpc;
        }

        public RollCountWeights getRollCountWeights() {
            return rollCountWeights == null ? new RollCountWeights() : rollCountWeights;
        }

        public boolean isAllowDuplicateTraits() {
            return allowDuplicateTraits;
        }

        public boolean isUseSeededRandom() {
            return useSeededRandom;
        }
    }

    /** Weighted roll-count settings (0..4) used to vary spawned trait counts. */
    public static final class RollCountWeights {
        private double count0 = 0.10;
        private double count1 = 0.20;
        private double count2 = 0.45;
        private double count3 = 0.20;
        private double count4 = 0.05;

        public double getCount0() {
            return count0;
        }

        public double getCount1() {
            return count1;
        }

        public double getCount2() {
            return count2;
        }

        public double getCount3() {
            return count3;
        }

        public double getCount4() {
            return count4;
        }
    }

    /** Trait inheritance controls used by breeding integrations. */
    public static final class InheritanceSettings {
        private boolean allowInheritance = true;
        private double inheritanceChance = 0.6;
        private double mutationChance = 0.1;
        private double pairAlignmentRangeInfluence = 0.6;
        private boolean preferParentTraits = true;

        public boolean isAllowInheritance() {
            return allowInheritance;
        }

        public double getInheritanceChance() {
            return inheritanceChance;
        }

        public double getMutationChance() {
            return mutationChance;
        }

        public double getPairAlignmentRangeInfluence() {
            return pairAlignmentRangeInfluence;
        }

        public boolean isPreferParentTraits() {
            return preferParentTraits;
        }
    }

    /** Single trait definition in a role-level trait pool. */
    public static final class TraitDefinition {
        private String id;
        private String displayName;
        private String effectKey;
        private String iconPath;
        private double weight = 1.0;
        private double inheritanceWeight = 1.0;
        private double naturalMin = 0.9;
        private double naturalMax = 1.1;
        private double breedingMin = 0.0;
        private double breedingMax = 1.0;
        private double defaultValue = 1.0;
        private String[] flags = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] conflictsWith = ArrayUtil.EMPTY_STRING_ARRAY;

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getEffectKey() {
            return effectKey;
        }

        @Nullable
        public String getIconPath() {
            if (iconPath == null || iconPath.isBlank()) {
                return null;
            }
            return iconPath;
        }

        public double getWeight() {
            return weight;
        }

        public double getInheritanceWeight() {
            return inheritanceWeight;
        }

        public double getNaturalMin() {
            return naturalMin;
        }

        public double getNaturalMax() {
            return naturalMax;
        }

        public double getBreedingMin() {
            return breedingMin;
        }

        public double getBreedingMax() {
            return breedingMax;
        }

        public double getDefaultValue() {
            return defaultValue;
        }

        public String[] getFlags() {
            return flags == null ? ArrayUtil.EMPTY_STRING_ARRAY : flags;
        }

        public String[] getConflictsWith() {
            return conflictsWith == null ? ArrayUtil.EMPTY_STRING_ARRAY : conflictsWith;
        }
    }
}
