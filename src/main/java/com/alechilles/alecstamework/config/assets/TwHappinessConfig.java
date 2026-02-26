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
 * Asset-backed companion happiness configuration for role-scoped happiness progression.
 * Stored under Server/Tamework/Happiness.
 */
public final class TwHappinessConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwHappinessConfig>> {
    private static final NeedBandSettings[] EMPTY_BANDS = new NeedBandSettings[0];

    private static final BuilderCodec<ValueSettings> VALUE_CODEC = BuilderCodec.builder(
            ValueSettings.class,
            ValueSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("CurrentDefault", Codec.DOUBLE),
            (settings, value) -> settings.currentDefault = value,
            settings -> settings.currentDefault
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Min", Codec.DOUBLE),
            (settings, value) -> settings.min = value,
            settings -> settings.min
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Max", Codec.DOUBLE),
            (settings, value) -> settings.max = value,
            settings -> settings.max
        )
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
        .add()
        .<Double>append(
            new KeyedCodec<>("ConvergencePerMinute", Codec.DOUBLE),
            (settings, value) -> settings.convergencePerMinute = value,
            settings -> settings.convergencePerMinute
        )
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
        .add()
        .<Double>append(
            new KeyedCodec<>("GainOnPet", Codec.DOUBLE),
            (settings, value) -> settings.gainOnPet = value,
            settings -> settings.gainOnPet
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("LoseOnDamage", Codec.DOUBLE),
            (settings, value) -> settings.loseOnDamage = value,
            settings -> settings.loseOnDamage
        )
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
        .add()
        .<String>append(
            new KeyedCodec<>("Label", Codec.STRING),
            (settings, value) -> settings.label = value,
            settings -> settings.label
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MinPercent", Codec.DOUBLE),
            (settings, value) -> settings.minPercent = value,
            settings -> settings.minPercent
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MaxPercent", Codec.DOUBLE),
            (settings, value) -> settings.maxPercent = value,
            settings -> settings.maxPercent
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("Offset", Codec.DOUBLE),
            (settings, value) -> settings.offset = value,
            settings -> settings.offset
        )
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
        .add()
        .<NeedBandSettings[]>append(
            new KeyedCodec<>("Bands", NEED_BAND_ARRAY_CODEC),
            (settings, value) -> settings.bands = value == null ? EMPTY_BANDS : value,
            settings -> settings.bands
        )
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
        .add()
        .<NeedModifierSettings>append(
            new KeyedCodec<>("Thirst", NEED_MODIFIER_CODEC),
            (settings, value) -> settings.thirst = value == null ? new NeedModifierSettings() : value,
            settings -> settings.thirst
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("OwnerNearbyOffset", Codec.DOUBLE),
            (settings, value) -> settings.ownerNearbyOffset = value,
            settings -> settings.ownerNearbyOffset
        )
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
        .<ValueSettings>append(
            new KeyedCodec<>("Values", VALUE_CODEC),
            (asset, value) -> asset.values = value == null ? new ValueSettings() : value,
            asset -> asset.values
        )
        .add()
        .<EquilibriumSettings>append(
            new KeyedCodec<>("Equilibrium", EQUILIBRIUM_CODEC),
            (asset, value) -> asset.equilibrium = value == null ? new EquilibriumSettings() : value,
            asset -> asset.equilibrium
        )
        .add()
        .<ImpulseSettings>append(
            new KeyedCodec<>("Impulses", IMPULSE_CODEC),
            (asset, value) -> asset.impulses = value == null ? new ImpulseSettings() : value,
            asset -> asset.impulses
        )
        .add()
        .<ModifierSettings>append(
            new KeyedCodec<>("Modifiers", MODIFIER_CODEC),
            (asset, value) -> asset.modifiers = value == null ? new ModifierSettings() : value,
            asset -> asset.modifiers
        )
        .add()
        .build();

    private static AssetStore<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> ASSET_STORE;
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
        return (DefaultAssetMap<String, TwHappinessConfig>) store.getAssetMap();
    }

    public static void clearRoleCache() {
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

    protected TwHappinessConfig() {
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

    /** Event-style impulse values applied immediately to current happiness. */
    public static final class ImpulseSettings {
        private double gainOnFeed = 5.0;
        private double gainOnPet = 3.0;
        private double loseOnDamage = 10.0;

        public double getGainOnFeed() {
            if (!Double.isFinite(gainOnFeed)) {
                return 0.0;
            }
            return gainOnFeed;
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
    }

    /** Active modifier groups that offset equilibrium target happiness. */
    public static final class ModifierSettings {
        private NeedModifierSettings hunger = new NeedModifierSettings();
        private NeedModifierSettings thirst = new NeedModifierSettings();
        private double ownerNearbyOffset;

        public NeedModifierSettings getHunger() {
            return hunger == null ? new NeedModifierSettings() : hunger;
        }

        public NeedModifierSettings getThirst() {
            return thirst == null ? new NeedModifierSettings() : thirst;
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
