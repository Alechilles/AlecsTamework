package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
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
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed trait configuration for role-scoped companion trait pools.
 * Stored under Server/Tamework/Traits.
 */
public final class TwTraitConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwTraitConfig>>,
        TwParentFallbackAsset<TwTraitConfig> {
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
        .documentation("Weight used when rolling this trait count outcome.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Count1", Codec.DOUBLE),
            (weights, value) -> weights.count1 = value,
            weights -> weights.count1
        )
        .documentation("Weight used when rolling this trait count outcome.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Count2", Codec.DOUBLE),
            (weights, value) -> weights.count2 = value,
            weights -> weights.count2
        )
        .documentation("Weight used when rolling this trait count outcome.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Count3", Codec.DOUBLE),
            (weights, value) -> weights.count3 = value,
            weights -> weights.count3
        )
        .documentation("Weight used when rolling this trait count outcome.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Count4", Codec.DOUBLE),
            (weights, value) -> weights.count4 = value,
            weights -> weights.count4
        )
        .documentation("Weight used when rolling this trait count outcome.")
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
        .documentation("Maximum number of traits that can be assigned to one NPC.")
        .add()
        .<RollCountWeights>append(
            new KeyedCodec<>("RollCountWeights", ROLL_COUNT_WEIGHTS_CODEC),
            (settings, value) -> settings.rollCountWeights = value == null ? new RollCountWeights() : value,
            settings -> settings.rollCountWeights
        )
        .documentation("Trait-count roll weights used when generating random traits.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("AllowDuplicateTraits", Codec.BOOLEAN),
            (settings, value) -> settings.allowDuplicateTraits = value,
            settings -> settings.allowDuplicateTraits
        )
        .documentation("If true, duplicate trait IDs may be rolled on the same NPC.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("UseSeededRandom", Codec.BOOLEAN),
            (settings, value) -> settings.useSeededRandom = value,
            settings -> settings.useSeededRandom
        )
        .documentation("If true, trait rolling uses deterministic seeded randomness.")
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
        .documentation("If true, offspring may inherit traits from parents.")
        .add()
        .<Double>append(
            new KeyedCodec<>("InheritanceChance", Codec.DOUBLE),
            (settings, value) -> settings.inheritanceChance = value,
            settings -> settings.inheritanceChance
        )
        .documentation("Chance that a rolled trait comes from parent inheritance.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MutationChance", Codec.DOUBLE),
            (settings, value) -> settings.mutationChance = value,
            settings -> settings.mutationChance
        )
        .documentation("Chance for mutation when generating inherited data.")
        .add()
        .<Double>append(
            new KeyedCodec<>("PairAlignmentRangeInfluence", Codec.DOUBLE),
            (settings, value) -> settings.pairAlignmentRangeInfluence = value,
            settings -> settings.pairAlignmentRangeInfluence
        )
        .documentation("Influence of parent trait alignment range on breeding trait rolls.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("PreferParentTraits", Codec.BOOLEAN),
            (settings, value) -> settings.preferParentTraits = value,
            settings -> settings.preferParentTraits
        )
        .documentation("If true, parent traits are favored during inheritance rolls.")
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
        .documentation("Unique identifier for this entry.")
        .add()
        .<String>append(
            new KeyedCodec<>("DisplayName", Codec.STRING),
            (definition, value) -> definition.displayName = value,
            definition -> definition.displayName
        )
        .documentation("Display name shown to players.")
        .add()
        .<String>append(
            new KeyedCodec<>("EffectKey", Codec.STRING),
            (definition, value) -> definition.effectKey = value,
            definition -> definition.effectKey
        )
        .documentation("Effect key applied by this trait.")
        .add()
        .<String>append(
            new KeyedCodec<>("IconPath", Codec.STRING),
            (definition, value) -> definition.iconPath = value,
            definition -> definition.iconPath
        )
        .documentation("Icon asset path used for this trait in UI.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Weight", Codec.DOUBLE),
            (definition, value) -> definition.weight = value,
            definition -> definition.weight
        )
        .documentation("Base roll weight for this trait.")
        .add()
        .<Double>append(
            new KeyedCodec<>("InheritanceWeight", Codec.DOUBLE),
            (definition, value) -> definition.inheritanceWeight = value,
            definition -> definition.inheritanceWeight
        )
        .documentation("Inheritance roll weight for this trait when breeding.")
        .add()
        .<Double>append(
            new KeyedCodec<>("NaturalMin", Codec.DOUBLE),
            (definition, value) -> definition.naturalMin = value,
            definition -> definition.naturalMin
        )
        .documentation("Minimum natural roll value for this trait.")
        .add()
        .<Double>append(
            new KeyedCodec<>("NaturalMax", Codec.DOUBLE),
            (definition, value) -> definition.naturalMax = value,
            definition -> definition.naturalMax
        )
        .documentation("Maximum natural roll value for this trait.")
        .add()
        .<Double>append(
            new KeyedCodec<>("BreedingMin", Codec.DOUBLE),
            (definition, value) -> definition.breedingMin = value,
            definition -> definition.breedingMin
        )
        .documentation("Minimum inherited roll value for this trait during breeding.")
        .add()
        .<Double>append(
            new KeyedCodec<>("BreedingMax", Codec.DOUBLE),
            (definition, value) -> definition.breedingMax = value,
            definition -> definition.breedingMax
        )
        .documentation("Maximum inherited roll value for this trait during breeding.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Default", Codec.DOUBLE),
            (definition, value) -> definition.defaultValue = value,
            definition -> definition.defaultValue
        )
        .documentation("Default setting when no override is set.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Flags", Codec.STRING_ARRAY),
            (definition, value) -> definition.flags = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            definition -> definition.flags
        )
        .documentation("Behavior flags associated with this trait.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("ConflictsWith", Codec.STRING_ARRAY),
            (definition, value) -> definition.conflictsWith = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            definition -> definition.conflictsWith
        )
        .documentation("Trait IDs that cannot coexist with this trait.")
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
        .documentation("NPC role IDs this config applies to. Inheritance: omitted value inherits from parent; explicit "
                + "array replaces parent value (no merge).")
        .add()
        .<SelectionSettings>append(
            new KeyedCodec<>("Selection", SELECTION_CODEC),
            (asset, value) -> asset.selection = value == null ? new SelectionSettings() : value,
            asset -> asset.selection
        )
        .documentation("Trait selection settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<InheritanceSettings>append(
            new KeyedCodec<>("Inheritance", INHERITANCE_CODEC),
            (asset, value) -> asset.inheritance = value == null ? new InheritanceSettings() : value,
            asset -> asset.inheritance
        )
        .documentation("Trait inheritance settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<TraitDefinition[]>append(
            new KeyedCodec<>("Traits", TRAIT_DEFINITION_ARRAY_CODEC),
            (asset, value) -> asset.traits = value == null ? EMPTY_TRAITS : value,
            asset -> asset.traits
        )
        .documentation("Trait definition list. Inheritance: omitted value inherits from parent; explicit array "
                + "replaces parent value (no merge).")
        .add()
        .build();

    private static AssetStore<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
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
        DefaultAssetMap<String, TwTraitConfig> assetMap = (DefaultAssetMap<String, TwTraitConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
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
            if (candidate == null || !candidate.isConfiguredEnabled()) {
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

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwTraitConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwTraitConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwTraitConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Selection")) {
            selection = parent.selection;
        } else {
            inheritSelectionSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Selection"));
        }
        if (!explicitTopLevelKeys.contains("Inheritance")) {
            inheritance = parent.inheritance;
        } else {
            inheritInheritanceSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Inheritance"));
        }
        if (!explicitTopLevelKeys.contains("Traits")) traits = parent.traits;
    }

    private void inheritSelectionSection(@Nonnull TwTraitConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (selection == null) {
            selection = parent.selection;
            return;
        }
        if (parent.selection == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("MaxTraitsPerNpc")) {
            selection.maxTraitsPerNpc = parent.selection.maxTraitsPerNpc;
        }
        if (!nestedExplicitKeys.contains("AllowDuplicateTraits")) {
            selection.allowDuplicateTraits = parent.selection.allowDuplicateTraits;
        }
        if (!nestedExplicitKeys.contains("UseSeededRandom")) {
            selection.useSeededRandom = parent.selection.useSeededRandom;
        }
        if (!nestedExplicitKeys.contains("RollCountWeights")) {
            selection.rollCountWeights = parent.selection.rollCountWeights;
        } else {
            inheritRollCountWeights(parent, nestedExplicitKeys);
        }
    }

    private void inheritRollCountWeights(@Nonnull TwTraitConfig parent, @Nonnull Set<String> nestedExplicitKeys) {
        if (selection == null || parent.selection == null) {
            return;
        }
        if (selection.rollCountWeights == null) {
            selection.rollCountWeights = parent.selection.rollCountWeights;
            return;
        }
        if (parent.selection.rollCountWeights == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("RollCountWeights.Count0")) {
            selection.rollCountWeights.count0 = parent.selection.rollCountWeights.count0;
        }
        if (!nestedExplicitKeys.contains("RollCountWeights.Count1")) {
            selection.rollCountWeights.count1 = parent.selection.rollCountWeights.count1;
        }
        if (!nestedExplicitKeys.contains("RollCountWeights.Count2")) {
            selection.rollCountWeights.count2 = parent.selection.rollCountWeights.count2;
        }
        if (!nestedExplicitKeys.contains("RollCountWeights.Count3")) {
            selection.rollCountWeights.count3 = parent.selection.rollCountWeights.count3;
        }
        if (!nestedExplicitKeys.contains("RollCountWeights.Count4")) {
            selection.rollCountWeights.count4 = parent.selection.rollCountWeights.count4;
        }
    }

    private void inheritInheritanceSection(@Nonnull TwTraitConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (inheritance == null) {
            inheritance = parent.inheritance;
            return;
        }
        if (parent.inheritance == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("AllowInheritance")) {
            inheritance.allowInheritance = parent.inheritance.allowInheritance;
        }
        if (!nestedExplicitKeys.contains("InheritanceChance")) {
            inheritance.inheritanceChance = parent.inheritance.inheritanceChance;
        }
        if (!nestedExplicitKeys.contains("MutationChance")) {
            inheritance.mutationChance = parent.inheritance.mutationChance;
        }
        if (!nestedExplicitKeys.contains("PairAlignmentRangeInfluence")) {
            inheritance.pairAlignmentRangeInfluence = parent.inheritance.pairAlignmentRangeInfluence;
        }
        if (!nestedExplicitKeys.contains("PreferParentTraits")) {
            inheritance.preferParentTraits = parent.inheritance.preferParentTraits;
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

    protected TwTraitConfig() {
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.traitsEnabled() != null) {
            return overrides.traitsEnabled();
        }
        return enabled;
    }

    public boolean isConfiguredEnabled() {
        return enabled;
    }

    @Nullable
    private static TameworkSettingsStore.GlobalOverrides resolveRuntimeOverrides() {
        return TameworkSettingsStore.loadRuntimeGlobalOverrides();
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


