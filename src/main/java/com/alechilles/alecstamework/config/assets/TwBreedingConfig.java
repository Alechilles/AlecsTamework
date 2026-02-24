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
import javax.annotation.Nullable;

/**
 * Asset-backed breeding configuration for role-scoped companion breeding rules.
 * Stored under Server/Tamework/Breeding.
 */
public final class TwBreedingConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwBreedingConfig>> {
    private static final BuilderCodec<HappinessSettings> HAPPINESS_CODEC = BuilderCodec.builder(
            HappinessSettings.class,
            HappinessSettings::new
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
        .<Double>append(
            new KeyedCodec<>("Threshold", Codec.DOUBLE),
            (settings, value) -> settings.threshold = value,
            settings -> settings.threshold
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("DecayPerMinute", Codec.DOUBLE),
            (settings, value) -> settings.decayPerMinute = value,
            settings -> settings.decayPerMinute
        )
        .add()
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
            new KeyedCodec<>("GainPerMinuteNearOwner", Codec.DOUBLE),
            (settings, value) -> settings.gainPerMinuteNearOwner = value,
            settings -> settings.gainPerMinuteNearOwner
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("LoseOnDamage", Codec.DOUBLE),
            (settings, value) -> settings.loseOnDamage = value,
            settings -> settings.loseOnDamage
        )
        .add()
        .build();

    private static final BuilderCodec<EligibilitySettings> ELIGIBILITY_CODEC = BuilderCodec.builder(
            EligibilitySettings.class,
            EligibilitySettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (settings, value) -> settings.requireTamed = value,
            settings -> settings.requireTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireAdult", Codec.BOOLEAN),
            (settings, value) -> settings.requireAdult = value,
            settings -> settings.requireAdult
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireNotInCombat", Codec.BOOLEAN),
            (settings, value) -> settings.requireNotInCombat = value,
            settings -> settings.requireNotInCombat
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireNotSleeping", Codec.BOOLEAN),
            (settings, value) -> settings.requireNotSleeping = value,
            settings -> settings.requireNotSleeping
        )
        .add()
        .build();

    private static final BuilderCodec<PairingSettings> PAIRING_CODEC = BuilderCodec.builder(
            PairingSettings.class,
            PairingSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("BreedRadius", Codec.DOUBLE),
            (settings, value) -> settings.breedRadius = value,
            settings -> settings.breedRadius
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireWanderMode", Codec.BOOLEAN),
            (settings, value) -> settings.requireWanderMode = value,
            settings -> settings.requireWanderMode
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireSameOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireSameOwner = value,
            settings -> settings.requireSameOwner
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxNearbyOffspring", Codec.INTEGER),
            (settings, value) -> settings.maxNearbyOffspring = value,
            settings -> settings.maxNearbyOffspring
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireSameRoleId", Codec.BOOLEAN),
            (settings, value) -> settings.requireSameRoleId = value,
            settings -> settings.requireSameRoleId
        )
        .add()
        .build();

    private static final BuilderCodec<CooldownSettings> COOLDOWN_CODEC = BuilderCodec.builder(
            CooldownSettings.class,
            CooldownSettings::new
    )
        .<Integer>append(
            new KeyedCodec<>("BaseCooldownSeconds", Codec.INTEGER),
            (settings, value) -> settings.baseCooldownSeconds = value,
            settings -> settings.baseCooldownSeconds
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("MinDelaySeconds", Codec.INTEGER),
            (settings, value) -> settings.minDelaySeconds = value,
            settings -> settings.minDelaySeconds
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxDelaySeconds", Codec.INTEGER),
            (settings, value) -> settings.maxDelaySeconds = value,
            settings -> settings.maxDelaySeconds
        )
        .add()
        .build();

    private static final BuilderCodec<InheritanceSettings> INHERITANCE_CODEC = BuilderCodec.builder(
            InheritanceSettings.class,
            InheritanceSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("InheritOwner", Codec.BOOLEAN),
            (settings, value) -> settings.inheritOwner = value,
            settings -> settings.inheritOwner
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritTamed", Codec.BOOLEAN),
            (settings, value) -> settings.inheritTamed = value,
            settings -> settings.inheritTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritAttachments", Codec.BOOLEAN),
            (settings, value) -> settings.inheritAttachments = value,
            settings -> settings.inheritAttachments
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritTraits", Codec.BOOLEAN),
            (settings, value) -> settings.inheritTraits = value,
            settings -> settings.inheritTraits
        )
        .add()
        .build();

