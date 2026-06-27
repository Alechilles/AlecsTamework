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
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed role food preference configuration.
 * Stored under Server/Tamework/Food.
 */
public final class TwFoodConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwFoodConfig>>,
        TwParentFallbackAsset<TwFoodConfig> {
    private static final String[] EMPTY_ITEMS = new String[0];
    private static final Map<String, RoleOverrideSettings> EMPTY_ROLE_OVERRIDES = Map.of();

    private static final BuilderCodec<FoodSettings> FOOD_CODEC = BuilderCodec.builder(
            FoodSettings.class,
            FoodSettings::new
    )
        .<String[]>append(
            new KeyedCodec<>("Preferred", Codec.STRING_ARRAY),
            (settings, value) -> settings.preferred = value == null ? EMPTY_ITEMS : value,
            settings -> settings.preferred
        )
        .documentation("Role-specific favorite or taming foods. Inheritance: explicit array replaces parent value (no merge).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Premium", Codec.STRING_ARRAY),
            (settings, value) -> settings.premium = value == null ? EMPTY_ITEMS : value,
            settings -> settings.premium
        )
        .documentation("High-value foods consumed before preferred foods by needs refill. Inheritance: explicit array replaces parent value (no merge).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Compatible", Codec.STRING_ARRAY),
            (settings, value) -> settings.compatible = value == null ? EMPTY_ITEMS : value,
            settings -> settings.compatible
        )
        .documentation("Accepted normal foods. Inheritance: explicit array replaces parent value (no merge).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Disliked", Codec.STRING_ARRAY),
            (settings, value) -> settings.disliked = value == null ? EMPTY_ITEMS : value,
            settings -> settings.disliked
        )
        .documentation("Foods the NPC can eat but receives a negative happiness effect from. Inheritance: explicit array replaces parent value (no merge).")
        .add()
        .build();

    private static final BuilderCodec<HappinessSettings> HAPPINESS_CODEC = BuilderCodec.builder(
            HappinessSettings.class,
            HappinessSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("Preferred", Codec.DOUBLE),
            (settings, value) -> settings.preferred = value == null ? settings.preferred : value,
            settings -> settings.preferred
        )
        .documentation("Happiness impulse for Preferred foods. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Premium", Codec.DOUBLE),
            (settings, value) -> settings.premium = value == null ? settings.premium : value,
            settings -> settings.premium
        )
        .documentation("Happiness impulse for Premium foods. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Compatible", Codec.DOUBLE),
            (settings, value) -> settings.compatible = value == null ? settings.compatible : value,
            settings -> settings.compatible
        )
        .documentation("Happiness impulse for Compatible foods. Inheritance: missing nested key inherits parent value.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Disliked", Codec.DOUBLE),
            (settings, value) -> settings.disliked = value == null ? settings.disliked : value,
            settings -> settings.disliked
        )
        .documentation("Happiness impulse for Disliked foods. Negative values are allowed. Inheritance: missing nested key inherits parent value.")
        .add()
        .build();

    private static final BuilderCodec<FoodOverrideSettings> FOOD_OVERRIDE_CODEC = BuilderCodec.builder(
            FoodOverrideSettings.class,
            FoodOverrideSettings::new
    )
        .<String[]>append(
            new KeyedCodec<>("Preferred", Codec.STRING_ARRAY),
            (settings, value) -> settings.preferred = value,
            settings -> settings.preferred
        )
        .documentation("Optional per-role Preferred replacement. Explicit array replaces the family category.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Premium", Codec.STRING_ARRAY),
            (settings, value) -> settings.premium = value,
            settings -> settings.premium
        )
        .documentation("Optional per-role Premium replacement. Explicit array replaces the family category.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Compatible", Codec.STRING_ARRAY),
            (settings, value) -> settings.compatible = value,
            settings -> settings.compatible
        )
        .documentation("Optional per-role Compatible replacement. Explicit array replaces the family category.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Disliked", Codec.STRING_ARRAY),
            (settings, value) -> settings.disliked = value,
            settings -> settings.disliked
        )
        .documentation("Optional per-role Disliked replacement. Explicit array replaces the family category.")
        .add()
        .build();

    private static final BuilderCodec<HappinessOverrideSettings> HAPPINESS_OVERRIDE_CODEC = BuilderCodec.builder(
            HappinessOverrideSettings.class,
            HappinessOverrideSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("Preferred", Codec.DOUBLE),
            (settings, value) -> settings.preferred = value,
            settings -> settings.preferred
        )
        .documentation("Optional per-role Preferred happiness replacement.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Premium", Codec.DOUBLE),
            (settings, value) -> settings.premium = value,
            settings -> settings.premium
        )
        .documentation("Optional per-role Premium happiness replacement.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Compatible", Codec.DOUBLE),
            (settings, value) -> settings.compatible = value,
            settings -> settings.compatible
        )
        .documentation("Optional per-role Compatible happiness replacement.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Disliked", Codec.DOUBLE),
            (settings, value) -> settings.disliked = value,
            settings -> settings.disliked
        )
        .documentation("Optional per-role Disliked happiness replacement.")
        .add()
        .build();

    private static final BuilderCodec<RoleOverrideSettings> ROLE_OVERRIDE_CODEC = BuilderCodec.builder(
            RoleOverrideSettings.class,
            RoleOverrideSettings::new
    )
        .<FoodOverrideSettings>append(
            new KeyedCodec<>("Foods", FOOD_OVERRIDE_CODEC),
            (settings, value) -> settings.foods = value,
            settings -> settings.foods
        )
        .documentation("Optional per-role food category replacements. Missing categories inherit the family category.")
        .add()
        .<HappinessOverrideSettings>append(
            new KeyedCodec<>("Happiness", HAPPINESS_OVERRIDE_CODEC),
            (settings, value) -> settings.happiness = value,
            settings -> settings.happiness
        )
        .documentation("Optional per-role happiness replacements. Missing values inherit the family value.")
        .add()
        .build();

    private static final MapCodec<RoleOverrideSettings, Map<String, RoleOverrideSettings>> ROLE_OVERRIDES_CODEC =
            new MapCodec<>(ROLE_OVERRIDE_CODEC, HashMap::new);

    public static final AssetBuilderCodec<String, TwFoodConfig> CODEC = AssetBuilderCodec.builder(
            TwFoodConfig.class,
            TwFoodConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
        .documentation("Food preference configuration for Alec's Tamework companions.")
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
        .documentation("NPC role IDs this config applies to. Inheritance: omitted value inherits from parent; explicit array replaces parent value (no merge).")
        .add()
        .<FoodSettings>append(
            new KeyedCodec<>("Foods", FOOD_CODEC),
            (asset, value) -> asset.foods = value == null ? new FoodSettings() : value,
            asset -> asset.foods
        )
        .documentation("Family food categories. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent. Arrays replace instead of merging.")
        .add()
        .<HappinessSettings>append(
            new KeyedCodec<>("Happiness", HAPPINESS_CODEC),
            (asset, value) -> asset.happiness = value == null ? new HappinessSettings() : value,
            asset -> asset.happiness
        )
        .documentation("Happiness impulses for each food category. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
        .add()
        .<Map<String, RoleOverrideSettings>>append(
            new KeyedCodec<>("RoleOverrides", ROLE_OVERRIDES_CODEC),
            (asset, value) -> asset.roleOverrides = value == null ? EMPTY_ROLE_OVERRIDES : value,
            asset -> asset.roleOverrides
        )
        .documentation("Per-role food and happiness override patches. Inheritance: explicit map replaces parent map (no merge); omitted map inherits parent map.")
        .add()
        .build();

    private static AssetStore<String, TwFoodConfig, DefaultAssetMap<String, TwFoodConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwFoodConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private FoodSettings foods = new FoodSettings();
    private HappinessSettings happiness = new HappinessSettings();
    private Map<String, RoleOverrideSettings> roleOverrides = EMPTY_ROLE_OVERRIDES;

    public static AssetStore<String, TwFoodConfig, DefaultAssetMap<String, TwFoodConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwFoodConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwFoodConfig> getAssetMap() {
        AssetStore<String, TwFoodConfig, DefaultAssetMap<String, TwFoodConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwFoodConfig> assetMap = (DefaultAssetMap<String, TwFoodConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwFoodConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwFoodConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwFoodConfig> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap);
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        return cache.get(normalizeRoleCacheKey(roleId));
    }

    @Nullable
    public static TwFoodConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwFoodConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwFoodConfig> map = assetMap.getAssetMap();
        TwFoodConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwFoodConfig candidate : map.values()) {
            if (candidate != null && candidate.getId() != null && candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    public static ResolvedFoodProfile resolveProfileForRole(@Nullable String roleId) {
        TwFoodConfig config = resolveForRole(roleId);
        return config != null ? config.resolveProfile(roleId) : null;
    }

    @Nonnull
    public static String[] resolveAcceptedItemIdsForRole(@Nullable String roleId) {
        ResolvedFoodProfile profile = resolveProfileForRole(roleId);
        return profile != null ? profile.acceptedItemIds() : EMPTY_ITEMS;
    }

    @Nonnull
    public static String[] resolveNeedsConsumeItemIdsForRole(@Nullable String roleId) {
        ResolvedFoodProfile profile = resolveProfileForRole(roleId);
        return profile != null ? profile.needsConsumeItemIds() : EMPTY_ITEMS;
    }

    @Nullable
    public static Double resolveHappinessDeltaForRole(@Nullable String roleId, @Nullable String itemId) {
        ResolvedFoodProfile profile = resolveProfileForRole(roleId);
        return profile != null ? profile.resolveHappinessDelta(itemId) : null;
    }

    @Nonnull
    private static Map<String, TwFoodConfig> buildRoleCache(@Nullable DefaultAssetMap<String, TwFoodConfig> assetMap) {
        Map<String, TwFoodConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwFoodConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                registerRoleCacheEntry(cache, candidate, roleId);
            }
            for (String roleId : candidate.getRoleOverrides().keySet()) {
                registerRoleCacheEntry(cache, candidate, roleId);
            }
        }
        return cache;
    }

    private static void registerRoleCacheEntry(@Nonnull Map<String, TwFoodConfig> cache,
                                               @Nullable TwFoodConfig candidate,
                                               @Nullable String roleId) {
        if (candidate == null || roleId == null || roleId.isBlank()) {
            return;
        }
        String normalizedRole = normalizeRoleCacheKey(roleId);
        TwFoodConfig existing = cache.get(normalizedRole);
        if (shouldReplaceCandidate(candidate, existing)) {
            cache.put(normalizedRole, candidate);
        }
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwFoodConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwFoodConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwFoodConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Foods")) {
            foods = parent.foods;
        } else {
            inheritFoodsSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Foods"));
        }
        if (!explicitTopLevelKeys.contains("Happiness")) {
            happiness = parent.happiness;
        } else {
            inheritHappinessSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Happiness"));
        }
        if (!explicitTopLevelKeys.contains("RoleOverrides")) roleOverrides = parent.roleOverrides;
    }

    private void inheritFoodsSection(@Nonnull TwFoodConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (foods == null) {
            foods = parent.foods;
            return;
        }
        if (parent.foods == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Preferred")) foods.preferred = parent.foods.preferred;
        if (!nestedExplicitKeys.contains("Premium")) foods.premium = parent.foods.premium;
        if (!nestedExplicitKeys.contains("Compatible")) foods.compatible = parent.foods.compatible;
        if (!nestedExplicitKeys.contains("Disliked")) foods.disliked = parent.foods.disliked;
    }

    private void inheritHappinessSection(@Nonnull TwFoodConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (happiness == null) {
            happiness = parent.happiness;
            return;
        }
        if (parent.happiness == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Preferred")) happiness.preferred = parent.happiness.preferred;
        if (!nestedExplicitKeys.contains("Premium")) happiness.premium = parent.happiness.premium;
        if (!nestedExplicitKeys.contains("Compatible")) happiness.compatible = parent.happiness.compatible;
        if (!nestedExplicitKeys.contains("Disliked")) happiness.disliked = parent.happiness.disliked;
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    @Nullable
    private RoleOverrideSettings resolveRoleOverride(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        String normalized = normalizeRoleCacheKey(roleId);
        for (Map.Entry<String, RoleOverrideSettings> entry : getRoleOverrides().entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (normalizeRoleCacheKey(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Nonnull
    public ResolvedFoodProfile resolveProfile(@Nullable String roleId) {
        FoodSettings resolvedFoods = copyFoods(getFoods());
        HappinessSettings resolvedHappiness = copyHappiness(getHappiness());
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override != null) {
            override.applyTo(resolvedFoods, resolvedHappiness);
        }
        return new ResolvedFoodProfile(resolvedFoods, resolvedHappiness);
    }

    private static FoodSettings copyFoods(@Nullable FoodSettings source) {
        FoodSettings copy = new FoodSettings();
        if (source == null) {
            return copy;
        }
        copy.preferred = source.getPreferred();
        copy.premium = source.getPremium();
        copy.compatible = source.getCompatible();
        copy.disliked = source.getDisliked();
        return copy;
    }

    private static HappinessSettings copyHappiness(@Nullable HappinessSettings source) {
        HappinessSettings copy = new HappinessSettings();
        if (source == null) {
            return copy;
        }
        copy.preferred = source.preferred;
        copy.premium = source.premium;
        copy.compatible = source.compatible;
        copy.disliked = source.disliked;
        return copy;
    }

    protected TwFoodConfig() {}

    public String getId() { return id; }

    public boolean isEnabled() { return enabled; }

    public int getPriority() { return priority; }

    public String[] getRoleIds() { return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds; }

    public FoodSettings getFoods() { return foods == null ? new FoodSettings() : foods; }

    public HappinessSettings getHappiness() { return happiness == null ? new HappinessSettings() : happiness; }

    public Map<String, RoleOverrideSettings> getRoleOverrides() {
        return roleOverrides == null ? EMPTY_ROLE_OVERRIDES : roleOverrides;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwFoodConfig candidate, @Nullable TwFoodConfig existing) {
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

    private static String normalizeRoleCacheKey(@Nonnull String roleId) {
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            return normalized.substring(separator + 1);
        }
        return normalized;
    }

    @Nullable
    private static String normalizeItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return itemId.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String[] cleanItems(@Nullable String[] items) {
        if (items == null || items.length == 0) {
            return EMPTY_ITEMS;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                out.add(item.trim());
            }
        }
        return out.isEmpty() ? EMPTY_ITEMS : out.toArray(new String[0]);
    }

    public enum FoodCategory {
        Preferred,
        Premium,
        Compatible,
        Disliked
    }

    public static final class FoodSettings {
        private String[] preferred = EMPTY_ITEMS;
        private String[] premium = EMPTY_ITEMS;
        private String[] compatible = EMPTY_ITEMS;
        private String[] disliked = EMPTY_ITEMS;

        public String[] getPreferred() { return cleanItems(preferred); }

        public String[] getPremium() { return cleanItems(premium); }

        public String[] getCompatible() { return cleanItems(compatible); }

        public String[] getDisliked() { return cleanItems(disliked); }

        @Nonnull
        private String[] get(FoodCategory category) {
            return switch (category) {
                case Preferred -> getPreferred();
                case Premium -> getPremium();
                case Compatible -> getCompatible();
                case Disliked -> getDisliked();
            };
        }
    }

    public static final class HappinessSettings {
        private double preferred = 6.0;
        private double premium = 10.0;
        private double compatible = 2.0;
        private double disliked = -10.0;

        public double getPreferred() { return safe(preferred, 6.0); }

        public double getPremium() { return safe(premium, 10.0); }

        public double getCompatible() { return safe(compatible, 2.0); }

        public double getDisliked() { return safe(disliked, -10.0); }

        private double get(FoodCategory category) {
            return switch (category) {
                case Preferred -> getPreferred();
                case Premium -> getPremium();
                case Compatible -> getCompatible();
                case Disliked -> getDisliked();
            };
        }

        private static double safe(double value, double fallback) {
            return Double.isFinite(value) ? value : fallback;
        }
    }

    public static final class FoodOverrideSettings {
        private String[] preferred;
        private String[] premium;
        private String[] compatible;
        private String[] disliked;

        private void applyTo(@Nonnull FoodSettings target) {
            if (preferred != null) target.preferred = preferred;
            if (premium != null) target.premium = premium;
            if (compatible != null) target.compatible = compatible;
            if (disliked != null) target.disliked = disliked;
        }
    }

    public static final class HappinessOverrideSettings {
        private Double preferred;
        private Double premium;
        private Double compatible;
        private Double disliked;

        private void applyTo(@Nonnull HappinessSettings target) {
            if (preferred != null) target.preferred = preferred;
            if (premium != null) target.premium = premium;
            if (compatible != null) target.compatible = compatible;
            if (disliked != null) target.disliked = disliked;
        }
    }

    public static final class RoleOverrideSettings {
        private FoodOverrideSettings foods;
        private HappinessOverrideSettings happiness;

        private void applyTo(@Nonnull FoodSettings targetFoods, @Nonnull HappinessSettings targetHappiness) {
            if (foods != null) {
                foods.applyTo(targetFoods);
            }
            if (happiness != null) {
                happiness.applyTo(targetHappiness);
            }
        }

        @Nullable
        public FoodOverrideSettings getFoods() {
            return foods;
        }

        @Nullable
        public HappinessOverrideSettings getHappiness() {
            return happiness;
        }
    }

    public record FoodEntry(@Nonnull String itemId, @Nonnull FoodCategory category, double happinessDelta) {}

    public static final class ResolvedFoodProfile {
        private final FoodSettings foods;
        private final HappinessSettings happiness;

        private ResolvedFoodProfile(@Nonnull FoodSettings foods, @Nonnull HappinessSettings happiness) {
            this.foods = foods;
            this.happiness = happiness;
        }

        public boolean hasAnyFood() {
            return displayEntries(true).length > 0;
        }

        @Nonnull
        public String[] preferredItemIds() {
            return foods.getPreferred();
        }

        @Nonnull
        public String[] acceptedItemIds() {
            return uniqueItems(FoodCategory.Preferred, FoodCategory.Premium, FoodCategory.Compatible, FoodCategory.Disliked);
        }

        @Nonnull
        public String[] needsConsumeItemIds() {
            return uniqueItems(FoodCategory.Premium, FoodCategory.Preferred, FoodCategory.Compatible, FoodCategory.Disliked);
        }

        @Nonnull
        public FoodEntry[] displayEntries(boolean tamed) {
            if (!tamed) {
                return entries(FoodCategory.Preferred);
            }
            return entries(FoodCategory.Preferred, FoodCategory.Premium, FoodCategory.Compatible, FoodCategory.Disliked);
        }

        @Nullable
        public Double resolveHappinessDelta(@Nullable String itemId) {
            String normalizedItem = normalizeItemId(itemId);
            if (normalizedItem == null) {
                return null;
            }
            for (FoodCategory category : FoodCategory.values()) {
                for (String candidate : foods.get(category)) {
                    String normalizedCandidate = normalizeItemId(candidate);
                    if (normalizedItem.equals(normalizedCandidate)) {
                        return happiness.get(category);
                    }
                }
            }
            return null;
        }

        @Nonnull
        private String[] uniqueItems(FoodCategory... categories) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (FoodCategory category : categories) {
                for (String item : foods.get(category)) {
                    if (item != null && !item.isBlank()) {
                        out.add(item.trim());
                    }
                }
            }
            return out.isEmpty() ? EMPTY_ITEMS : out.toArray(new String[0]);
        }

        @Nonnull
        private FoodEntry[] entries(FoodCategory... categories) {
            List<FoodEntry> out = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (FoodCategory category : categories) {
                for (String item : foods.get(category)) {
                    if (item == null || item.isBlank()) {
                        continue;
                    }
                    String clean = item.trim();
                    String normalized = normalizeItemId(clean);
                    if (normalized == null || !seen.add(normalized)) {
                        continue;
                    }
                    out.add(new FoodEntry(clean, category, happiness.get(category)));
                }
            }
            return out.isEmpty() ? new FoodEntry[0] : out.toArray(new FoodEntry[0]);
        }
    }
}
