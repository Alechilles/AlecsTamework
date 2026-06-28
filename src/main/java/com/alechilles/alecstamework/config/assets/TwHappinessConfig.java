package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
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
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed companion happiness configuration for role-scoped happiness progression.
 * Stored under Server/Tamework/Happiness.
 */
public final class TwHappinessConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwHappinessConfig>>,
        TwParentFallbackAsset<TwHappinessConfig> {
    private static final NeedBandSettings[] EMPTY_BANDS = new NeedBandSettings[0];
    private static final PopulationBandSettings[] EMPTY_POPULATION_BANDS = new PopulationBandSettings[0];
    private static final MapCodec<Double, Map<String, Double>> FEED_ITEM_IMPULSES_CODEC =
            new MapCodec<>(Codec.DOUBLE, HashMap::new);

    private static final BuilderCodec<ValueSettings> VALUE_CODEC = BuilderCodec.builder(
            ValueSettings.class,
            ValueSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("CurrentDefault", Codec.DOUBLE),
            (settings, value) -> settings.currentDefault = value,
            settings -> settings.currentDefault
        )
        .documentation("Default happiness value used when no saved value exists.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Min", Codec.DOUBLE),
            (settings, value) -> settings.min = value,
            settings -> settings.min
        )
        .documentation("Minimum setting allowed.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Max", Codec.DOUBLE),
            (settings, value) -> settings.max = value,
            settings -> settings.max
        )
        .documentation("Maximum setting allowed.")
        .add()
        .build();

    private static final BuilderCodec<EquilibriumSettings> EQUILIBRIUM_CODEC = BuilderCodec.builder(
            EquilibriumSettings.class,
            EquilibriumSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("BaseSetpoint", Codec.DOUBLE),
            (settings, value) -> settings.baseSetpoint = value,
            settings -> settings.baseSetpoint
        )
        .documentation("Baseline happiness target NPCs trend toward over time.")
        .add()
        .<Double>append(
            new KeyedCodec<>("ConvergencePerMinute", Codec.DOUBLE),
            (settings, value) -> settings.convergencePerMinute = value,
            settings -> settings.convergencePerMinute
        )
        .documentation("How quickly happiness converges toward the base setpoint each minute.")
        .add()
        .build();

    private static final BuilderCodec<ImpulseSettings> IMPULSE_CODEC = BuilderCodec.builder(
            ImpulseSettings.class,
            ImpulseSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("GainOnFeed", Codec.DOUBLE),
            (settings, value) -> settings.gainOnFeed = value,
            settings -> settings.gainOnFeed
        )
        .documentation("Happiness impulse applied only when hand-feeding succeeds.")
        .add()
        .<Double>append(
            new KeyedCodec<>("HandFeedDurationMinutes", Codec.DOUBLE),
            (settings, value) -> settings.handFeedDurationMinutes = value,
            settings -> settings.handFeedDurationMinutes
        )
        .documentation("Duration in minutes for the hand-feed impulse.")
        .add()
        .<Double>append(
            new KeyedCodec<>("FeedImpulseDurationMinutes", Codec.DOUBLE),
            (settings, value) -> settings.feedImpulseDurationMinutes = value,
            settings -> settings.feedImpulseDurationMinutes
        )
        .documentation("Duration in minutes for food-consumption impulses (item or param based).")
        .add()
        .<Double>append(
            new KeyedCodec<>("GainOnPet", Codec.DOUBLE),
            (settings, value) -> settings.gainOnPet = value,
            settings -> settings.gainOnPet
        )
        .documentation("Happiness gained from successful pet interactions.")
        .add()
        .<Double>append(
            new KeyedCodec<>("LoseOnDamage", Codec.DOUBLE),
            (settings, value) -> settings.loseOnDamage = value,
            settings -> settings.loseOnDamage
        )
        .documentation("Happiness lost when the NPC takes damage.")
        .add()
        .<Map<String, Double>>append(
            new KeyedCodec<>("FeedItemImpulses", FEED_ITEM_IMPULSES_CODEC),
            (settings, value) -> settings.feedItemImpulses = value == null ? Map.of() : value,
            settings -> settings.feedItemImpulses
        )
        .documentation("Per-item feed impulse overrides keyed by consumed item ID. Inheritance: explicit map replaces "
                + "parent map (no merge); omitted map inherits parent map.")
        .add()
        .<Map<String, Double>>append(
            new KeyedCodec<>("FeedParamImpulses", FEED_ITEM_IMPULSES_CODEC),
            (settings, value) -> settings.feedParamImpulses = value == null ? Map.of() : value,
            settings -> settings.feedParamImpulses
        )
        .documentation("Per-role-param feed impulse overrides keyed by role parameter name (for example, FoodFavorite). "
                + "Each param should resolve to item IDs. Inheritance: explicit map replaces parent map (no merge); "
                + "omitted map inherits parent map.")
        .add()
        .build();

    private static final BuilderCodec<NeedBandSettings> NEED_BAND_CODEC = BuilderCodec.builder(
            NeedBandSettings.class,
            NeedBandSettings::new
    )
        .<String>append(
            new KeyedCodec<>("Id", Codec.STRING),
            (settings, value) -> settings.id = value,
            settings -> settings.id
        )
        .documentation("Unique identifier for this entry.")
        .add()
        .<String>append(
            new KeyedCodec<>("Label", Codec.STRING),
            (settings, value) -> settings.label = value,
            settings -> settings.label
        )
        .documentation("UI label shown for this entry. May be raw text or a server.lang key.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MinPercent", Codec.DOUBLE),
            (settings, value) -> settings.minPercent = value,
            settings -> settings.minPercent
        )
        .documentation("Inclusive minimum percentage for this happiness band.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MaxPercent", Codec.DOUBLE),
            (settings, value) -> settings.maxPercent = value,
            settings -> settings.maxPercent
        )
        .documentation("Inclusive maximum percentage for this happiness band.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Offset", Codec.DOUBLE),
            (settings, value) -> settings.offset = value,
            settings -> settings.offset
        )
        .documentation("Value offset contributed by this entry.")
        .add()
        .build();

    private static final ArrayCodec<NeedBandSettings> NEED_BAND_ARRAY_CODEC =
            new ArrayCodec<>(NEED_BAND_CODEC, NeedBandSettings[]::new);

    private static final BuilderCodec<NeedModifierSettings> NEED_MODIFIER_CODEC = BuilderCodec.builder(
            NeedModifierSettings.class,
            NeedModifierSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value == null || value,
            settings -> settings.enabled
        )
        .documentation("Turns this section on or off.")
        .add()
        .<NeedBandSettings[]>append(
            new KeyedCodec<>("Bands", NEED_BAND_ARRAY_CODEC),
            (settings, value) -> settings.bands = value == null ? EMPTY_BANDS : value,
            settings -> settings.bands
        )
        .documentation("Band entries that map a value range to an offset.")
        .add()
        .build();

    private static final BuilderCodec<PopulationBandSettings> POPULATION_BAND_CODEC = BuilderCodec.builder(
            PopulationBandSettings.class,
            PopulationBandSettings::new
    )
        .<String>append(
            new KeyedCodec<>("Id", Codec.STRING),
            (settings, value) -> settings.id = value,
            settings -> settings.id
        )
        .documentation("Unique identifier for this entry.")
        .add()
        .<String>append(
            new KeyedCodec<>("Label", Codec.STRING),
            (settings, value) -> settings.label = value,
            settings -> settings.label
        )
        .documentation("UI label shown for this entry. May be raw text or a server.lang key.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MinCount", Codec.INTEGER),
            (settings, value) -> settings.minCount = value,
            settings -> settings.minCount
        )
        .documentation("Minimum count allowed.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxCount", Codec.INTEGER),
            (settings, value) -> settings.maxCount = value,
            settings -> settings.maxCount
        )
        .documentation("Maximum count allowed.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Offset", Codec.DOUBLE),
            (settings, value) -> settings.offset = value,
            settings -> settings.offset
        )
        .documentation("Value offset contributed by this entry.")
        .add()
        .build();

    private static final ArrayCodec<PopulationBandSettings> POPULATION_BAND_ARRAY_CODEC =
            new ArrayCodec<>(POPULATION_BAND_CODEC, PopulationBandSettings[]::new);

    private static final BuilderCodec<PopulationModifierSettings> POPULATION_MODIFIER_CODEC = BuilderCodec.builder(
            PopulationModifierSettings.class,
            PopulationModifierSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value == null || value,
            settings -> settings.enabled
        )
        .documentation("Turns this section on or off.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Radius", Codec.DOUBLE),
            (settings, value) -> settings.radius = value,
            settings -> settings.radius
        )
        .documentation("Search radius in blocks used by this system.")
        .add()
        .<PopulationBandSettings[]>append(
            new KeyedCodec<>("Bands", POPULATION_BAND_ARRAY_CODEC),
            (settings, value) -> settings.bands = value == null ? EMPTY_POPULATION_BANDS : value,
            settings -> settings.bands
        )
        .documentation("Band entries that map a value range to an offset.")
        .add()
        .build();

    private static final BuilderCodec<ModifierSettings> MODIFIER_CODEC = BuilderCodec.builder(
            ModifierSettings.class,
            ModifierSettings::new
    )
        .<NeedModifierSettings>append(
            new KeyedCodec<>("Hunger", NEED_MODIFIER_CODEC),
            (settings, value) -> settings.hunger = value == null ? new NeedModifierSettings() : value,
            settings -> settings.hunger
        )
        .documentation("Hunger contribution settings for happiness adjustment.")
        .add()
        .<NeedModifierSettings>append(
            new KeyedCodec<>("Thirst", NEED_MODIFIER_CODEC),
            (settings, value) -> settings.thirst = value == null ? new NeedModifierSettings() : value,
            settings -> settings.thirst
        )
        .documentation("Thirst contribution settings for happiness adjustment.")
        .add()
        .<PopulationModifierSettings>append(
            new KeyedCodec<>("Population", POPULATION_MODIFIER_CODEC),
            (settings, value) -> settings.population = value == null ? new PopulationModifierSettings() : value,
            settings -> settings.population
        )
        .documentation("Population settings for ownership limits and scope.")
        .add()
        .<Double>append(
            new KeyedCodec<>("OwnerNearbyOffset", Codec.DOUBLE),
            (settings, value) -> settings.ownerNearbyOffset = value,
            settings -> settings.ownerNearbyOffset
        )
        .documentation("Happiness offset applied while owner is nearby.")
        .add()
        .build();

    public static final AssetBuilderCodec<String, TwHappinessConfig> CODEC = AssetBuilderCodec.builder(
            TwHappinessConfig.class,
            TwHappinessConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
        .documentation("Shared happiness configuration for Alec's Tamework companions.")
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
        .<ValueSettings>append(
            new KeyedCodec<>("Values", VALUE_CODEC),
            (asset, value) -> asset.values = value == null ? new ValueSettings() : value,
            asset -> asset.values
        )
        .documentation("Base happiness value bounds and defaults. Inheritance: omitted section inherits from parent; "
                + "when present, only explicitly defined nested fields override parent.")
        .add()
        .<EquilibriumSettings>append(
            new KeyedCodec<>("Equilibrium", EQUILIBRIUM_CODEC),
            (asset, value) -> asset.equilibrium = value == null ? new EquilibriumSettings() : value,
            asset -> asset.equilibrium
        )
        .documentation("Equilibrium convergence settings. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<ImpulseSettings>append(
            new KeyedCodec<>("Impulses", IMPULSE_CODEC),
            (asset, value) -> asset.impulses = value == null ? new ImpulseSettings() : value,
            asset -> asset.impulses
        )
        .documentation("Impulse settings for direct happiness gains/losses. Inheritance: omitted section inherits "
                + "from parent; when present, only explicitly defined nested fields override parent.")
        .add()
        .<ModifierSettings>append(
            new KeyedCodec<>("Modifiers", MODIFIER_CODEC),
            (asset, value) -> asset.modifiers = value == null ? new ModifierSettings() : value,
            asset -> asset.modifiers
        )
        .documentation("Modifier groups for needs/population/owner offsets. Inheritance: omitted section inherits "
                + "from parent; when present, only explicitly defined nested fields override parent.")
        .add()
        .build();

    private static AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwHappinessConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private ValueSettings values = new ValueSettings();
    private EquilibriumSettings equilibrium = new EquilibriumSettings();
    private ImpulseSettings impulses = new ImpulseSettings();
    private ModifierSettings modifiers = new ModifierSettings();

    public static AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwHappinessConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwHappinessConfig> getAssetMap() {
        AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwHappinessConfig> assetMap = (DefaultAssetMap<String, TwHappinessConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwHappinessConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwHappinessConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwHappinessConfig> cache = ROLE_CACHE;
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

    public static boolean isEnabledForRole(@Nullable String roleId) {
        DefaultAssetMap<String, TwHappinessConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null || assetMap.getAssetMap().isEmpty()) {
            return TameworkRuntimeSettings.happinessEnabled(true);
        }
        TwHappinessConfig configured = resolveConfiguredForRole(roleId, assetMap);
        boolean configEnabled = configured == null || configured.isConfiguredEnabled();
        return configEnabled && TameworkRuntimeSettings.happinessEnabled(configEnabled);
    }

    @Nullable
    private static TwHappinessConfig resolveConfiguredForRole(@Nullable String roleId,
                                                              @Nonnull DefaultAssetMap<String, TwHappinessConfig> assetMap) {
        TwHappinessConfig bestRoleMatch = null;
        TwHappinessConfig bestRoleless = null;
        String normalizedRoleId = roleId == null ? "" : roleId.trim().toLowerCase(Locale.ROOT);
        for (TwHappinessConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null) {
                continue;
            }
            String[] roleIds = candidate.getRoleIds();
            if (roleIds.length == 0) {
                if (shouldReplaceCandidate(candidate, bestRoleless)) {
                    bestRoleless = candidate;
                }
                continue;
            }
            if (normalizedRoleId.isBlank()) {
                continue;
            }
            for (String candidateRoleId : roleIds) {
                if (candidateRoleId == null || candidateRoleId.isBlank()) {
                    continue;
                }
                if (normalizedRoleId.equals(candidateRoleId.trim().toLowerCase(Locale.ROOT))
                        && shouldReplaceCandidate(candidate, bestRoleMatch)) {
                    bestRoleMatch = candidate;
                }
            }
        }
        return bestRoleMatch != null ? bestRoleMatch : bestRoleless;
    }

    @Nullable
    public static TwHappinessConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwHappinessConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwHappinessConfig> map = assetMap.getAssetMap();
        TwHappinessConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwHappinessConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwHappinessConfig> buildRoleCache(
            @Nullable DefaultAssetMap<String, TwHappinessConfig> assetMap) {
        Map<String, TwHappinessConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwHappinessConfig candidate : assetMap.getAssetMap().values()) {
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
                TwHappinessConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwHappinessConfig candidate,
                                                  @Nullable TwHappinessConfig existing) {
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

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwHappinessConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwHappinessConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwHappinessConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Values")) {
            values = parent.values;
        } else {
            inheritValuesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Values"));
        }
        if (!explicitTopLevelKeys.contains("Equilibrium")) {
            equilibrium = parent.equilibrium;
        } else {
            inheritEquilibriumSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Equilibrium"));
        }
        if (!explicitTopLevelKeys.contains("Impulses")) {
            impulses = parent.impulses;
        } else {
            inheritImpulsesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Impulses"));
        }
        if (!explicitTopLevelKeys.contains("Modifiers")) {
            modifiers = parent.modifiers;
        } else {
            inheritModifiersSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Modifiers"));
        }
    }

    private void inheritValuesSection(@Nonnull TwHappinessConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (values == null) {
            values = parent.values;
            return;
        }
        if (parent.values == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("CurrentDefault")) values.currentDefault = parent.values.currentDefault;
        if (!nestedExplicitKeys.contains("Min")) values.min = parent.values.min;
        if (!nestedExplicitKeys.contains("Max")) values.max = parent.values.max;
    }

    private void inheritEquilibriumSection(@Nonnull TwHappinessConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (equilibrium == null) {
            equilibrium = parent.equilibrium;
            return;
        }
        if (parent.equilibrium == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("BaseSetpoint")) {
            equilibrium.baseSetpoint = parent.equilibrium.baseSetpoint;
        }
        if (!nestedExplicitKeys.contains("ConvergencePerMinute")) {
            equilibrium.convergencePerMinute = parent.equilibrium.convergencePerMinute;
        }
    }

    private void inheritImpulsesSection(@Nonnull TwHappinessConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (impulses == null) {
            impulses = parent.impulses;
            return;
        }
        if (parent.impulses == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("GainOnFeed")) impulses.gainOnFeed = parent.impulses.gainOnFeed;
        if (!nestedExplicitKeys.contains("HandFeedDurationMinutes")) {
            impulses.handFeedDurationMinutes = parent.impulses.handFeedDurationMinutes;
        }
        if (!nestedExplicitKeys.contains("FeedImpulseDurationMinutes")) {
            impulses.feedImpulseDurationMinutes = parent.impulses.feedImpulseDurationMinutes;
        }
        if (!nestedExplicitKeys.contains("GainOnPet")) impulses.gainOnPet = parent.impulses.gainOnPet;
        if (!nestedExplicitKeys.contains("LoseOnDamage")) impulses.loseOnDamage = parent.impulses.loseOnDamage;
        if (!nestedExplicitKeys.contains("FeedItemImpulses")) {
            impulses.feedItemImpulses = parent.impulses.feedItemImpulses;
        }
        if (!nestedExplicitKeys.contains("FeedParamImpulses")) {
            impulses.feedParamImpulses = parent.impulses.feedParamImpulses;
        }
    }

    private void inheritModifiersSection(@Nonnull TwHappinessConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (modifiers == null) {
            modifiers = parent.modifiers;
            return;
        }
        if (parent.modifiers == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("OwnerNearbyOffset")) {
            modifiers.ownerNearbyOffset = parent.modifiers.ownerNearbyOffset;
        }
        inheritNeedModifier(
                nestedExplicitKeys,
                "Hunger",
                modifiers.hunger,
                parent.modifiers.hunger,
                settings -> modifiers.hunger = settings
        );
        inheritNeedModifier(
                nestedExplicitKeys,
                "Thirst",
                modifiers.thirst,
                parent.modifiers.thirst,
                settings -> modifiers.thirst = settings
        );
        inheritPopulationModifier(nestedExplicitKeys, modifiers, parent.modifiers);
    }

    private void inheritNeedModifier(@Nonnull Set<String> nestedExplicitKeys,
                                     @Nonnull String nestedFieldKey,
                                     @Nullable NeedModifierSettings currentSettings,
                                     @Nullable NeedModifierSettings parentSettings,
                                     @Nonnull java.util.function.Consumer<NeedModifierSettings> assignCurrent) {
        if (!nestedExplicitKeys.contains(nestedFieldKey)) {
            assignCurrent.accept(parentSettings);
            return;
        }
        if (currentSettings == null) {
            assignCurrent.accept(parentSettings);
            return;
        }
        if (parentSettings == null) {
            return;
        }
        if (!nestedExplicitKeys.contains(nestedFieldKey + ".Enabled")) {
            currentSettings.enabled = parentSettings.enabled;
        }
        if (!nestedExplicitKeys.contains(nestedFieldKey + ".Bands")) {
            currentSettings.bands = parentSettings.bands;
        }
    }

    private void inheritPopulationModifier(@Nonnull Set<String> nestedExplicitKeys,
                                           @Nonnull ModifierSettings currentModifiers,
                                           @Nonnull ModifierSettings parentModifiers) {
        if (!nestedExplicitKeys.contains("Population")) {
            currentModifiers.population = parentModifiers.population;
            return;
        }
        if (currentModifiers.population == null) {
            currentModifiers.population = parentModifiers.population;
            return;
        }
        if (parentModifiers.population == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Population.Enabled")) {
            currentModifiers.population.enabled = parentModifiers.population.enabled;
        }
        if (!nestedExplicitKeys.contains("Population.Radius")) {
            currentModifiers.population.radius = parentModifiers.population.radius;
        }
        if (!nestedExplicitKeys.contains("Population.Bands")) {
            currentModifiers.population.bands = parentModifiers.population.bands;
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

    protected TwHappinessConfig() {
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfiguredEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public String[] getRoleIds() {
        return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
    }

    public ValueSettings getValues() {
        return values == null ? new ValueSettings() : values;
    }

    public EquilibriumSettings getEquilibrium() {
        return equilibrium == null ? new EquilibriumSettings() : equilibrium;
    }

    public ImpulseSettings getImpulses() {
        return impulses == null ? new ImpulseSettings() : impulses;
    }

    public ModifierSettings getModifiers() {
        return modifiers == null ? new ModifierSettings() : modifiers;
    }

    /** Bounds and default value for shared happiness state. */
    public static final class ValueSettings {
        private double currentDefault = 50.0;
        private double min = 0.0;
        private double max = 100.0;

        public double getCurrentDefault() {
            return clamp(currentDefault, getMin(), getMax());
        }

        public double getMin() {
            if (!Double.isFinite(min)) {
                return 0.0;
            }
            return min;
        }

        public double getMax() {
            if (!Double.isFinite(max)) {
                return getMin();
            }
            if (max < getMin()) {
                return getMin();
            }
            return max;
        }
    }

    /** Convergence settings for the equilibrium mood target. */
    public static final class EquilibriumSettings {
        private double baseSetpoint = 50.0;
        private double convergencePerMinute = 8.0;

        public double getBaseSetpoint() {
            if (!Double.isFinite(baseSetpoint)) {
                return 50.0;
            }
            return baseSetpoint;
        }

        public double getConvergencePerMinute() {
            if (!Double.isFinite(convergencePerMinute) || convergencePerMinute < 0.0) {
                return 0.0;
            }
            return convergencePerMinute;
        }
    }

    /** Impulse settings for direct events and timed feed effects. */
    public static final class ImpulseSettings {
        private static final double DEFAULT_DURATION_MINUTES = 15.0;
        private double gainOnFeed = 5.0;
        private double handFeedDurationMinutes = DEFAULT_DURATION_MINUTES;
        private double feedImpulseDurationMinutes = DEFAULT_DURATION_MINUTES;
        private double gainOnPet = 3.0;
        private double loseOnDamage = 10.0;
        private Map<String, Double> feedItemImpulses = Map.of();
        private Map<String, Double> feedParamImpulses = Map.of();

        public double getGainOnFeed() {
            if (!Double.isFinite(gainOnFeed)) {
                return 0.0;
            }
            return gainOnFeed;
        }

        public double getHandFeedDurationMinutes() {
            if (!Double.isFinite(handFeedDurationMinutes) || handFeedDurationMinutes <= 0.0) {
                return DEFAULT_DURATION_MINUTES;
            }
            return handFeedDurationMinutes;
        }

        public double getFeedImpulseDurationMinutes() {
            if (!Double.isFinite(feedImpulseDurationMinutes) || feedImpulseDurationMinutes <= 0.0) {
                return DEFAULT_DURATION_MINUTES;
            }
            return feedImpulseDurationMinutes;
        }

        public double getGainOnPet() {
            if (!Double.isFinite(gainOnPet)) {
                return 0.0;
            }
            return gainOnPet;
        }

        public double getLoseOnDamage() {
            if (!Double.isFinite(loseOnDamage)) {
                return 0.0;
            }
            return loseOnDamage;
        }

        @Nonnull
        public Map<String, Double> getFeedItemImpulses() {
            return normalizeImpulseMap(feedItemImpulses, true);
        }

        @Nonnull
        public Map<String, Double> getFeedParamImpulses() {
            return normalizeImpulseMap(feedParamImpulses, false);
        }

        @Nonnull
        private static Map<String, Double> normalizeImpulseMap(@Nullable Map<String, Double> rawValues,
                                                               boolean lowercaseKeys) {
            if (rawValues == null || rawValues.isEmpty()) {
                return Map.of();
            }
            HashMap<String, Double> normalized = new HashMap<>();
            for (Map.Entry<String, Double> entry : rawValues.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String normalizedKey = entry.getKey().trim();
                if (lowercaseKeys) {
                    normalizedKey = normalizedKey.toLowerCase(Locale.ROOT);
                }
                if (normalizedKey.isBlank() || !Double.isFinite(entry.getValue())) {
                    continue;
                }
                normalized.put(normalizedKey, entry.getValue());
            }
            if (normalized.isEmpty()) {
                return Map.of();
            }
            return normalized;
        }
    }

    /** Active modifier groups that offset equilibrium target happiness. */
    public static final class ModifierSettings {
        private NeedModifierSettings hunger = new NeedModifierSettings();
        private NeedModifierSettings thirst = new NeedModifierSettings();
        private PopulationModifierSettings population = new PopulationModifierSettings();
        private double ownerNearbyOffset;

        public NeedModifierSettings getHunger() {
            return hunger == null ? new NeedModifierSettings() : hunger;
        }

        public NeedModifierSettings getThirst() {
            return thirst == null ? new NeedModifierSettings() : thirst;
        }

        public PopulationModifierSettings getPopulation() {
            return population == null ? new PopulationModifierSettings() : population;
        }

        public double getOwnerNearbyOffset() {
            if (!Double.isFinite(ownerNearbyOffset)) {
                return 0.0;
            }
            return ownerNearbyOffset;
        }
    }

    /** Need-specific modifier bands (for hunger/thirst). */
    public static final class NeedModifierSettings {
        private boolean enabled = true;
        private NeedBandSettings[] bands = EMPTY_BANDS;

        public boolean isEnabled() {
            return enabled;
        }

        public NeedBandSettings[] getBands() {
            return bands == null ? EMPTY_BANDS : bands;
        }
    }

    /** Population band modifiers based on nearby same-type companion count. */
    public static final class PopulationModifierSettings {
        private boolean enabled;
        private double radius = 14.0;
        private PopulationBandSettings[] bands = EMPTY_POPULATION_BANDS;

        public boolean isEnabled() {
            return enabled;
        }

        public double getRadius() {
            if (!Double.isFinite(radius) || radius <= 0.0) {
                return 0.0;
            }
            return radius;
        }

        public PopulationBandSettings[] getBands() {
            return bands == null ? EMPTY_POPULATION_BANDS : bands;
        }
    }

    /** One nearby-count band contributing an equilibrium happiness offset. */
    public static final class PopulationBandSettings {
        private String id;
        private String label;
        private int minCount;
        private int maxCount = -1;
        private double offset;

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public int getMinCount() {
            return Math.max(0, minCount);
        }

        public int getMaxCount() {
            if (maxCount < 0) {
                return -1;
            }
            return Math.max(getMinCount(), maxCount);
        }

        public double getOffset() {
            if (!Double.isFinite(offset)) {
                return 0.0;
            }
            return offset;
        }
    }

    /** One percentage band contributing an offset to equilibrium target. */
    public static final class NeedBandSettings {
        private String id;
        private String label;
        private double minPercent;
        private double maxPercent = 100.0;
        private double offset;

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public double getMinPercent() {
            if (!Double.isFinite(minPercent)) {
                return 0.0;
            }
            return clamp(minPercent, 0.0, 100.0);
        }

        public double getMaxPercent() {
            if (!Double.isFinite(maxPercent)) {
                return 100.0;
            }
            return clamp(maxPercent, 0.0, 100.0);
        }

        public double getOffset() {
            if (!Double.isFinite(offset)) {
                return 0.0;
            }
            return offset;
        }
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}