    public static final AssetBuilderCodec<String, TwBreedingConfig> CODEC = AssetBuilderCodec.builder(
            TwBreedingConfig.class,
            TwBreedingConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
        .documentation("Breeding configuration for Alec's Tamework companions.")
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
        .<HappinessSettings>append(
            new KeyedCodec<>("Happiness", HAPPINESS_CODEC),
            (asset, value) -> asset.happiness = value == null ? new HappinessSettings() : value,
            asset -> asset.happiness
        )
        .add()
        .<EligibilitySettings>append(
            new KeyedCodec<>("Eligibility", ELIGIBILITY_CODEC),
            (asset, value) -> asset.eligibility = value == null ? new EligibilitySettings() : value,
            asset -> asset.eligibility
        )
        .add()
        .<PairingSettings>append(
            new KeyedCodec<>("Pairing", PAIRING_CODEC),
            (asset, value) -> asset.pairing = value == null ? new PairingSettings() : value,
            asset -> asset.pairing
        )
        .add()
        .<CooldownSettings>append(
            new KeyedCodec<>("Cooldowns", COOLDOWN_CODEC),
            (asset, value) -> asset.cooldowns = value == null ? new CooldownSettings() : value,
            asset -> asset.cooldowns
        )
        .add()
        .<InheritanceSettings>append(
            new KeyedCodec<>("Inheritance", INHERITANCE_CODEC),
            (asset, value) -> asset.inheritance = value == null ? new InheritanceSettings() : value,
            asset -> asset.inheritance
        )
        .add()
        .build();

    private static AssetStore<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> ASSET_STORE;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwBreedingConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private HappinessSettings happiness = new HappinessSettings();
    private EligibilitySettings eligibility = new EligibilitySettings();
    private PairingSettings pairing = new PairingSettings();
    private CooldownSettings cooldowns = new CooldownSettings();
    private InheritanceSettings inheritance = new InheritanceSettings();

    public static AssetStore<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwBreedingConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwBreedingConfig> getAssetMap() {
        AssetStore<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, TwBreedingConfig>) store.getAssetMap();
    }

    public static void clearRoleCache() {
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwBreedingConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwBreedingConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwBreedingConfig> cache = ROLE_CACHE;
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
    public static TwBreedingConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwBreedingConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwBreedingConfig> map = assetMap.getAssetMap();
        TwBreedingConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwBreedingConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwBreedingConfig> buildRoleCache(
            @Nullable DefaultAssetMap<String, TwBreedingConfig> assetMap) {
        Map<String, TwBreedingConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwBreedingConfig candidate : assetMap.getAssetMap().values()) {
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
                TwBreedingConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwBreedingConfig candidate,
                                                  @Nullable TwBreedingConfig existing) {
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

    protected TwBreedingConfig() {
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

    public HappinessSettings getHappiness() {
        return happiness == null ? new HappinessSettings() : happiness;
    }

    public EligibilitySettings getEligibility() {
        return eligibility == null ? new EligibilitySettings() : eligibility;
    }

    public PairingSettings getPairing() {
        return pairing == null ? new PairingSettings() : pairing;
    }

    public CooldownSettings getCooldowns() {
        return cooldowns == null ? new CooldownSettings() : cooldowns;
    }

    public InheritanceSettings getInheritance() {
        return inheritance == null ? new InheritanceSettings() : inheritance;
    }

    /** Tunable values for breeding happiness gates and progression. */
    public static final class HappinessSettings {
        private double currentDefault = 50.0;
        private double min = 0.0;
        private double max = 100.0;
        private double threshold = 70.0;
        private double decayPerMinute = 1.0;
        private double gainOnFeed = 5.0;
        private double gainOnPet = 3.0;
        private double gainPerMinuteNearOwner = 1.0;
        private double loseOnDamage = 10.0;

        public double getCurrentDefault() {
            return currentDefault;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }

        public double getThreshold() {
            return threshold;
        }

        public double getDecayPerMinute() {
            return decayPerMinute;
        }

        public double getGainOnFeed() {
            return gainOnFeed;
        }

        public double getGainOnPet() {
            return gainOnPet;
        }

        public double getGainPerMinuteNearOwner() {
            return gainPerMinuteNearOwner;
        }

        public double getLoseOnDamage() {
            return loseOnDamage;
        }
    }

    /** Eligibility gate toggles for breeding attempts. */
    public static final class EligibilitySettings {
        private boolean requireTamed = true;
        private boolean requireAdult = true;
        private boolean requireNotInCombat = true;
        private boolean requireNotSleeping = true;

        public boolean isRequireTamed() {
            return requireTamed;
        }

        public boolean isRequireAdult() {
            return requireAdult;
        }

        public boolean isRequireNotInCombat() {
            return requireNotInCombat;
        }

        public boolean isRequireNotSleeping() {
            return requireNotSleeping;
        }
    }

    /** Role-level partner matching and nearby offspring rules. */
    public static final class PairingSettings {
        private double breedRadius = 10.0;
        private boolean requireWanderMode = true;
        private boolean requireSameOwner;
        private int maxNearbyOffspring;
        private boolean requireSameRoleId = true;

        public double getBreedRadius() {
            return breedRadius;
        }

        public boolean isRequireWanderMode() {
            return requireWanderMode;
        }

        public boolean isRequireSameOwner() {
            return requireSameOwner;
        }

        public int getMaxNearbyOffspring() {
            return maxNearbyOffspring;
        }

        public boolean isRequireSameRoleId() {
            return requireSameRoleId;
        }
    }

    /** Cooldown windows and randomized delay knobs for breeding checks. */
    public static final class CooldownSettings {
        private int baseCooldownSeconds = 600;
        private int minDelaySeconds = 15;
        private int maxDelaySeconds = 45;

        public int getBaseCooldownSeconds() {
            return baseCooldownSeconds;
        }

        public int getMinDelaySeconds() {
            return minDelaySeconds;
        }

        public int getMaxDelaySeconds() {
            return maxDelaySeconds;
        }
    }

    /** Offspring inheritance toggles for breeding outcomes. */
    public static final class InheritanceSettings {
        private boolean inheritOwner = true;
        private boolean inheritTamed = true;
        private boolean inheritAttachments;
        private boolean inheritTraits;

        public boolean isInheritOwner() {
            return inheritOwner;
        }

        public boolean isInheritTamed() {
            return inheritTamed;
        }

        public boolean isInheritAttachments() {
            return inheritAttachments;
        }

        public boolean isInheritTraits() {
            return inheritTraits;
        }
    }
}
