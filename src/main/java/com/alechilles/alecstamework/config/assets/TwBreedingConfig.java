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
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed breeding configuration for role-scoped companion breeding rules.
 * Stored under Server/Tamework/Breeding.
 */
public final class TwBreedingConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwBreedingConfig>>,
        TwParentFallbackAsset<TwBreedingConfig> {
    private static final int SECONDS_PER_MINUTE = 60;
    private static final AdultRoleChoice[] EMPTY_ADULT_ROLE_CHOICES = new AdultRoleChoice[0];
    private static final RoleFamily[] EMPTY_ROLE_FAMILIES = new RoleFamily[0];
    private static final RoleMaxNearbySameTypeOverride[] EMPTY_ROLE_MAX_NEARBY_OVERRIDES =
            new RoleMaxNearbySameTypeOverride[0];
    private static final Map<String, RoleOverrideSettings> EMPTY_ROLE_OVERRIDES = Collections.emptyMap();

    private static final BuilderCodec<HappinessSettings> HAPPINESS_CODEC = BuilderCodec.builder(
            HappinessSettings.class,
            HappinessSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("Threshold", Codec.DOUBLE),
            (settings, value) -> settings.threshold = value,
            settings -> settings.threshold
        )
        .documentation("Threshold value used by this rule.")
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
        .documentation("Requires the target NPC to be tamed.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireAdult", Codec.BOOLEAN),
            (settings, value) -> settings.requireAdult = value,
            settings -> settings.requireAdult
        )
        .documentation("Requires adult to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireNotInCombat", Codec.BOOLEAN),
            (settings, value) -> settings.requireNotInCombat = value,
            settings -> settings.requireNotInCombat
        )
        .documentation("Requires not in combat to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireNotSleeping", Codec.BOOLEAN),
            (settings, value) -> settings.requireNotSleeping = value,
            settings -> settings.requireNotSleeping
        )
        .documentation("Requires not sleeping to be true.")
        .add()
        .build();

    private static final BuilderCodec<RoleMaxNearbySameTypeOverride> ROLE_MAX_NEARBY_OVERRIDE_CODEC =
            BuilderCodec.builder(
                RoleMaxNearbySameTypeOverride.class,
                RoleMaxNearbySameTypeOverride::new
            )
                .<String>append(
                    new KeyedCodec<>("RoleId", Codec.STRING),
                    (override, value) -> override.roleId = value,
                    override -> override.roleId
                )
                .documentation("Role ID this setting targets.")
                .add()
                .<Integer>append(
                    new KeyedCodec<>("MaxNearbySameType", Codec.INTEGER),
                    (override, value) -> override.maxNearbySameType = value,
                    override -> override.maxNearbySameType
                )
                .documentation("Maximum nearby NPCs of the same type allowed before breeding is blocked.")
                .add()
                .build();

    private static final ArrayCodec<RoleMaxNearbySameTypeOverride> ROLE_MAX_NEARBY_OVERRIDE_ARRAY_CODEC =
            new ArrayCodec<>(ROLE_MAX_NEARBY_OVERRIDE_CODEC, RoleMaxNearbySameTypeOverride[]::new);

    private static final BuilderCodec<PairingSettings> PAIRING_CODEC = BuilderCodec.builder(
            PairingSettings.class,
            PairingSettings::new
    )
        .<Double>append(
            new KeyedCodec<>("BreedRadius", Codec.DOUBLE),
            (settings, value) -> settings.breedRadius = value,
            settings -> settings.breedRadius
        )
        .documentation("Maximum distance between breeding candidates, in blocks.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireWanderMode", Codec.BOOLEAN),
            (settings, value) -> settings.requireWanderMode = value,
            settings -> settings.requireWanderMode
        )
        .documentation("Requires wander mode to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireSameOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireSameOwner = value,
            settings -> settings.requireSameOwner
        )
        .documentation("Requires breeding partners to share the same owner.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxNearbySameType", Codec.INTEGER),
            (settings, value) -> settings.maxNearbySameType = value,
            settings -> settings.maxNearbySameType
        )
        .documentation("Maximum nearby NPCs of the same type allowed before breeding is blocked.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireSameRoleId", Codec.BOOLEAN),
            (settings, value) -> settings.requireSameRoleId = value,
            settings -> settings.requireSameRoleId
        )
        .documentation("Deprecated legacy compatibility toggle. Prefer RoleCompatibility. If RoleCompatibility is omitted, true maps to SameRole and false maps to Any.")
        .add()
        .<String>append(
            new KeyedCodec<>("RoleCompatibility", Codec.STRING),
            (settings, value) -> settings.roleCompatibility = RoleCompatibility.fromConfigValue(value, null),
            settings -> settings.roleCompatibility == null ? null : settings.roleCompatibility.toConfigValue()
        )
        .documentation("Partner role compatibility mode: SameRole, SameLifecycleFamily, DifferentFamilyRole, or Any. Inheritance: missing nested key inherits parent value.")
        .add()
        .<RoleMaxNearbySameTypeOverride[]>append(
            new KeyedCodec<>("RoleMaxNearbySameType", ROLE_MAX_NEARBY_OVERRIDE_ARRAY_CODEC),
            (settings, value) -> settings.roleMaxNearbySameType = value == null
                    ? EMPTY_ROLE_MAX_NEARBY_OVERRIDES
                    : value,
            settings -> settings.roleMaxNearbySameType
        )
        .documentation("Optional per-role overrides for nearby same-type breeding limits.")
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
        .documentation("Base breeding cooldown in seconds.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("BaseCooldownMinutes", Codec.INTEGER),
            (settings, value) -> {
                if (value != null) {
                    settings.baseCooldownSeconds = minutesToSeconds(value, settings.baseCooldownSeconds);
                }
            },
            settings -> null
        )
        .documentation("Legacy minute-based cooldown; converted to seconds when provided.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MinDelaySeconds", Codec.INTEGER),
            (settings, value) -> settings.minDelaySeconds = value,
            settings -> settings.minDelaySeconds
        )
        .documentation("Minimum random delay in seconds before breeding resolves.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxDelaySeconds", Codec.INTEGER),
            (settings, value) -> settings.maxDelaySeconds = value,
            settings -> settings.maxDelaySeconds
        )
        .documentation("Maximum random delay in seconds before breeding resolves.")
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
        .documentation("Relative weight for inheriting attachment traits from parents.")
        .add()
        .<Double>append(
            new KeyedCodec<>("RandomWeight", Codec.DOUBLE),
            (settings, value) -> settings.randomWeight = value,
            settings -> settings.randomWeight
        )
        .documentation("Relative weight for selecting random attachment traits.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MutationChance", Codec.DOUBLE),
            (settings, value) -> settings.mutationChance = value,
            settings -> settings.mutationChance
        )
        .documentation("Chance for mutation when generating inherited data.")
        .add()
        .build();

    private static final BuilderCodec<GenderSettings> GENDER_CODEC = BuilderCodec.builder(
            GenderSettings.class,
            GenderSettings::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value != null && value,
            settings -> settings.enabled
        )
        .documentation("Turns binary gender assignment and gender-aware breeding checks on or off.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireDifferentGender", Codec.BOOLEAN),
            (settings, value) -> settings.requireDifferentGender = value == null || value,
            settings -> settings.requireDifferentGender
        )
        .documentation("When gender is enabled, requires breeding partners to have different genders.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MaleWeight", Codec.DOUBLE),
            (settings, value) -> settings.maleWeight = value,
            settings -> settings.maleWeight
        )
        .documentation("Relative weighted chance for random male assignment.")
        .add()
        .<Double>append(
            new KeyedCodec<>("FemaleWeight", Codec.DOUBLE),
            (settings, value) -> settings.femaleWeight = value,
            settings -> settings.femaleWeight
        )
        .documentation("Relative weighted chance for random female assignment.")
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
        .documentation("If true, offspring inherits owner assignment from parents.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritTamed", Codec.BOOLEAN),
            (settings, value) -> settings.inheritTamed = value,
            settings -> settings.inheritTamed
        )
        .documentation("If true, offspring inherits tamed state from parents.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritAttachments", Codec.BOOLEAN),
            (settings, value) -> settings.inheritAttachments = value,
            settings -> settings.inheritAttachments
        )
        .documentation("If true, offspring can inherit attachment modifiers.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritTraits", Codec.BOOLEAN),
            (settings, value) -> settings.inheritTraits = value,
            settings -> settings.inheritTraits
        )
        .documentation("If true, offspring can inherit trait values.")
        .add()
        .<AttachmentInheritanceSettings>append(
            new KeyedCodec<>("AttachmentInheritance", ATTACHMENT_INHERITANCE_CODEC),
            (settings, value) -> settings.attachmentInheritance =
                    value == null ? new AttachmentInheritanceSettings() : value,
            settings -> settings.attachmentInheritance
        )
        .documentation("Attachment inheritance weighting and mutation settings.")
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
        .documentation("Turns this section on or off.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("SweepIntervalSeconds", Codec.INTEGER),
            (settings, value) -> settings.sweepIntervalSeconds = value == null ? 30 : value,
            settings -> settings.getSweepIntervalSeconds()
        )
        .documentation("How often this system runs, in seconds.")
        .add()
        .<String>append(
            new KeyedCodec<>("Basis", Codec.STRING),
            (settings, value) -> settings.timerBasis = TimerBasis.fromConfigValue(value),
            settings -> settings.getTimerBasis().toConfigValue()
        )
        .documentation("Chooses which time basis this system uses.")
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
        .documentation("Chooses which time basis this system uses.")
        .add()
        .build();

    private static final BuilderCodec<AdultRoleChoice> ADULT_ROLE_CHOICE_CODEC = BuilderCodec.builder(
            AdultRoleChoice.class,
            AdultRoleChoice::new
    )
        .<String>append(
            new KeyedCodec<>("RoleId", Codec.STRING),
            (choice, value) -> choice.roleId = value,
            choice -> choice.roleId
        )
        .documentation("Adult role ID that offspring can grow into.")
        .add()
        .<String>append(
            new KeyedCodec<>("Gender", Codec.STRING),
            (choice, value) -> choice.gender = Gender.fromConfigValue(value),
            choice -> choice.gender == null ? null : choice.gender.toConfigValue()
        )
        .documentation("Optional binary gender for this adult role: Male or Female.")
        .add()
        .<Double>append(
            new KeyedCodec<>("Weight", Codec.DOUBLE),
            (choice, value) -> choice.weight = value,
            choice -> choice.weight
        )
        .documentation("Relative weighted chance for this adult role. Non-positive values are ignored.")
        .add()
        .build();

    private static final ArrayCodec<AdultRoleChoice> ADULT_ROLE_CHOICE_ARRAY_CODEC =
            new ArrayCodec<>(ADULT_ROLE_CHOICE_CODEC, AdultRoleChoice[]::new);

    private static final BuilderCodec<RoleFamily> ROLE_FAMILY_CODEC = BuilderCodec.builder(
            RoleFamily.class,
            RoleFamily::new
    )
        .<String>append(
            new KeyedCodec<>("AdultRoleId", Codec.STRING),
            (family, value) -> family.adultRoleId = value,
            family -> family.adultRoleId
        )
        .documentation("Legacy single adult role ID assigned when offspring reaches adult stage. Ignored for weighted selection when AdultRoles is present.")
        .add()
        .<AdultRoleChoice[]>append(
            new KeyedCodec<>("AdultRoles", ADULT_ROLE_CHOICE_ARRAY_CODEC),
            (family, value) -> family.adultRoles = value == null ? EMPTY_ADULT_ROLE_CHOICES : value,
            family -> family.adultRoles
        )
        .documentation("Weighted adult roles that this family can breed as and grow into. Explicit array replaces the parent family entry.")
        .add()
        .<String>append(
            new KeyedCodec<>("BabyRoleId", Codec.STRING),
            (family, value) -> family.babyRoleId = value,
            family -> family.babyRoleId
        )
        .documentation("Role ID assigned when offspring is spawned as a baby.")
        .add()
        .<String>append(
            new KeyedCodec<>("AdolescentRoleId", Codec.STRING),
            (family, value) -> family.adolescentRoleId = value,
            family -> family.adolescentRoleId
        )
        .documentation("Role ID assigned during the adolescent growth stage.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("TimeToFullGrownSeconds", Codec.INTEGER),
            (family, value) -> family.timeToFullGrownSeconds = value,
            family -> family.timeToFullGrownSeconds
        )
        .documentation("Time in seconds to reach full-grown stage.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("TimeToFullGrownMinutes", Codec.INTEGER),
            (family, value) -> {
                if (value != null) {
                    family.timeToFullGrownSeconds = minutesToSeconds(value, family.timeToFullGrownSeconds);
                }
            },
            family -> null
        )
        .documentation("Legacy minute-based growth time; converted to seconds when provided.")
        .add()
        .<Double>append(
            new KeyedCodec<>("BabyStartScale", Codec.DOUBLE),
            (family, value) -> family.babyStartScale = value,
            family -> family.babyStartScale
        )
        .documentation("Initial visual scale used for baby stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdolescentStartScale", Codec.DOUBLE),
            (family, value) -> family.adolescentStartScale = value,
            family -> family.adolescentStartScale
        )
        .documentation("Initial visual scale used for adolescent stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdultStartScale", Codec.DOUBLE),
            (family, value) -> family.adultStartScale = value,
            family -> family.adultStartScale
        )
        .documentation("Initial visual scale used for adult stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdolescentSwitchScale", Codec.DOUBLE),
            (family, value) -> family.adolescentSwitchScale = value,
            family -> family.adolescentSwitchScale
        )
        .documentation("Scale threshold that switches from baby to adolescent stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdultSwitchScale", Codec.DOUBLE),
            (family, value) -> family.adultSwitchScale = value,
            family -> family.adultSwitchScale
        )
        .documentation("Scale threshold that switches from adolescent to adult stage.")
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
        .documentation("Turns this section on or off.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("DefaultTimeToFullGrownSeconds", Codec.INTEGER),
            (settings, value) -> settings.defaultTimeToFullGrownSeconds = value,
            settings -> null
        )
        .documentation("Default full-growth time in seconds when family override is absent.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("DefaultTimeToFullGrownMinutes", Codec.INTEGER),
            (settings, value) -> {
                if (value != null) {
                    settings.defaultTimeToFullGrownSeconds =
                            minutesToSeconds(value, settings.defaultTimeToFullGrownSeconds);
                }
            },
            settings -> null
        )
        .documentation("Legacy default growth time in minutes; converted to seconds when provided.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("TimeToFullGrownSeconds", Codec.INTEGER),
            (settings, value) -> settings.defaultTimeToFullGrownSeconds = value,
            settings -> settings.defaultTimeToFullGrownSeconds
        )
        .documentation("Time in seconds to reach full-grown stage.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("TimeToFullGrownMinutes", Codec.INTEGER),
            (settings, value) -> {
                if (value != null) {
                    settings.defaultTimeToFullGrownSeconds =
                            minutesToSeconds(value, settings.defaultTimeToFullGrownSeconds);
                }
            },
            settings -> null
        )
        .documentation("Legacy minute-based growth time; converted to seconds when provided.")
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultBabyStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultBabyStartScale = value,
            settings -> null
        )
        .documentation("Default baby scale when family override is absent.")
        .add()
        .<Double>append(
            new KeyedCodec<>("BabyStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultBabyStartScale = value,
            settings -> settings.defaultBabyStartScale
        )
        .documentation("Initial visual scale used for baby stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdolescentStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdolescentStartScale = value,
            settings -> null
        )
        .documentation("Default adolescent scale when family override is absent.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdolescentStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdolescentStartScale = value,
            settings -> settings.defaultAdolescentStartScale
        )
        .documentation("Initial visual scale used for adolescent stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdultStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdultStartScale = value,
            settings -> null
        )
        .documentation("Default adult scale when family override is absent.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdultStartScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdultStartScale = value,
            settings -> settings.defaultAdultStartScale
        )
        .documentation("Initial visual scale used for adult stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdolescentSwitchScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdolescentSwitchScale = value,
            settings -> null
        )
        .documentation("Default baby-to-adolescent switch scale when family override is absent.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdolescentSwitchScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdolescentSwitchScale = value,
            settings -> settings.defaultAdolescentSwitchScale
        )
        .documentation("Scale threshold that switches from baby to adolescent stage.")
        .add()
        .<Double>append(
            new KeyedCodec<>("DefaultAdultSwitchScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdultSwitchScale = value,
            settings -> null
        )
        .documentation("Default adolescent-to-adult switch scale when family override is absent.")
        .add()
        .<Double>append(
            new KeyedCodec<>("AdultSwitchScale", Codec.DOUBLE),
            (settings, value) -> settings.defaultAdultSwitchScale = value,
            settings -> settings.defaultAdultSwitchScale
        )
        .documentation("Scale threshold that switches from adolescent to adult stage.")
        .add()
        .<RoleFamily[]>append(
            new KeyedCodec<>("Families", ROLE_FAMILY_ARRAY_CODEC),
            (settings, value) -> settings.families = value == null ? EMPTY_ROLE_FAMILIES : value,
            settings -> settings.families
        )
        .documentation("Role-family entries used by this configuration.")
        .add()
        .build();

    private static final BuilderCodec<HappinessSettingsOverride> HAPPINESS_OVERRIDE_CODEC = BuilderCodec.builder(
            HappinessSettingsOverride.class,
            HappinessSettingsOverride::new
    )
        .<Double>append(
            new KeyedCodec<>("Threshold", Codec.DOUBLE),
            (settings, value) -> settings.threshold = value,
            settings -> settings.threshold
        )
        .documentation("Threshold value used by this rule.")
        .add()
        .build();

    private static final BuilderCodec<EligibilitySettingsOverride> ELIGIBILITY_OVERRIDE_CODEC = BuilderCodec.builder(
            EligibilitySettingsOverride.class,
            EligibilitySettingsOverride::new
    )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (settings, value) -> settings.requireTamed = value,
            settings -> settings.requireTamed
        )
        .documentation("Requires the target NPC to be tamed.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireAdult", Codec.BOOLEAN),
            (settings, value) -> settings.requireAdult = value,
            settings -> settings.requireAdult
        )
        .documentation("Requires adult to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireNotInCombat", Codec.BOOLEAN),
            (settings, value) -> settings.requireNotInCombat = value,
            settings -> settings.requireNotInCombat
        )
        .documentation("Requires not in combat to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireNotSleeping", Codec.BOOLEAN),
            (settings, value) -> settings.requireNotSleeping = value,
            settings -> settings.requireNotSleeping
        )
        .documentation("Requires not sleeping to be true.")
        .add()
        .build();

    private static final BuilderCodec<PairingSettingsOverride> PAIRING_OVERRIDE_CODEC = BuilderCodec.builder(
            PairingSettingsOverride.class,
            PairingSettingsOverride::new
    )
        .<Double>append(
            new KeyedCodec<>("BreedRadius", Codec.DOUBLE),
            (settings, value) -> settings.breedRadius = value,
            settings -> settings.breedRadius
        )
        .documentation("Maximum distance between breeding candidates, in blocks.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireWanderMode", Codec.BOOLEAN),
            (settings, value) -> settings.requireWanderMode = value,
            settings -> settings.requireWanderMode
        )
        .documentation("Requires wander mode to be true.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireSameOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireSameOwner = value,
            settings -> settings.requireSameOwner
        )
        .documentation("Requires breeding partners to share the same owner.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxNearbySameType", Codec.INTEGER),
            (settings, value) -> settings.maxNearbySameType = value,
            settings -> settings.maxNearbySameType
        )
        .documentation("Maximum nearby NPCs of the same type allowed before breeding is blocked.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireSameRoleId", Codec.BOOLEAN),
            (settings, value) -> settings.requireSameRoleId = value,
            settings -> settings.requireSameRoleId
        )
        .documentation("Deprecated legacy compatibility toggle. Prefer RoleCompatibility. If RoleCompatibility is omitted, true maps to SameRole and false maps to Any.")
        .add()
        .<String>append(
            new KeyedCodec<>("RoleCompatibility", Codec.STRING),
            (settings, value) -> settings.roleCompatibility = RoleCompatibility.fromConfigValue(value, null),
            settings -> settings.roleCompatibility == null ? null : settings.roleCompatibility.toConfigValue()
        )
        .documentation("Partner role compatibility mode: SameRole, SameLifecycleFamily, DifferentFamilyRole, or Any.")
        .add()
        .<RoleMaxNearbySameTypeOverride[]>append(
            new KeyedCodec<>("RoleMaxNearbySameType", ROLE_MAX_NEARBY_OVERRIDE_ARRAY_CODEC),
            (settings, value) -> settings.roleMaxNearbySameType = value,
            settings -> settings.roleMaxNearbySameType
        )
        .documentation("Optional per-role overrides for nearby same-type breeding limits.")
        .add()
        .build();

    private static final BuilderCodec<CooldownSettingsOverride> COOLDOWN_OVERRIDE_CODEC = BuilderCodec.builder(
            CooldownSettingsOverride.class,
            CooldownSettingsOverride::new
    )
        .<Integer>append(
            new KeyedCodec<>("BaseCooldownSeconds", Codec.INTEGER),
            (settings, value) -> settings.baseCooldownSeconds = value,
            settings -> settings.baseCooldownSeconds
        )
        .documentation("Base breeding cooldown in seconds.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("BaseCooldownMinutes", Codec.INTEGER),
            (settings, value) -> {
                if (value != null) {
                    settings.baseCooldownSeconds = minutesToSeconds(value, settings.baseCooldownSeconds);
                }
            },
            settings -> null
        )
        .documentation("Legacy minute-based cooldown; converted to seconds when provided.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MinDelaySeconds", Codec.INTEGER),
            (settings, value) -> settings.minDelaySeconds = value,
            settings -> settings.minDelaySeconds
        )
        .documentation("Minimum random delay in seconds before breeding resolves.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxDelaySeconds", Codec.INTEGER),
            (settings, value) -> settings.maxDelaySeconds = value,
            settings -> settings.maxDelaySeconds
        )
        .documentation("Maximum random delay in seconds before breeding resolves.")
        .add()
        .build();

    private static final BuilderCodec<PassiveBreedingSettingsOverride> PASSIVE_BREEDING_OVERRIDE_CODEC =
            BuilderCodec.builder(
                PassiveBreedingSettingsOverride.class,
                PassiveBreedingSettingsOverride::new
            )
                .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value,
                    settings -> settings.enabled
                )
                .documentation("Turns this section on or off.")
                .add()
                .<Integer>append(
                    new KeyedCodec<>("SweepIntervalSeconds", Codec.INTEGER),
                    (settings, value) -> settings.sweepIntervalSeconds = value,
                    settings -> settings.sweepIntervalSeconds
                )
                .documentation("How often this system runs, in seconds.")
                .add()
                .<String>append(
                    new KeyedCodec<>("Basis", Codec.STRING),
                    (settings, value) -> settings.timerBasis = value == null
                            ? null
                            : TimerBasis.fromConfigValue(value),
                    settings -> settings.timerBasis == null
                            ? null
                            : settings.timerBasis.toConfigValue()
                )
                .documentation("Chooses which time basis this system uses.")
                .add()
                .build();

    private static final BuilderCodec<TimingSettingsOverride> TIMING_OVERRIDE_CODEC = BuilderCodec.builder(
            TimingSettingsOverride.class,
            TimingSettingsOverride::new
    )
        .<String>append(
            new KeyedCodec<>("Basis", Codec.STRING),
            (settings, value) -> settings.timerBasis = value == null
                    ? null
                    : TimerBasis.fromConfigValue(value),
            settings -> settings.timerBasis == null ? null : settings.timerBasis.toConfigValue()
        )
        .documentation("Chooses which time basis this system uses.")
        .add()
        .build();

    private static final BuilderCodec<GenderSettingsOverride> GENDER_OVERRIDE_CODEC = BuilderCodec.builder(
            GenderSettingsOverride.class,
            GenderSettingsOverride::new
    )
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (settings, value) -> settings.enabled = value,
            settings -> settings.enabled
        )
        .documentation("Turns binary gender assignment and gender-aware breeding checks on or off.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireDifferentGender", Codec.BOOLEAN),
            (settings, value) -> settings.requireDifferentGender = value,
            settings -> settings.requireDifferentGender
        )
        .documentation("When gender is enabled, requires breeding partners to have different genders.")
        .add()
        .<Double>append(
            new KeyedCodec<>("MaleWeight", Codec.DOUBLE),
            (settings, value) -> settings.maleWeight = value,
            settings -> settings.maleWeight
        )
        .documentation("Relative weighted chance for random male assignment.")
        .add()
        .<Double>append(
            new KeyedCodec<>("FemaleWeight", Codec.DOUBLE),
            (settings, value) -> settings.femaleWeight = value,
            settings -> settings.femaleWeight
        )
        .documentation("Relative weighted chance for random female assignment.")
        .add()
        .build();

    private static final BuilderCodec<AttachmentInheritanceSettingsOverride> ATTACHMENT_INHERITANCE_OVERRIDE_CODEC =
            BuilderCodec.builder(
                AttachmentInheritanceSettingsOverride.class,
                AttachmentInheritanceSettingsOverride::new
            )
                .<Double>append(
                    new KeyedCodec<>("ParentWeight", Codec.DOUBLE),
                    (settings, value) -> settings.parentWeight = value,
                    settings -> settings.parentWeight
                )
                .documentation("Relative weight for inheriting attachment traits from parents.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("RandomWeight", Codec.DOUBLE),
                    (settings, value) -> settings.randomWeight = value,
                    settings -> settings.randomWeight
                )
                .documentation("Relative weight for selecting random attachment traits.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("MutationChance", Codec.DOUBLE),
                    (settings, value) -> settings.mutationChance = value,
                    settings -> settings.mutationChance
                )
                .documentation("Chance for mutation when generating inherited data.")
                .add()
                .build();

    private static final BuilderCodec<InheritanceSettingsOverride> INHERITANCE_OVERRIDE_CODEC = BuilderCodec.builder(
            InheritanceSettingsOverride.class,
            InheritanceSettingsOverride::new
    )
        .<Boolean>append(
            new KeyedCodec<>("InheritOwner", Codec.BOOLEAN),
            (settings, value) -> settings.inheritOwner = value,
            settings -> settings.inheritOwner
        )
        .documentation("If true, offspring inherits owner assignment from parents.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritTamed", Codec.BOOLEAN),
            (settings, value) -> settings.inheritTamed = value,
            settings -> settings.inheritTamed
        )
        .documentation("If true, offspring inherits tamed state from parents.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritAttachments", Codec.BOOLEAN),
            (settings, value) -> settings.inheritAttachments = value,
            settings -> settings.inheritAttachments
        )
        .documentation("If true, offspring can inherit attachment modifiers.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("InheritTraits", Codec.BOOLEAN),
            (settings, value) -> settings.inheritTraits = value,
            settings -> settings.inheritTraits
        )
        .documentation("If true, offspring can inherit trait values.")
        .add()
        .<AttachmentInheritanceSettingsOverride>append(
            new KeyedCodec<>("AttachmentInheritance", ATTACHMENT_INHERITANCE_OVERRIDE_CODEC),
            (settings, value) -> settings.attachmentInheritance = value,
            settings -> settings.attachmentInheritance
        )
        .documentation("Attachment inheritance weighting and mutation settings.")
        .add()
        .build();

    private static final BuilderCodec<OffspringLifecycleSettingsOverride> OFFSPRING_LIFECYCLE_OVERRIDE_CODEC =
            BuilderCodec.builder(
                OffspringLifecycleSettingsOverride.class,
                OffspringLifecycleSettingsOverride::new
            )
                .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value,
                    settings -> settings.enabled
                )
                .documentation("Turns this section on or off.")
                .add()
                .<Integer>append(
                    new KeyedCodec<>("DefaultTimeToFullGrownSeconds", Codec.INTEGER),
                    (settings, value) -> settings.defaultTimeToFullGrownSeconds = value,
                    settings -> null
                )
                .documentation("Default full-growth time in seconds when family override is absent.")
                .add()
                .<Integer>append(
                    new KeyedCodec<>("DefaultTimeToFullGrownMinutes", Codec.INTEGER),
                    (settings, value) -> {
                        if (value != null) {
                            settings.defaultTimeToFullGrownSeconds =
                                    minutesToSeconds(value, settings.defaultTimeToFullGrownSeconds);
                        }
                    },
                    settings -> null
                )
                .documentation("Legacy default growth time in minutes; converted to seconds when provided.")
                .add()
                .<Integer>append(
                    new KeyedCodec<>("TimeToFullGrownSeconds", Codec.INTEGER),
                    (settings, value) -> settings.defaultTimeToFullGrownSeconds = value,
                    settings -> settings.defaultTimeToFullGrownSeconds
                )
                .documentation("Time in seconds to reach full-grown stage.")
                .add()
                .<Integer>append(
                    new KeyedCodec<>("TimeToFullGrownMinutes", Codec.INTEGER),
                    (settings, value) -> {
                        if (value != null) {
                            settings.defaultTimeToFullGrownSeconds =
                                    minutesToSeconds(value, settings.defaultTimeToFullGrownSeconds);
                        }
                    },
                    settings -> null
                )
                .documentation("Legacy minute-based growth time; converted to seconds when provided.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("DefaultBabyStartScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultBabyStartScale = value,
                    settings -> null
                )
                .documentation("Default baby scale when family override is absent.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("BabyStartScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultBabyStartScale = value,
                    settings -> settings.defaultBabyStartScale
                )
                .documentation("Initial visual scale used for baby stage.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("DefaultAdolescentStartScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdolescentStartScale = value,
                    settings -> null
                )
                .documentation("Default adolescent scale when family override is absent.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("AdolescentStartScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdolescentStartScale = value,
                    settings -> settings.defaultAdolescentStartScale
                )
                .documentation("Initial visual scale used for adolescent stage.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("DefaultAdultStartScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdultStartScale = value,
                    settings -> null
                )
                .documentation("Default adult scale when family override is absent.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("AdultStartScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdultStartScale = value,
                    settings -> settings.defaultAdultStartScale
                )
                .documentation("Initial visual scale used for adult stage.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("DefaultAdolescentSwitchScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdolescentSwitchScale = value,
                    settings -> null
                )
                .documentation("Default baby-to-adolescent switch scale when family override is absent.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("AdolescentSwitchScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdolescentSwitchScale = value,
                    settings -> settings.defaultAdolescentSwitchScale
                )
                .documentation("Scale threshold that switches from baby to adolescent stage.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("DefaultAdultSwitchScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdultSwitchScale = value,
                    settings -> null
                )
                .documentation("Default adolescent-to-adult switch scale when family override is absent.")
                .add()
                .<Double>append(
                    new KeyedCodec<>("AdultSwitchScale", Codec.DOUBLE),
                    (settings, value) -> settings.defaultAdultSwitchScale = value,
                    settings -> settings.defaultAdultSwitchScale
                )
                .documentation("Scale threshold that switches from adolescent to adult stage.")
                .add()
                .<RoleFamily[]>append(
                    new KeyedCodec<>("Families", ROLE_FAMILY_ARRAY_CODEC),
                    (settings, value) -> settings.families = value,
                    settings -> settings.families
                )
                .documentation("Role-family entries used by this configuration.")
                .add()
                .build();

    private static final BuilderCodec<RoleOverrideSettings> ROLE_OVERRIDE_CODEC = BuilderCodec.builder(
            RoleOverrideSettings.class,
            RoleOverrideSettings::new
    )
        .<HappinessSettingsOverride>append(
            new KeyedCodec<>("Happiness", HAPPINESS_OVERRIDE_CODEC),
            (settings, value) -> settings.happiness = value,
            settings -> settings.happiness
        )
        .documentation("Happiness gating settings used when evaluating breeding eligibility.")
        .add()
        .<EligibilitySettingsOverride>append(
            new KeyedCodec<>("Eligibility", ELIGIBILITY_OVERRIDE_CODEC),
            (settings, value) -> settings.eligibility = value,
            settings -> settings.eligibility
        )
        .documentation("Base eligibility requirements an NPC must satisfy before breeding.")
        .add()
        .<PairingSettingsOverride>append(
            new KeyedCodec<>("Pairing", PAIRING_OVERRIDE_CODEC),
            (settings, value) -> settings.pairing = value,
            settings -> settings.pairing
        )
        .documentation("Pair matching rules used to choose compatible breeding partners.")
        .add()
        .<CooldownSettingsOverride>append(
            new KeyedCodec<>("Cooldowns", COOLDOWN_OVERRIDE_CODEC),
            (settings, value) -> settings.cooldowns = value,
            settings -> settings.cooldowns
        )
        .documentation("Cooldown and random delay settings between breeding attempts.")
        .add()
        .<PassiveBreedingSettingsOverride>append(
            new KeyedCodec<>("PassiveBreeding", PASSIVE_BREEDING_OVERRIDE_CODEC),
            (settings, value) -> settings.passiveBreeding = value,
            settings -> settings.passiveBreeding
        )
        .documentation("Settings for passive, timer-based breeding.")
        .add()
        .<TimingSettingsOverride>append(
            new KeyedCodec<>("Timing", TIMING_OVERRIDE_CODEC),
            (settings, value) -> settings.timing = value,
            settings -> settings.timing
        )
        .documentation("Lifecycle timing and growth progression settings.")
        .add()
        .<GenderSettingsOverride>append(
            new KeyedCodec<>("Gender", GENDER_OVERRIDE_CODEC),
            (settings, value) -> settings.gender = value,
            settings -> settings.gender
        )
        .documentation("Optional binary gender settings for this role.")
        .add()
        .<InheritanceSettingsOverride>append(
            new KeyedCodec<>("Inheritance", INHERITANCE_OVERRIDE_CODEC),
            (settings, value) -> settings.inheritance = value,
            settings -> settings.inheritance
        )
        .documentation("If true, inherits ance from parent data.")
        .add()
        .<OffspringLifecycleSettingsOverride>append(
            new KeyedCodec<>("OffspringLifecycle", OFFSPRING_LIFECYCLE_OVERRIDE_CODEC),
            (settings, value) -> settings.offspringLifecycle = value,
            settings -> settings.offspringLifecycle
        )
        .documentation("Role and growth settings for spawned offspring.")
        .add()
        .build();

    private static final MapCodec<RoleOverrideSettings, Map<String, RoleOverrideSettings>> ROLE_OVERRIDES_BY_ROLE_CODEC =
            new MapCodec<>(ROLE_OVERRIDE_CODEC, HashMap::new);

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
        .<HappinessSettings>append(
            new KeyedCodec<>("Happiness", HAPPINESS_CODEC),
            (asset, value) -> asset.happiness = value == null ? new HappinessSettings() : value,
            asset -> asset.happiness
        )
        .documentation("Breeding happiness settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<EligibilitySettings>append(
            new KeyedCodec<>("Eligibility", ELIGIBILITY_CODEC),
            (asset, value) -> asset.eligibility = value == null ? new EligibilitySettings() : value,
            asset -> asset.eligibility
        )
        .documentation("Breeding eligibility settings. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<PairingSettings>append(
            new KeyedCodec<>("Pairing", PAIRING_CODEC),
            (asset, value) -> asset.pairing = value == null ? new PairingSettings() : value,
            asset -> asset.pairing
        )
        .documentation("Breeding pairing settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<CooldownSettings>append(
            new KeyedCodec<>("Cooldowns", COOLDOWN_CODEC),
            (asset, value) -> asset.cooldowns = value == null ? new CooldownSettings() : value,
            asset -> asset.cooldowns
        )
        .documentation("Breeding cooldown settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<PassiveBreedingSettings>append(
            new KeyedCodec<>("PassiveBreeding", PASSIVE_BREEDING_CODEC),
            (asset, value) -> asset.passiveBreeding = value == null ? new PassiveBreedingSettings() : value,
            asset -> asset.passiveBreeding
        )
        .documentation("Passive breeding settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<TimingSettings>append(
            new KeyedCodec<>("Timing", TIMING_CODEC),
            (asset, value) -> asset.timing = value == null ? new TimingSettings() : value,
            asset -> asset.timing
        )
        .documentation("Breeding timing settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<GenderSettings>append(
            new KeyedCodec<>("Gender", GENDER_CODEC),
            (asset, value) -> asset.gender = value == null ? new GenderSettings() : value,
            asset -> asset.gender
        )
        .documentation("Optional binary gender settings. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<InheritanceSettings>append(
            new KeyedCodec<>("Inheritance", INHERITANCE_CODEC),
            (asset, value) -> asset.inheritance = value == null ? new InheritanceSettings() : value,
            asset -> asset.inheritance
        )
        .documentation("Breeding inheritance settings. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<OffspringLifecycleSettings>append(
            new KeyedCodec<>("OffspringLifecycle", OFFSPRING_LIFECYCLE_CODEC),
            (asset, value) -> asset.offspringLifecycle = value == null
                    ? new OffspringLifecycleSettings()
                    : value,
            asset -> asset.offspringLifecycle
        )
        .documentation("Offspring lifecycle settings. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .<Map<String, RoleOverrideSettings>>append(
            new KeyedCodec<>("RoleOverrides", ROLE_OVERRIDES_BY_ROLE_CODEC),
            (asset, value) -> asset.roleOverrides = value == null ? EMPTY_ROLE_OVERRIDES : value,
            asset -> asset.roleOverrides
        )
        .documentation("Per-role override patches. Not inherited from parent; define per child config.")
        .add()
        .build();

    private static AssetStore<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
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
    private GenderSettings gender = new GenderSettings();
    private InheritanceSettings inheritance = new InheritanceSettings();
    private OffspringLifecycleSettings offspringLifecycle = new OffspringLifecycleSettings();
    private Map<String, RoleOverrideSettings> roleOverrides = EMPTY_ROLE_OVERRIDES;

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
        DefaultAssetMap<String, TwBreedingConfig> assetMap = (DefaultAssetMap<String, TwBreedingConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
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
            for (Map.Entry<String, RoleOverrideSettings> overrideEntry : candidate.getRoleOverrides().entrySet()) {
                if (overrideEntry == null || overrideEntry.getKey() == null || overrideEntry.getKey().isBlank()) {
                    continue;
                }
                registerRoleCacheEntry(cache, candidate, overrideEntry.getKey());
                RoleOverrideSettings override = overrideEntry.getValue();
                OffspringLifecycleSettingsOverride lifecycleOverride = override != null
                        ? override.offspringLifecycle
                        : null;
                RoleFamily[] overrideFamilies = lifecycleOverride != null
                        ? lifecycleOverride.families
                        : null;
                if (overrideFamilies == null) {
                    continue;
                }
                for (RoleFamily family : overrideFamilies) {
                    if (family == null) {
                        continue;
                    }
                    registerRoleCacheEntry(cache, candidate, family.getAdultRoleId());
                    registerRoleCacheEntry(cache, candidate, family.getBabyRoleId());
                    registerRoleCacheEntry(cache, candidate, family.getAdolescentRoleId());
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

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwBreedingConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwBreedingConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwBreedingConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Happiness")) {
            happiness = parent.happiness;
        } else {
            inheritHappinessSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Happiness"));
        }
        if (!explicitTopLevelKeys.contains("Eligibility")) {
            eligibility = parent.eligibility;
        } else {
            inheritEligibilitySection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Eligibility"));
        }
        if (!explicitTopLevelKeys.contains("Pairing")) {
            pairing = parent.pairing;
        } else {
            inheritPairingSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Pairing"));
        }
        if (!explicitTopLevelKeys.contains("Cooldowns")) {
            cooldowns = parent.cooldowns;
        } else {
            inheritCooldownSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Cooldowns"));
        }
        if (!explicitTopLevelKeys.contains("PassiveBreeding")) {
            passiveBreeding = parent.passiveBreeding;
        } else {
            inheritPassiveBreedingSection(
                    parent,
                    nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "PassiveBreeding")
            );
        }
        if (!explicitTopLevelKeys.contains("Timing")) {
            timing = parent.timing;
        } else {
            inheritTimingSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Timing"));
        }
        if (!explicitTopLevelKeys.contains("Gender")) {
            gender = parent.gender;
        } else {
            inheritGenderSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Gender"));
        }
        if (!explicitTopLevelKeys.contains("Inheritance")) {
            inheritance = parent.inheritance;
        } else {
            inheritInheritanceSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Inheritance"));
        }
        if (!explicitTopLevelKeys.contains("OffspringLifecycle")) {
            offspringLifecycle = parent.offspringLifecycle;
        } else {
            inheritOffspringLifecycleSection(
                    parent,
                    nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "OffspringLifecycle")
            );
        }
        if (roleOverrides == null) {
            roleOverrides = EMPTY_ROLE_OVERRIDES;
        }
    }

    private void inheritHappinessSection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
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
        if (!nestedExplicitKeys.contains("Threshold")) {
            happiness.threshold = parent.happiness.threshold;
        }
    }

    private void inheritEligibilitySection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (eligibility == null) {
            eligibility = parent.eligibility;
            return;
        }
        if (parent.eligibility == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("RequireTamed")) eligibility.requireTamed = parent.eligibility.requireTamed;
        if (!nestedExplicitKeys.contains("RequireAdult")) eligibility.requireAdult = parent.eligibility.requireAdult;
        if (!nestedExplicitKeys.contains("RequireNotInCombat")) {
            eligibility.requireNotInCombat = parent.eligibility.requireNotInCombat;
        }
        if (!nestedExplicitKeys.contains("RequireNotSleeping")) {
            eligibility.requireNotSleeping = parent.eligibility.requireNotSleeping;
        }
    }

    private void inheritPairingSection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (pairing == null) {
            pairing = parent.pairing;
            return;
        }
        if (parent.pairing == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("BreedRadius")) pairing.breedRadius = parent.pairing.breedRadius;
        if (!nestedExplicitKeys.contains("RequireWanderMode")) {
            pairing.requireWanderMode = parent.pairing.requireWanderMode;
        }
        if (!nestedExplicitKeys.contains("RequireSameOwner")) {
            pairing.requireSameOwner = parent.pairing.requireSameOwner;
        }
        if (!nestedExplicitKeys.contains("MaxNearbySameType")) {
            pairing.maxNearbySameType = parent.pairing.maxNearbySameType;
        }
        if (!nestedExplicitKeys.contains("RequireSameRoleId")) {
            pairing.requireSameRoleId = parent.pairing.requireSameRoleId;
        }
        if (!nestedExplicitKeys.contains("RoleCompatibility")) {
            pairing.roleCompatibility = parent.pairing.roleCompatibility;
        }
        if (!nestedExplicitKeys.contains("RoleMaxNearbySameType")) {
            pairing.roleMaxNearbySameType = parent.pairing.roleMaxNearbySameType;
        }
    }

    private void inheritCooldownSection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (cooldowns == null) {
            cooldowns = parent.cooldowns;
            return;
        }
        if (parent.cooldowns == null) {
            return;
        }
        if (!containsAny(nestedExplicitKeys, "BaseCooldownSeconds", "BaseCooldownMinutes")) {
            cooldowns.baseCooldownSeconds = parent.cooldowns.baseCooldownSeconds;
        }
        if (!nestedExplicitKeys.contains("MinDelaySeconds")) {
            cooldowns.minDelaySeconds = parent.cooldowns.minDelaySeconds;
        }
        if (!nestedExplicitKeys.contains("MaxDelaySeconds")) {
            cooldowns.maxDelaySeconds = parent.cooldowns.maxDelaySeconds;
        }
    }

    private void inheritPassiveBreedingSection(@Nonnull TwBreedingConfig parent,
                                               @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (passiveBreeding == null) {
            passiveBreeding = parent.passiveBreeding;
            return;
        }
        if (parent.passiveBreeding == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Enabled")) {
            passiveBreeding.enabled = parent.passiveBreeding.enabled;
        }
        if (!nestedExplicitKeys.contains("SweepIntervalSeconds")) {
            passiveBreeding.sweepIntervalSeconds = parent.passiveBreeding.sweepIntervalSeconds;
        }
        if (!nestedExplicitKeys.contains("Basis")) {
            passiveBreeding.timerBasis = parent.passiveBreeding.timerBasis;
        }
    }

    private void inheritTimingSection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (timing == null) {
            timing = parent.timing;
            return;
        }
        if (parent.timing == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Basis")) timing.timerBasis = parent.timing.timerBasis;
    }

    private void inheritGenderSection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (gender == null) {
            gender = parent.gender;
            return;
        }
        if (parent.gender == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Enabled")) gender.enabled = parent.gender.enabled;
        if (!nestedExplicitKeys.contains("RequireDifferentGender")) {
            gender.requireDifferentGender = parent.gender.requireDifferentGender;
        }
        if (!nestedExplicitKeys.contains("MaleWeight")) gender.maleWeight = parent.gender.maleWeight;
        if (!nestedExplicitKeys.contains("FemaleWeight")) gender.femaleWeight = parent.gender.femaleWeight;
    }

    private void inheritInheritanceSection(@Nonnull TwBreedingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
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
        if (!nestedExplicitKeys.contains("InheritOwner")) inheritance.inheritOwner = parent.inheritance.inheritOwner;
        if (!nestedExplicitKeys.contains("InheritTamed")) inheritance.inheritTamed = parent.inheritance.inheritTamed;
        if (!nestedExplicitKeys.contains("InheritAttachments")) {
            inheritance.inheritAttachments = parent.inheritance.inheritAttachments;
        }
        if (!nestedExplicitKeys.contains("InheritTraits")) {
            inheritance.inheritTraits = parent.inheritance.inheritTraits;
        }
        if (!nestedExplicitKeys.contains("AttachmentInheritance")) {
            inheritance.attachmentInheritance = parent.inheritance.attachmentInheritance;
        } else {
            inheritAttachmentInheritanceSection(parent, nestedExplicitKeys);
        }
    }

    private void inheritAttachmentInheritanceSection(@Nonnull TwBreedingConfig parent,
                                                     @Nonnull Set<String> nestedExplicitKeys) {
        if (inheritance == null || parent.inheritance == null) {
            return;
        }
        if (inheritance.attachmentInheritance == null) {
            inheritance.attachmentInheritance = parent.inheritance.attachmentInheritance;
            return;
        }
        if (parent.inheritance.attachmentInheritance == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("AttachmentInheritance.ParentWeight")) {
            inheritance.attachmentInheritance.parentWeight = parent.inheritance.attachmentInheritance.parentWeight;
        }
        if (!nestedExplicitKeys.contains("AttachmentInheritance.RandomWeight")) {
            inheritance.attachmentInheritance.randomWeight = parent.inheritance.attachmentInheritance.randomWeight;
        }
        if (!nestedExplicitKeys.contains("AttachmentInheritance.MutationChance")) {
            inheritance.attachmentInheritance.mutationChance = parent.inheritance.attachmentInheritance.mutationChance;
        }
    }

    private void inheritOffspringLifecycleSection(@Nonnull TwBreedingConfig parent,
                                                  @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (offspringLifecycle == null) {
            offspringLifecycle = parent.offspringLifecycle;
            return;
        }
        if (parent.offspringLifecycle == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Enabled")) {
            offspringLifecycle.enabled = parent.offspringLifecycle.enabled;
        }
        if (!containsAny(
                nestedExplicitKeys,
                "DefaultTimeToFullGrownSeconds",
                "DefaultTimeToFullGrownMinutes",
                "TimeToFullGrownSeconds",
                "TimeToFullGrownMinutes"
        )) {
            offspringLifecycle.defaultTimeToFullGrownSeconds = parent.offspringLifecycle.defaultTimeToFullGrownSeconds;
        }
        if (!containsAny(nestedExplicitKeys, "DefaultBabyStartScale", "BabyStartScale")) {
            offspringLifecycle.defaultBabyStartScale = parent.offspringLifecycle.defaultBabyStartScale;
        }
        if (!containsAny(nestedExplicitKeys, "DefaultAdolescentStartScale", "AdolescentStartScale")) {
            offspringLifecycle.defaultAdolescentStartScale = parent.offspringLifecycle.defaultAdolescentStartScale;
        }
        if (!containsAny(nestedExplicitKeys, "DefaultAdultStartScale", "AdultStartScale")) {
            offspringLifecycle.defaultAdultStartScale = parent.offspringLifecycle.defaultAdultStartScale;
        }
        if (!containsAny(nestedExplicitKeys, "DefaultAdolescentSwitchScale", "AdolescentSwitchScale")) {
            offspringLifecycle.defaultAdolescentSwitchScale = parent.offspringLifecycle.defaultAdolescentSwitchScale;
        }
        if (!containsAny(nestedExplicitKeys, "DefaultAdultSwitchScale", "AdultSwitchScale")) {
            offspringLifecycle.defaultAdultSwitchScale = parent.offspringLifecycle.defaultAdultSwitchScale;
        }
        if (!nestedExplicitKeys.contains("Families")) {
            offspringLifecycle.families = parent.offspringLifecycle.families;
        }
    }

    private static boolean containsAny(@Nonnull Set<String> nestedExplicitKeys, @Nonnull String... keys) {
        for (String key : keys) {
            if (nestedExplicitKeys.contains(key)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
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

    public HappinessSettings resolveHappiness(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        HappinessSettings resolved;
        if (override == null || override.happiness == null) {
            resolved = copyHappiness(getHappiness());
        } else {
            resolved = copyHappiness(getHappiness());
            override.happiness.applyTo(resolved);
        }
        if (!isHappinessRequiredByRuntimeOverrides()) {
            resolved.threshold = 0.0;
        }
        return resolved;
    }

    public boolean isHappinessRequired(@Nullable String roleId) {
        return resolveHappiness(roleId).getThreshold() > 0.0;
    }

    public EligibilitySettings getEligibility() {
        return eligibility == null ? new EligibilitySettings() : eligibility;
    }

    public EligibilitySettings resolveEligibility(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.eligibility == null) {
            return getEligibility();
        }
        EligibilitySettings resolved = copyEligibility(getEligibility());
        override.eligibility.applyTo(resolved);
        return resolved;
    }

    public PairingSettings getPairing() {
        return pairing == null ? new PairingSettings() : pairing;
    }

    public PairingSettings resolvePairing(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.pairing == null) {
            return getPairing();
        }
        PairingSettings resolved = copyPairing(getPairing());
        override.pairing.applyTo(resolved);
        return resolved;
    }

    public int resolveMaxNearbySameType(@Nullable String roleId) {
        return resolvePairing(roleId).resolveMaxNearbySameType(roleId);
    }

    public CooldownSettings getCooldowns() {
        return cooldowns == null ? new CooldownSettings() : cooldowns;
    }

    public CooldownSettings resolveCooldowns(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.cooldowns == null) {
            return getCooldowns();
        }
        CooldownSettings resolved = copyCooldowns(getCooldowns());
        override.cooldowns.applyTo(resolved);
        return resolved;
    }

    public PassiveBreedingSettings getPassiveBreeding() {
        return passiveBreeding == null ? new PassiveBreedingSettings() : passiveBreeding;
    }

    public PassiveBreedingSettings resolvePassiveBreeding(@Nullable String roleId) {
        PassiveBreedingSettings resolved;
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.passiveBreeding == null) {
            resolved = copyPassiveBreeding(getPassiveBreeding());
        } else {
            resolved = copyPassiveBreeding(getPassiveBreeding());
            override.passiveBreeding.applyTo(resolved);
        }
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.passiveBreedingEnabled() != null) {
            resolved.enabled = overrides.passiveBreedingEnabled();
        }
        return resolved;
    }

    @Nullable
    private static TameworkSettingsStore.GlobalOverrides resolveRuntimeOverrides() {
        return TameworkSettingsStore.loadRuntimeGlobalOverrides();
    }

    private static boolean isHappinessRequiredByRuntimeOverrides() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides == null) {
            return true;
        }
        if (overrides.happinessEnabled() != null && !overrides.happinessEnabled()) {
            return false;
        }
        return overrides.breedingRequiresHappiness() == null || overrides.breedingRequiresHappiness();
    }

    public TimingSettings getTiming() {
        return timing == null ? new TimingSettings() : timing;
    }

    public TimingSettings resolveTiming(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.timing == null) {
            return getTiming();
        }
        TimingSettings resolved = copyTiming(getTiming());
        override.timing.applyTo(resolved);
        return resolved;
    }

    public GenderSettings getGender() {
        return gender == null ? new GenderSettings() : gender;
    }

    public GenderSettings resolveGender(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.gender == null) {
            return copyGender(getGender());
        }
        GenderSettings resolved = copyGender(getGender());
        override.gender.applyTo(resolved);
        return resolved;
    }

    public InheritanceSettings getInheritance() {
        return inheritance == null ? new InheritanceSettings() : inheritance;
    }

    public InheritanceSettings resolveInheritance(@Nullable String roleId) {
        RoleOverrideSettings override = resolveRoleOverride(roleId);
        if (override == null || override.inheritance == null) {
            return getInheritance();
        }
        InheritanceSettings resolved = copyInheritance(getInheritance());
        override.inheritance.applyTo(resolved);
        return resolved;
    }

    public OffspringLifecycleSettings getOffspringLifecycle() {
        return offspringLifecycle == null ? new OffspringLifecycleSettings() : offspringLifecycle;
    }

    public OffspringLifecycleSettings resolveOffspringLifecycle(@Nullable String roleId) {
        RoleOverrideSettings override = resolveOffspringLifecycleOverride(roleId);
        if (override == null || override.offspringLifecycle == null) {
            return getOffspringLifecycle();
        }
        OffspringLifecycleSettings resolved = copyOffspringLifecycle(getOffspringLifecycle());
        override.offspringLifecycle.applyTo(resolved);
        return resolved;
    }

    public Map<String, RoleOverrideSettings> getRoleOverrides() {
        return roleOverrides == null ? EMPTY_ROLE_OVERRIDES : roleOverrides;
    }

    @Nullable
    public RoleFamily resolveLifecycleFamilyForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return resolveOffspringLifecycle(roleId).resolveFamilyForRole(roleId);
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
            if (!normalizeRoleCacheKey(entry.getKey()).equals(normalized)) {
                continue;
            }
            return entry.getValue();
        }
        return null;
    }

    @Nullable
    private RoleOverrideSettings resolveOffspringLifecycleOverride(@Nullable String roleId) {
        RoleOverrideSettings exact = resolveRoleOverride(roleId);
        if (exact != null && exact.offspringLifecycle != null) {
            return exact;
        }
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        for (RoleOverrideSettings override : getRoleOverrides().values()) {
            if (override == null || override.offspringLifecycle == null || override.offspringLifecycle.families == null) {
                continue;
            }
            for (RoleFamily family : override.offspringLifecycle.families) {
                if (family != null && family.matchesRole(roleId)) {
                    return override;
                }
            }
        }
        return null;
    }

    private static HappinessSettings copyHappiness(@Nullable HappinessSettings source) {
        HappinessSettings base = source == null ? new HappinessSettings() : source;
        HappinessSettings copy = new HappinessSettings();
        copy.threshold = base.threshold;
        return copy;
    }

    private static EligibilitySettings copyEligibility(@Nullable EligibilitySettings source) {
        EligibilitySettings base = source == null ? new EligibilitySettings() : source;
        EligibilitySettings copy = new EligibilitySettings();
        copy.requireTamed = base.requireTamed;
        copy.requireAdult = base.requireAdult;
        copy.requireNotInCombat = base.requireNotInCombat;
        copy.requireNotSleeping = base.requireNotSleeping;
        return copy;
    }

    private static PairingSettings copyPairing(@Nullable PairingSettings source) {
        PairingSettings base = source == null ? new PairingSettings() : source;
        PairingSettings copy = new PairingSettings();
        copy.breedRadius = base.breedRadius;
        copy.requireWanderMode = base.requireWanderMode;
        copy.requireSameOwner = base.requireSameOwner;
        copy.maxNearbySameType = base.maxNearbySameType;
        copy.requireSameRoleId = base.requireSameRoleId;
        copy.roleCompatibility = base.roleCompatibility;
        copy.roleMaxNearbySameType = base.roleMaxNearbySameType == null
                ? EMPTY_ROLE_MAX_NEARBY_OVERRIDES
                : base.roleMaxNearbySameType.clone();
        return copy;
    }

    private static CooldownSettings copyCooldowns(@Nullable CooldownSettings source) {
        CooldownSettings base = source == null ? new CooldownSettings() : source;
        CooldownSettings copy = new CooldownSettings();
        copy.baseCooldownSeconds = base.baseCooldownSeconds;
        copy.minDelaySeconds = base.minDelaySeconds;
        copy.maxDelaySeconds = base.maxDelaySeconds;
        return copy;
    }

    private static PassiveBreedingSettings copyPassiveBreeding(@Nullable PassiveBreedingSettings source) {
        PassiveBreedingSettings base = source == null ? new PassiveBreedingSettings() : source;
        PassiveBreedingSettings copy = new PassiveBreedingSettings();
        copy.enabled = base.enabled;
        copy.sweepIntervalSeconds = base.sweepIntervalSeconds;
        copy.timerBasis = base.timerBasis;
        return copy;
    }

    private static TimingSettings copyTiming(@Nullable TimingSettings source) {
        TimingSettings base = source == null ? new TimingSettings() : source;
        TimingSettings copy = new TimingSettings();
        copy.timerBasis = base.timerBasis;
        return copy;
    }

    private static GenderSettings copyGender(@Nullable GenderSettings source) {
        GenderSettings base = source == null ? new GenderSettings() : source;
        GenderSettings copy = new GenderSettings();
        copy.enabled = base.enabled;
        copy.requireDifferentGender = base.requireDifferentGender;
        copy.maleWeight = base.maleWeight;
        copy.femaleWeight = base.femaleWeight;
        return copy;
    }

    private static InheritanceSettings copyInheritance(@Nullable InheritanceSettings source) {
        InheritanceSettings base = source == null ? new InheritanceSettings() : source;
        InheritanceSettings copy = new InheritanceSettings();
        copy.inheritOwner = base.inheritOwner;
        copy.inheritTamed = base.inheritTamed;
        copy.inheritAttachments = base.inheritAttachments;
        copy.inheritTraits = base.inheritTraits;
        copy.attachmentInheritance = copyAttachmentInheritance(base.attachmentInheritance);
        return copy;
    }

    private static AttachmentInheritanceSettings copyAttachmentInheritance(
            @Nullable AttachmentInheritanceSettings source) {
        AttachmentInheritanceSettings base = source == null
                ? new AttachmentInheritanceSettings()
                : source;
        AttachmentInheritanceSettings copy = new AttachmentInheritanceSettings();
        copy.parentWeight = base.parentWeight;
        copy.randomWeight = base.randomWeight;
        copy.mutationChance = base.mutationChance;
        return copy;
    }

    private static int minutesToSeconds(int minutes, @Nullable Integer fallbackSeconds) {
        int safeFallback = fallbackSeconds == null ? 0 : Math.max(0, fallbackSeconds);
        if (minutes < 0) {
            return safeFallback;
        }
        long seconds = (long) minutes * SECONDS_PER_MINUTE;
        if (seconds > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) seconds;
    }

    private static OffspringLifecycleSettings copyOffspringLifecycle(@Nullable OffspringLifecycleSettings source) {
        OffspringLifecycleSettings base = source == null ? new OffspringLifecycleSettings() : source;
        OffspringLifecycleSettings copy = new OffspringLifecycleSettings();
        copy.enabled = base.enabled;
        copy.defaultTimeToFullGrownSeconds = base.defaultTimeToFullGrownSeconds;
        copy.defaultBabyStartScale = base.defaultBabyStartScale;
        copy.defaultAdolescentStartScale = base.defaultAdolescentStartScale;
        copy.defaultAdultStartScale = base.defaultAdultStartScale;
        copy.defaultAdolescentSwitchScale = base.defaultAdolescentSwitchScale;
        copy.defaultAdultSwitchScale = base.defaultAdultSwitchScale;
        copy.families = base.families == null ? EMPTY_ROLE_FAMILIES : base.families.clone();
        return copy;
    }

    /** Optional per-role overrides for breeding sections. */
    public static final class RoleOverrideSettings {
        private HappinessSettingsOverride happiness;
        private EligibilitySettingsOverride eligibility;
        private PairingSettingsOverride pairing;
        private CooldownSettingsOverride cooldowns;
        private PassiveBreedingSettingsOverride passiveBreeding;
        private TimingSettingsOverride timing;
        private GenderSettingsOverride gender;
        private InheritanceSettingsOverride inheritance;
        private OffspringLifecycleSettingsOverride offspringLifecycle;
    }

    /** Partial happiness override patch for a single role. */
    public static final class HappinessSettingsOverride {
        private Double threshold;

        private void applyTo(@Nonnull HappinessSettings target) {
            if (threshold != null) {
                target.threshold = threshold;
            }
        }
    }

    /** Partial eligibility override patch for a single role. */
    public static final class EligibilitySettingsOverride {
        private Boolean requireTamed;
        private Boolean requireAdult;
        private Boolean requireNotInCombat;
        private Boolean requireNotSleeping;

        private void applyTo(@Nonnull EligibilitySettings target) {
            if (requireTamed != null) {
                target.requireTamed = requireTamed;
            }
            if (requireAdult != null) {
                target.requireAdult = requireAdult;
            }
            if (requireNotInCombat != null) {
                target.requireNotInCombat = requireNotInCombat;
            }
            if (requireNotSleeping != null) {
                target.requireNotSleeping = requireNotSleeping;
            }
        }
    }

    /** Partial pairing override patch for a single role. */
    public static final class PairingSettingsOverride {
        private Double breedRadius;
        private Boolean requireWanderMode;
        private Boolean requireSameOwner;
        private Integer maxNearbySameType;
        private Boolean requireSameRoleId;
        private RoleCompatibility roleCompatibility;
        private RoleMaxNearbySameTypeOverride[] roleMaxNearbySameType;

        private void applyTo(@Nonnull PairingSettings target) {
            if (breedRadius != null) {
                target.breedRadius = breedRadius;
            }
            if (requireWanderMode != null) {
                target.requireWanderMode = requireWanderMode;
            }
            if (requireSameOwner != null) {
                target.requireSameOwner = requireSameOwner;
            }
            if (maxNearbySameType != null) {
                target.maxNearbySameType = maxNearbySameType;
            }
            if (requireSameRoleId != null) {
                target.requireSameRoleId = requireSameRoleId;
            }
            if (roleCompatibility != null) {
                target.roleCompatibility = roleCompatibility;
            }
            if (roleMaxNearbySameType != null) {
                target.roleMaxNearbySameType = roleMaxNearbySameType;
            }
        }
    }

    /** Partial cooldown override patch for a single role. */
    public static final class CooldownSettingsOverride {
        private Integer baseCooldownSeconds;
        private Integer minDelaySeconds;
        private Integer maxDelaySeconds;

        private void applyTo(@Nonnull CooldownSettings target) {
            if (baseCooldownSeconds != null) {
                target.baseCooldownSeconds = baseCooldownSeconds;
            }
            if (minDelaySeconds != null) {
                target.minDelaySeconds = minDelaySeconds;
            }
            if (maxDelaySeconds != null) {
                target.maxDelaySeconds = maxDelaySeconds;
            }
        }
    }

    /** Partial passive-breeding override patch for a single role. */
    public static final class PassiveBreedingSettingsOverride {
        private Boolean enabled;
        private Integer sweepIntervalSeconds;
        private TimerBasis timerBasis;

        private void applyTo(@Nonnull PassiveBreedingSettings target) {
            if (enabled != null) {
                target.enabled = enabled;
            }
            if (sweepIntervalSeconds != null) {
                target.sweepIntervalSeconds = sweepIntervalSeconds;
            }
            if (timerBasis != null) {
                target.timerBasis = timerBasis;
            }
        }
    }

    /** Partial timing override patch for a single role. */
    public static final class TimingSettingsOverride {
        private TimerBasis timerBasis;

        private void applyTo(@Nonnull TimingSettings target) {
            if (timerBasis != null) {
                target.timerBasis = timerBasis;
            }
        }
    }

    /** Partial gender override patch for a single role. */
    public static final class GenderSettingsOverride {
        private Boolean enabled;
        private Boolean requireDifferentGender;
        private Double maleWeight;
        private Double femaleWeight;

        private void applyTo(@Nonnull GenderSettings target) {
            if (enabled != null) {
                target.enabled = enabled;
            }
            if (requireDifferentGender != null) {
                target.requireDifferentGender = requireDifferentGender;
            }
            if (maleWeight != null) {
                target.maleWeight = maleWeight;
            }
            if (femaleWeight != null) {
                target.femaleWeight = femaleWeight;
            }
        }
    }

    /** Partial attachment-inheritance override patch for a single role. */
    public static final class AttachmentInheritanceSettingsOverride {
        private Double parentWeight;
        private Double randomWeight;
        private Double mutationChance;

        private void applyTo(@Nonnull AttachmentInheritanceSettings target) {
            if (parentWeight != null) {
                target.parentWeight = parentWeight;
            }
            if (randomWeight != null) {
                target.randomWeight = randomWeight;
            }
            if (mutationChance != null) {
                target.mutationChance = mutationChance;
            }
        }
    }

    /** Partial inheritance override patch for a single role. */
    public static final class InheritanceSettingsOverride {
        private Boolean inheritOwner;
        private Boolean inheritTamed;
        private Boolean inheritAttachments;
        private Boolean inheritTraits;
        private AttachmentInheritanceSettingsOverride attachmentInheritance;

        private void applyTo(@Nonnull InheritanceSettings target) {
            if (inheritOwner != null) {
                target.inheritOwner = inheritOwner;
            }
            if (inheritTamed != null) {
                target.inheritTamed = inheritTamed;
            }
            if (inheritAttachments != null) {
                target.inheritAttachments = inheritAttachments;
            }
            if (inheritTraits != null) {
                target.inheritTraits = inheritTraits;
            }
            if (attachmentInheritance != null) {
                if (target.attachmentInheritance == null) {
                    target.attachmentInheritance = new AttachmentInheritanceSettings();
                }
                attachmentInheritance.applyTo(target.attachmentInheritance);
            }
        }
    }

    /** Partial lifecycle override patch for a single role. */
    public static final class OffspringLifecycleSettingsOverride {
        private Boolean enabled;
        private Integer defaultTimeToFullGrownSeconds;
        private Double defaultBabyStartScale;
        private Double defaultAdolescentStartScale;
        private Double defaultAdultStartScale;
        private Double defaultAdolescentSwitchScale;
        private Double defaultAdultSwitchScale;
        private RoleFamily[] families;

        private void applyTo(@Nonnull OffspringLifecycleSettings target) {
            if (enabled != null) {
                target.enabled = enabled;
            }
            if (defaultTimeToFullGrownSeconds != null) {
                target.defaultTimeToFullGrownSeconds = defaultTimeToFullGrownSeconds;
            }
            if (defaultBabyStartScale != null) {
                target.defaultBabyStartScale = defaultBabyStartScale;
            }
            if (defaultAdolescentStartScale != null) {
                target.defaultAdolescentStartScale = defaultAdolescentStartScale;
            }
            if (defaultAdultStartScale != null) {
                target.defaultAdultStartScale = defaultAdultStartScale;
            }
            if (defaultAdolescentSwitchScale != null) {
                target.defaultAdolescentSwitchScale = defaultAdolescentSwitchScale;
            }
            if (defaultAdultSwitchScale != null) {
                target.defaultAdultSwitchScale = defaultAdultSwitchScale;
            }
            if (families != null) {
                target.families = families;
            }
        }
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
        private RoleCompatibility roleCompatibility;
        private RoleMaxNearbySameTypeOverride[] roleMaxNearbySameType = EMPTY_ROLE_MAX_NEARBY_OVERRIDES;

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
            return Math.max(0, maxNearbySameType);
        }

        public boolean isRequireSameRoleId() {
            return requireSameRoleId;
        }

        public RoleCompatibility getRoleCompatibility() {
            if (roleCompatibility != null) {
                return roleCompatibility;
            }
            return requireSameRoleId ? RoleCompatibility.SAME_ROLE : RoleCompatibility.ANY;
        }

        public RoleMaxNearbySameTypeOverride[] getRoleMaxNearbySameType() {
            return roleMaxNearbySameType == null ? EMPTY_ROLE_MAX_NEARBY_OVERRIDES : roleMaxNearbySameType;
        }

        public int resolveMaxNearbySameType(@Nullable String roleId) {
            int fallback = getMaxNearbySameType();
            if (roleId == null || roleId.isBlank()) {
                return fallback;
            }
            for (RoleMaxNearbySameTypeOverride override : getRoleMaxNearbySameType()) {
                if (override == null || !override.matchesRole(roleId)) {
                    continue;
                }
                return override.getMaxNearbySameType(fallback);
            }
            return fallback;
        }
    }

    /** Optional per-role Pairing MaxNearbySameType overrides. */
    public static final class RoleMaxNearbySameTypeOverride {
        private String roleId;
        private Integer maxNearbySameType;

        @Nullable
        public String getRoleId() {
            return roleId;
        }

        public int getMaxNearbySameType(int fallback) {
            if (maxNearbySameType == null) {
                return Math.max(0, fallback);
            }
            return Math.max(0, maxNearbySameType);
        }

        public boolean matchesRole(@Nullable String targetRoleId) {
            if (roleId == null || roleId.isBlank() || targetRoleId == null || targetRoleId.isBlank()) {
                return false;
            }
            return normalizeRoleCacheKey(roleId).equals(normalizeRoleCacheKey(targetRoleId));
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

    /** Partner role compatibility modes for breeding pair selection. */
    public enum RoleCompatibility {
        SAME_ROLE("SameRole"),
        SAME_LIFECYCLE_FAMILY("SameLifecycleFamily"),
        DIFFERENT_FAMILY_ROLE("DifferentFamilyRole"),
        ANY("Any");

        private final String configValue;

        RoleCompatibility(String configValue) {
            this.configValue = configValue;
        }

        @Nullable
        public static RoleCompatibility fromConfigValue(@Nullable String value,
                                                        @Nullable RoleCompatibility fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String normalized = normalizeEnumToken(value);
            for (RoleCompatibility compatibility : values()) {
                if (normalizeEnumToken(compatibility.name()).equals(normalized)
                        || normalizeEnumToken(compatibility.configValue).equals(normalized)) {
                    return compatibility;
                }
            }
            return fallback;
        }

        public String toConfigValue() {
            return configValue;
        }

        private static String normalizeEnumToken(@Nonnull String value) {
            return value.trim()
                    .replace("_", "")
                    .replace("-", "")
                    .replace(" ", "")
                    .toLowerCase(Locale.ROOT);
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

    /** Binary gender values used by optional breeding gender support. */
    public enum Gender {
        Male,
        Female;

        @Nullable
        public static Gender fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            for (Gender candidate : values()) {
                if (candidate.name().equalsIgnoreCase(value.trim())) {
                    return candidate;
                }
            }
            return null;
        }

        public String toConfigValue() {
            return name();
        }
    }

    /** Optional binary gender assignment and pairing settings. */
    public static final class GenderSettings {
        private boolean enabled;
        private boolean requireDifferentGender = true;
        private double maleWeight = 1.0;
        private double femaleWeight = 1.0;

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isRequireDifferentGender() {
            return requireDifferentGender;
        }

        public double getMaleWeight() {
            return sanitizeWeight(maleWeight, 1.0);
        }

        public double getFemaleWeight() {
            return sanitizeWeight(femaleWeight, 1.0);
        }

        public Gender selectGender(double roll) {
            double male = getMaleWeight();
            double female = getFemaleWeight();
            double total = male + female;
            if (!Double.isFinite(total) || total <= 0.0) {
                return Gender.Male;
            }
            double target = clampRoll(roll) * total;
            return target < male ? Gender.Male : Gender.Female;
        }

        private static double sanitizeWeight(double value, double fallback) {
            if (!Double.isFinite(value) || value < 0.0) {
                return fallback;
            }
            return value;
        }

        private static double clampRoll(double roll) {
            if (!Double.isFinite(roll) || roll <= 0.0) {
                return 0.0;
            }
            if (roll >= 1.0) {
                return Math.nextDown(1.0);
            }
            return roll;
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

    /** Weighted adult role option for multi-adult breeding families. */
    public static final class AdultRoleChoice {
        private String roleId;
        private Gender gender;
        private double weight = 1.0;

        @Nullable
        public String getRoleId() {
            return roleId;
        }

        @Nullable
        public Gender getGender() {
            return gender;
        }

        public double getWeight() {
            if (!Double.isFinite(weight) || weight <= 0.0) {
                return 0.0;
            }
            return weight;
        }

        public boolean hasPositiveWeight() {
            return getWeight() > 0.0;
        }

        public boolean matchesRole(@Nullable String targetRoleId) {
            return RoleFamily.matchesRoleId(roleId, targetRoleId);
        }

        public boolean matchesGender(@Nullable Gender targetGender) {
            return targetGender == null || gender == null || gender == targetGender;
        }
    }

    /** Explicit role-family mapping for baby/adolescent/adult lifecycle transitions. */
    public static final class RoleFamily {
        private String adultRoleId;
        private AdultRoleChoice[] adultRoles = EMPTY_ADULT_ROLE_CHOICES;
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
            String fromWeighted = firstWeightedAdultRoleId();
            return fromWeighted != null ? fromWeighted : adultRoleId;
        }

        public AdultRoleChoice[] getAdultRoles() {
            return adultRoles == null ? EMPTY_ADULT_ROLE_CHOICES : adultRoles;
        }

        public boolean hasWeightedAdultRoles() {
            return getAdultRoles().length > 0;
        }

        @Nullable
        public String getLegacyAdultRoleId() {
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
            return matchesAdultRole(roleId)
                    || matchesRoleId(babyRoleId, roleId)
                    || matchesRoleId(adolescentRoleId, roleId);
        }

        public boolean matchesAdultRole(@Nullable String roleId) {
            if (roleId == null || roleId.isBlank()) {
                return false;
            }
            if (hasWeightedAdultRoles()) {
                for (AdultRoleChoice choice : getAdultRoles()) {
                    if (choice != null && choice.matchesRole(roleId)) {
                        return true;
                    }
                }
                return false;
            }
            return matchesRoleId(adultRoleId, roleId);
        }

        public boolean hasSelectableAdultRole() {
            if (hasWeightedAdultRoles()) {
                for (AdultRoleChoice choice : getAdultRoles()) {
                    if (choice != null
                            && choice.getRoleId() != null
                            && !choice.getRoleId().isBlank()
                            && choice.hasPositiveWeight()) {
                        return true;
                    }
                }
                return false;
            }
            return adultRoleId != null && !adultRoleId.isBlank();
        }

        @Nullable
        public Gender resolveGenderForAdultRole(@Nullable String roleId) {
            if (roleId == null || roleId.isBlank()) {
                return null;
            }
            for (AdultRoleChoice choice : getAdultRoles()) {
                if (choice != null && choice.matchesRole(roleId)) {
                    return choice.getGender();
                }
            }
            return null;
        }

        @Nullable
        private String firstWeightedAdultRoleId() {
            for (AdultRoleChoice choice : getAdultRoles()) {
                if (choice == null || choice.getRoleId() == null || choice.getRoleId().isBlank()) {
                    continue;
                }
                return choice.getRoleId();
            }
            return null;
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



