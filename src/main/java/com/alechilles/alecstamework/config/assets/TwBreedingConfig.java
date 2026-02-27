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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed breeding configuration for role-scoped companion breeding rules.
 * Stored under Server/Tamework/Breeding.
 */
public final class TwBreedingConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwBreedingConfig>> {
    private static final RoleFamily[] EMPTY_ROLE_FAMILIES = new RoleFamily[0];

    private static final BuilderCodec<HappinessSettings> HAPPINESS_CODEC = BuilderCodec.builder(
            HappinessSettings.class,
            HappinessSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("Threshold", Codec.DOUBLE),
            (settings, value) -> settings.threshold = value,
            settings -> settings.threshold
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
            new KeyedCodec<>("MaxNearbySameType", Codec.INTEGER),
            (settings, value) -> settings.maxNearbySameType = value,
            settings -> settings.maxNearbySameType
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

    private static final BuilderCodec<AttachmentInheritanceSettings> ATTACHMENT_INHERITANCE_CODEC = BuilderCodec.builder(
            AttachmentInheritanceSettings.class,
            AttachmentInheritanceSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("ParentWeight", Codec.DOUBLE),
            (settings, value) -> settings.parentWeight = value,
            settings -> settings.parentWeight
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("RandomWeight", Codec.DOUBLE),
            (settings, value) -> settings.randomWeight = value,
            settings -> settings.randomWeight
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MutationChance", Codec.DOUBLE),
            (settings, value) -> settings.mutationChance = value,
            settings -> settings.mutationChance
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
        .<AttachmentInheritanceSettings>append(
            new KeyedCodec<>("AttachmentInheritance", ATTACHMENT_INHERITANCE_CODEC),
            (settings, value) -> settings.attachmentInheritance =
                    value == null ? new AttachmentInheritanceSettings() : value,
            settings -> settings.attachmentInheritance
        )
        .add()
        .build();

    private static final BuilderCodec<PassiveBreedingSettings> PASSIVE_BREEDING_CODEC = BuilderCodec.builder(
            PassiveBreedingSettings.class,
            PassiveBreedingSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value != null && value,
            settings -> settings.enabled
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("SweepIntervalSeconds", Codec.INTEGER),
            (settings, value) -> settings.sweepIntervalSeconds = value == null ? 30 : value,
            settings -> settings.getSweepIntervalSeconds()
        )
        .add()
        .<String>append(
            new KeyedCodec<>("Basis", Codec.STRING),
            (settings, value) -> settings.timerBasis = TimerBasis.fromConfigValue(value),
            settings -> settings.getTimerBasis().toConfigValue()
        )
        .add()
        .build();

    private static final BuilderCodec<TimingSettings> TIMING_CODEC = BuilderCodec.builder(
            TimingSettings.class,
            TimingSettings::new
    )
        .<String>append(
            new KeyedCodec<>("Basis", Codec.STRING),
            (settings, value) -> settings.timerBasis = TimerBasis.fromConfigValue(value),
            settings -> settings.getTimerBasis().toConfigValue()
        )
        .add()
        .build();

    private static final BuilderCodec<RoleFamily> ROLE_FAMILY_CODEC = BuilderCodec.builder(
            RoleFamily.class,
            RoleFamily::new
    )
        .<String>append(
            new KeyedCodec<>("AdultRoleId", Codec.STRING),
            (family, value) -> family.adultRoleId = value,
            family -> family.adultRoleId
        )
        .add()
        .<String>append(
            new KeyedCodec<>("BabyRoleId", Codec.STRING),
            (family, value) -> family.babyRoleId = value,
            family -> family.babyRoleId
        )
        .add()
        .<String>append(
            new KeyedCodec<>("AdolescentRoleId", Codec.STRING),
            (family, value) -> family.adolescentRoleId = value,
            family -> family.adolescentRoleId
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("TimeToFullGrownSeconds", Codec.INTEGER),
            (family, value) -> family.timeToFullGrownSeconds = value,
            family -> family.timeToFullGrownSeconds
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("BabyStartScale", Codec.DOUBLE),
            (family, value) -> family.babyStartScale = value,
            family -> family.babyStartScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("AdolescentStartScale", Codec.DOUBLE),
            (family, value) -> family.adolescentStartScale = value,
            family -> family.adolescentStartScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("AdultStartScale", Codec.DOUBLE),
            (family, value) -> family.adultStartScale = value,
            family -> family.adultStartScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("AdolescentSwitchScale", Codec.DOUBLE),
            (family, value) -> family.adolescentSwitchScale = value,
            family -> family.adolescentSwitchScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("AdultSwitchScale", Codec.DOUBLE),
            (family, value) -> family.adultSwitchScale = value,
            family -> family.adultSwitchScale
        )
        .add()
        .build();

    private static final ArrayCodec<RoleFamily> ROLE_FAMILY_ARRAY_CODEC =
            new ArrayCodec<>(ROLE_FAMILY_CODEC, RoleFamily[]::new);

    private static final BuilderCodec<OffspringLifecycleSettings> OFFSPRING_LIFECYCLE_CODEC = BuilderCodec.builder(
            OffspringLifecycleSettings.class,
            OffspringLifecycleSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value == null || value,
            settings -> settings.enabled
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("DefaultTimeToFullGrownSeconds", Codec.INTEGER),
            (settings, value) -> settings.defaultTimeToFullGrownSeconds = value,
            settings -> settings.defaultTimeToFullGrownSeconds
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultBabyStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultBabyStartScale = value,
            settings -> settings.defaultBabyStartScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdolescentStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdolescentStartScale = value,
            settings -> settings.defaultAdolescentStartScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdultStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdultStartScale = value,
            settings -> settings.defaultAdultStartScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdolescentSwitchScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdolescentSwitchScale = value,
            settings -> settings.defaultAdolescentSwitchScale
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdultSwitchScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdultSwitchScale = value,
            settings -> settings.defaultAdultSwitchScale
        )
        .add()
        .<RoleFamily[]>append(
            new KeyedCodec<>("Families", ROLE_FAMILY_ARRAY_CODEC),
            (settings, value) -> settings.families = value == null ? EMPTY_ROLE_FAMILIES : value,
            settings -> settings.families
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
        .<PassiveBreedingSettings>append(
            new KeyedCodec<>("PassiveBreeding", PASSIVE_BREEDING_CODEC),
            (asset, value) -> asset.passiveBreeding = value == null ? new PassiveBreedingSettings() : value,
            asset -> asset.passiveBreeding
        )
        .add()
        .<TimingSettings>append(
            new KeyedCodec<>("Timing", TIMING_CODEC),
            (asset, value) -> asset.timing = value == null ? new TimingSettings() : value,
            asset -> asset.timing
        )
        .add()
        .<InheritanceSettings>append(
            new KeyedCodec<>("Inheritance", INHERITANCE_CODEC),
            (asset, value) -> asset.inheritance = value == null ? new InheritanceSettings() : value,
            asset -> asset.inheritance
        )
        .add()
        .<OffspringLifecycleSettings>append(
            new KeyedCodec<>("OffspringLifecycle", OFFSPRING_LIFECYCLE_CODEC),
            (asset, value) -> asset.offspringLifecycle = value == null
                    ? new OffspringLifecycleSettings()
                    : value,
            asset -> asset.offspringLifecycle
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
    private PassiveBreedingSettings passiveBreeding = new PassiveBreedingSettings();
    private TimingSettings timing = new TimingSettings();
    private InheritanceSettings inheritance = new InheritanceSettings();
    private OffspringLifecycleSettings offspringLifecycle = new OffspringLifecycleSettings();

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
        return cache.get(normalizeRoleCacheKey(roleId));
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
            if (candidateRoles != null && candidateRoles.length > 0) {
                for (String roleId : candidateRoles) {
                    registerRoleCacheEntry(cache, candidate, roleId);
                }
            }
            OffspringLifecycleSettings lifecycle = candidate.getOffspringLifecycle();
            for (RoleFamily family : lifecycle.getFamilies()) {
                if (family == null) {
                    continue;
                }
                registerRoleCacheEntry(cache, candidate, family.getAdultRoleId());
                registerRoleCacheEntry(cache, candidate, family.getBabyRoleId());
                registerRoleCacheEntry(cache, candidate, family.getAdolescentRoleId());
            }
        }
        return cache;
    }

    private static void registerRoleCacheEntry(@Nonnull Map<String, TwBreedingConfig> cache,
                                               @Nullable TwBreedingConfig candidate,
                                               @Nullable String roleId) {
        if (candidate == null || roleId == null || roleId.isBlank()) {
            return;
        }
        String normalizedRole = normalizeRoleCacheKey(roleId);
        TwBreedingConfig existing = cache.get(normalizedRole);
        if (shouldReplaceCandidate(candidate, existing)) {
            cache.put(normalizedRole, candidate);
        }
    }

    private static String normalizeRoleCacheKey(@Nonnull String roleId) {
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            return normalized.substring(separator + 1);
        }
        return normalized;
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

    public PassiveBreedingSettings getPassiveBreeding() {
        return passiveBreeding == null ? new PassiveBreedingSettings() : passiveBreeding;
    }

    public TimingSettings getTiming() {
        return timing == null ? new TimingSettings() : timing;
    }

    public InheritanceSettings getInheritance() {
        return inheritance == null ? new InheritanceSettings() : inheritance;
    }

    public OffspringLifecycleSettings getOffspringLifecycle() {
        return offspringLifecycle == null ? new OffspringLifecycleSettings() : offspringLifecycle;
    }

    @Nullable
    public RoleFamily resolveLifecycleFamilyForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return getOffspringLifecycle().resolveFamilyForRole(roleId);
    }

    /** Tunable values for breeding-specific happiness gating. */
    public static final class HappinessSettings {
        private double threshold = 70.0;

        public double getThreshold() {
            return threshold;
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

    /** Role-level partner matching and nearby same-type population rules. */
    public static final class PairingSettings {
        private double breedRadius = 10.0;
        private boolean requireWanderMode = true;
        private boolean requireSameOwner;
        private int maxNearbySameType;
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

        public int getMaxNearbySameType() {
            return maxNearbySameType;
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

    /** Controls passive, non-interaction breeding candidate generation. */
    public static final class PassiveBreedingSettings {
        private boolean enabled;
        private int sweepIntervalSeconds = 30;
        private TimerBasis timerBasis = TimerBasis.REAL_TIME;

        public boolean isEnabled() {
            return enabled;
        }

        public int getSweepIntervalSeconds() {
            return Math.max(1, sweepIntervalSeconds);
        }

        public TimerBasis getTimerBasis() {
            return timerBasis == null ? TimerBasis.REAL_TIME : timerBasis;
        }
    }

    /** Controls how breeding durations are mapped onto game-time timestamps. */
    public static final class TimingSettings {
        private TimerBasis timerBasis = TimerBasis.WORLD_TIME_SCALED;

        public TimerBasis getTimerBasis() {
            return timerBasis == null ? TimerBasis.WORLD_TIME_SCALED : timerBasis;
        }
    }

    /** Duration basis for breeding cooldown and lifecycle timing conversion. */
    public enum TimerBasis {
        REAL_TIME,
        WORLD_TIME_SCALED;

        public static TimerBasis fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return WORLD_TIME_SCALED;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (TimerBasis basis : values()) {
                if (basis.name().equals(normalized)) {
                    return basis;
                }
            }
            return WORLD_TIME_SCALED;
        }

        public String toConfigValue() {
            return name();
        }
    }

    /** Offspring inheritance toggles for breeding outcomes. */
    public static final class InheritanceSettings {
        private boolean inheritOwner = true;
        private boolean inheritTamed = true;
        private boolean inheritAttachments;
        private boolean inheritTraits;
        private AttachmentInheritanceSettings attachmentInheritance = new AttachmentInheritanceSettings();

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

        public AttachmentInheritanceSettings getAttachmentInheritance() {
            return attachmentInheritance == null
                    ? new AttachmentInheritanceSettings()
                    : attachmentInheritance;
        }
    }

    /** Attachment inheritance weighting and mutation settings for offspring model selection. */
    public static final class AttachmentInheritanceSettings {
        private double parentWeight = 1.0;
        private double randomWeight = 0.25;
        private double mutationChance = 0.05;

        public double getParentWeight() {
            if (!Double.isFinite(parentWeight) || parentWeight < 0.0) {
                return 1.0;
            }
            return parentWeight;
        }

        public double getRandomWeight() {
            if (!Double.isFinite(randomWeight) || randomWeight < 0.0) {
                return 0.25;
            }
            return randomWeight;
        }

        public double getMutationChance() {
            if (!Double.isFinite(mutationChance)) {
                return 0.05;
            }
            if (mutationChance < 0.0) {
                return 0.0;
            }
            if (mutationChance > 1.0) {
                return 1.0;
            }
            return mutationChance;
        }
    }

    /** Lifecycle role family mappings and growth defaults used for offspring progression. */
    public static final class OffspringLifecycleSettings {
        private static final int MIN_TIME_TO_FULL_GROWN_SECONDS = 1;
        private static final double MIN_SCALE = 0.05;

        private boolean enabled = true;
        private int defaultTimeToFullGrownSeconds = 420;
        private double defaultBabyStartScale = 0.55;
        private double defaultAdolescentStartScale = 0.80;
        private double defaultAdultStartScale = 0.80;
        private double defaultAdolescentSwitchScale = 1.00;
        private double defaultAdultSwitchScale = 1.00;
        private RoleFamily[] families = EMPTY_ROLE_FAMILIES;

        public boolean isEnabled() {
            return enabled;
        }

        public int getDefaultTimeToFullGrownSeconds() {
            return sanitizeTimeSeconds(defaultTimeToFullGrownSeconds, 420);
        }

        public double getDefaultBabyStartScale() {
            return sanitizeScale(defaultBabyStartScale, 0.55);
        }

        public double getDefaultAdolescentStartScale() {
            return sanitizeScale(defaultAdolescentStartScale, 0.80);
        }

        public double getDefaultAdultStartScale() {
            return sanitizeScale(defaultAdultStartScale, 0.80);
        }

        public double getDefaultAdolescentSwitchScale() {
            return sanitizeScale(defaultAdolescentSwitchScale, 1.00);
        }

        public double getDefaultAdultSwitchScale() {
            return sanitizeScale(defaultAdultSwitchScale, 1.00);
        }

        public RoleFamily[] getFamilies() {
            return families == null ? EMPTY_ROLE_FAMILIES : families;
        }

        @Nullable
        public RoleFamily resolveFamilyForRole(@Nullable String roleId) {
            if (roleId == null || roleId.isBlank()) {
                return null;
            }
            for (RoleFamily family : getFamilies()) {
                if (family != null && family.matchesRole(roleId)) {
                    return family;
                }
            }
            return null;
        }

        public int resolveTimeToFullGrownSeconds(@Nullable RoleFamily family) {
            if (family != null && family.getTimeToFullGrownSeconds() != null) {
                return sanitizeTimeSeconds(family.getTimeToFullGrownSeconds(), getDefaultTimeToFullGrownSeconds());
            }
            return getDefaultTimeToFullGrownSeconds();
        }

        public double resolveBabyStartScale(@Nullable RoleFamily family) {
            if (family != null) {
                return sanitizeScale(family.getBabyStartScale(), getDefaultBabyStartScale());
            }
            return getDefaultBabyStartScale();
        }

        public double resolveAdolescentStartScale(@Nullable RoleFamily family) {
            if (family != null) {
                return sanitizeScale(family.getAdolescentStartScale(), getDefaultAdolescentStartScale());
            }
            return getDefaultAdolescentStartScale();
        }

        public double resolveAdultStartScale(@Nullable RoleFamily family) {
            if (family != null) {
                return sanitizeScale(family.getAdultStartScale(), getDefaultAdultStartScale());
            }
            return getDefaultAdultStartScale();
        }

        public double resolveAdolescentSwitchScale(@Nullable RoleFamily family) {
            if (family != null) {
                return sanitizeScale(family.getAdolescentSwitchScale(), getDefaultAdolescentSwitchScale());
            }
            return getDefaultAdolescentSwitchScale();
        }

        public double resolveAdultSwitchScale(@Nullable RoleFamily family) {
            if (family != null) {
                return sanitizeScale(family.getAdultSwitchScale(), getDefaultAdultSwitchScale());
            }
            return getDefaultAdultSwitchScale();
        }

        private static int sanitizeTimeSeconds(@Nullable Integer value, int fallback) {
            int safeFallback = Math.max(MIN_TIME_TO_FULL_GROWN_SECONDS, fallback);
            if (value == null) {
                return safeFallback;
            }
            return Math.max(MIN_TIME_TO_FULL_GROWN_SECONDS, value);
        }

        private static double sanitizeScale(@Nullable Double value, double fallback) {
            double safeFallback = sanitizeScale(fallback, 1.0);
            if (value == null) {
                return safeFallback;
            }
            return sanitizeScale(value.doubleValue(), safeFallback);
        }

        private static double sanitizeScale(double value, double fallback) {
            if (!Double.isFinite(value) || value <= 0.0) {
                return fallback;
            }
            return Math.max(MIN_SCALE, value);
        }
    }

    /** Explicit role-family mapping for baby/adolescent/adult lifecycle transitions. */
    public static final class RoleFamily {
        private String adultRoleId;
        private String babyRoleId;
        private String adolescentRoleId;
        private Integer timeToFullGrownSeconds;
        private Double babyStartScale;
        private Double adolescentStartScale;
        private Double adultStartScale;
        private Double adolescentSwitchScale;
        private Double adultSwitchScale;

        @Nullable
        public String getAdultRoleId() {
            return adultRoleId;
        }

        @Nullable
        public String getBabyRoleId() {
            return babyRoleId;
        }

        @Nullable
        public String getAdolescentRoleId() {
            return adolescentRoleId;
        }

        @Nullable
        public Integer getTimeToFullGrownSeconds() {
            return timeToFullGrownSeconds;
        }

        @Nullable
        public Double getBabyStartScale() {
            return babyStartScale;
        }

        @Nullable
        public Double getAdolescentStartScale() {
            return adolescentStartScale;
        }

        @Nullable
        public Double getAdultStartScale() {
            return adultStartScale;
        }

        @Nullable
        public Double getAdolescentSwitchScale() {
            return adolescentSwitchScale;
        }

        @Nullable
        public Double getAdultSwitchScale() {
            return adultSwitchScale;
        }

        public boolean matchesRole(@Nullable String roleId) {
            if (roleId == null || roleId.isBlank()) {
                return false;
            }
            return matchesRoleId(adultRoleId, roleId)
                    || matchesRoleId(babyRoleId, roleId)
                    || matchesRoleId(adolescentRoleId, roleId);
        }

        private static boolean matchesRoleId(@Nullable String candidate, @Nullable String roleId) {
            if (candidate == null || candidate.isBlank() || roleId == null || roleId.isBlank()) {
                return false;
            }
            String normalizedCandidate = normalizeRoleId(candidate);
            String normalizedRoleId = normalizeRoleId(roleId);
            return normalizedCandidate.equals(normalizedRoleId);
        }

        private static String normalizeRoleId(@Nonnull String roleId) {
            String normalized = roleId.trim().toLowerCase(Locale.ROOT);
            int separator = normalized.lastIndexOf(':');
            if (separator >= 0 && separator < normalized.length() - 1) {
                return normalized.substring(separator + 1);
            }
            return normalized;
        }
    }
}
