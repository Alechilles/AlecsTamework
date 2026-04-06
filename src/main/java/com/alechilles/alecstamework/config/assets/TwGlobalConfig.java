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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed global configuration for Alec's Tamework.
 * Stored under Server/Tamework/Global.
 */
public final class TwGlobalConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwGlobalConfig>>,
        TwParentFallbackAsset<TwGlobalConfig> {
    private static final int MILLIS_PER_MINUTE = 60_000;
    private static final String DEFAULT_SIMPLE_CLAIMS_DAMAGE_ALLOW_DAMAGE_PERMISSION_KEY =
            "tamework.damage_tamed_claim_npc";
    private static final BuilderCodec<GeneralSection> GENERAL_SECTION_CODEC = BuilderCodec.builder(
                    GeneralSection.class, GeneralSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (section, value) -> section.enabled = value,
                    section -> section.enabled
            )
            .documentation("Turns this section on or off.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (section, value) -> section.priority = value,
                    section -> section.priority
            )
            .documentation("Priority used when multiple configs apply; higher values take precedence.")
            .add()
            .build();

    private static final BuilderCodec<OwnershipProtectionSection> OWNERSHIP_PROTECTION_SECTION_CODEC = BuilderCodec.builder(
                    OwnershipProtectionSection.class, OwnershipProtectionSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("BlockOwnerDamage", Codec.BOOLEAN),
                    (section, value) -> section.blockOwnerDamage = value,
                    section -> section.blockOwnerDamage
            )
            .documentation("Blocks owner damage when enabled.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BlockAllPlayerDamageIfOwned", Codec.BOOLEAN),
                    (section, value) -> section.blockAllPlayerDamageIfOwned = value,
                    section -> section.blockAllPlayerDamageIfOwned
            )
            .documentation("Blocks all player damage if owned when enabled.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InvulnerableIfOwned", Codec.BOOLEAN),
                    (section, value) -> section.invulnerableIfOwned = value,
                    section -> section.invulnerableIfOwned
            )
            .documentation("If true, owned NPCs cannot take damage from normal sources.")
            .add()
            .build();

    private static final BuilderCodec<OwnershipRequirementsSection> OWNERSHIP_REQUIREMENTS_SECTION_CODEC = BuilderCodec.builder(
                    OwnershipRequirementsSection.class, OwnershipRequirementsSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("CaptureRequiresOwner", Codec.BOOLEAN),
                    (section, value) -> section.captureRequiresOwner = value,
                    section -> section.captureRequiresOwner
            )
            .documentation("Default capture ownership requirement when spawner capture settings leave RequireOwner unset.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("SpawnRequiresOwner", Codec.BOOLEAN),
                    (section, value) -> section.spawnRequiresOwner = value,
                    section -> section.spawnRequiresOwner
            )
            .documentation("Default spawn ownership requirement when spawner spawn settings leave RequireOwner unset.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("InteractionRequiresOwner", Codec.BOOLEAN),
                    (section, value) -> section.interactionRequiresOwner = value,
                    section -> section.interactionRequiresOwner
            )
            .documentation("Default interaction ownership requirement when TwInteraction entries leave RequireOwner unset.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("LinkingRequiresOwner", Codec.BOOLEAN),
                    (section, value) -> section.linkingRequiresOwner = value,
                    section -> section.linkingRequiresOwner
            )
            .documentation("Default command-link ownership requirement when TwCommandItemConfig RequireOwner is unset.")
            .add()
            .build();

    private static final BuilderCodec<InteractionDefaultsSection> INTERACTION_DEFAULTS_SECTION_CODEC = BuilderCodec.builder(
                    InteractionDefaultsSection.class, InteractionDefaultsSection::new
            )
            .<String>append(
                    new KeyedCodec<>("InteractionConfigParam", Codec.STRING),
                    (section, value) -> section.interactionConfigParam = value,
                    section -> section.interactionConfigParam
            )
            .documentation("NPC parameter key used to resolve interaction config ID.")
            .add()
            .<String>append(
                    new KeyedCodec<>("LovedItemsParam", Codec.STRING),
                    (section, value) -> section.lovedItemsParam = value,
                    section -> section.lovedItemsParam
            )
            .documentation("NPC parameter key used to resolve loved item set.")
            .add()
            .<String>append(
                    new KeyedCodec<>("IsHarvestableParam", Codec.STRING),
                    (section, value) -> section.isHarvestableParam = value,
                    section -> section.isHarvestableParam
            )
            .documentation("NPC parameter key that marks whether harvesting is allowed.")
            .add()
            .<String>append(
                    new KeyedCodec<>("IsMountableParam", Codec.STRING),
                    (section, value) -> section.isMountableParam = value,
                    section -> section.isMountableParam
            )
            .documentation("NPC parameter key that marks whether mounting is allowed.")
            .add()
            .<String>append(
                    new KeyedCodec<>("HarvestContextParam", Codec.STRING),
                    (section, value) -> section.harvestContextParam = value,
                    section -> section.harvestContextParam
            )
            .documentation("NPC parameter key used for harvest interaction context.")
            .add()
            .<String>append(
                    new KeyedCodec<>("HarvestAlarmName", Codec.STRING),
                    (section, value) -> section.harvestAlarmName = value,
                    section -> section.harvestAlarmName
            )
            .documentation("Alarm name used for harvest-ready timing.")
            .add()
            .<String>append(
                    new KeyedCodec<>("InteractionCooldownAlarmPrefix", Codec.STRING),
                    (section, value) -> section.interactionCooldownAlarmPrefix = value,
                    section -> section.interactionCooldownAlarmPrefix
            )
            .documentation("Prefix used when creating interaction cooldown alarm IDs.")
            .add()
            .build();

    private static final BuilderCodec<CommandSection> COMMAND_SECTION_CODEC = BuilderCodec.builder(
                    CommandSection.class, CommandSection::new
            )
            .<Double>append(
                    new KeyedCodec<>("ReturnHomeTeleportDistance", Codec.DOUBLE),
                    (section, value) -> section.returnHomeTeleportDistance = value,
                    section -> section.returnHomeTeleportDistance
            )
            .documentation("Distance threshold before return-home teleport is attempted.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("ReturnHomePathDistanceBeforeTeleport", Codec.DOUBLE),
                    (section, value) -> section.returnHomePathDistanceBeforeTeleport = value,
                    section -> section.returnHomePathDistanceBeforeTeleport
            )
            .documentation("Path distance threshold before teleport fallback is used.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("ReturnHomeTeleportDelayMs", Codec.INTEGER),
                    (section, value) -> section.returnHomeTeleportDelayMs = value,
                    section -> section.returnHomeTeleportDelayMs
            )
            .documentation("Delay in milliseconds before return-home teleport occurs.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallSafeSpawnDistance", Codec.DOUBLE),
                    (section, value) -> section.recallSafeSpawnDistance = value,
                    section -> section.recallSafeSpawnDistance
            )
            .documentation("Distance used when searching a safe recall spawn position.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("RecallForceRelocateDistance", Codec.DOUBLE),
                    (section, value) -> section.recallForceRelocateDistance = value,
                    section -> section.recallForceRelocateDistance
            )
            .documentation("Distance threshold that forces relocation during recall.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("RelocationRetryIntervalMs", Codec.INTEGER),
                    (section, value) -> section.relocationRetryIntervalMs = value,
                    section -> section.relocationRetryIntervalMs
            )
            .documentation("Value in milliseconds for relocation retry interval.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("RelocationMaxWaitMs", Codec.INTEGER),
                    (section, value) -> section.relocationMaxWaitMs = value,
                    section -> section.relocationMaxWaitMs
            )
            .documentation("Value in milliseconds for relocation max wait.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("RelocationMaxRetryAttempts", Codec.INTEGER),
                    (section, value) -> section.relocationMaxRetryAttempts = value,
                    section -> section.relocationMaxRetryAttempts
            )
            .documentation("Maximum relocation retry attempts before command recovery gives up.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("DeadRespawnEnabled", Codec.BOOLEAN),
                    (section, value) -> section.deadRespawnEnabled = value,
                    section -> section.deadRespawnEnabled
            )
            .documentation("If true, dead linked NPCs can be respawned by command systems.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnCooldownMs", Codec.INTEGER),
                    (section, value) -> section.deadRespawnCooldownMs = value,
                    section -> section.deadRespawnCooldownMs
            )
            .documentation("Cooldown in milliseconds before dead respawn becomes available.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnCooldownMins", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnCooldownMins = value,
                    section -> section.deadRespawnCooldownMins
            )
            .documentation("Legacy minute-based respawn cooldown; converted to milliseconds when provided.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("DeadRespawnFollowRetryDelayMs", Codec.INTEGER),
                    (section, value) -> section.deadRespawnFollowRetryDelayMs = value,
                    section -> section.deadRespawnFollowRetryDelayMs
            )
            .documentation("Retry delay in milliseconds for dead-respawn follow attempts.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceClose", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceClose = value,
                    section -> section.deadRespawnDistanceClose
            )
            .documentation("Distance threshold for the close respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceNear", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceNear = value,
                    section -> section.deadRespawnDistanceNear
            )
            .documentation("Distance threshold for the near respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceMid", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceMid = value,
                    section -> section.deadRespawnDistanceMid
            )
            .documentation("Distance threshold for the mid respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DeadRespawnDistanceFar", Codec.DOUBLE),
                    (section, value) -> section.deadRespawnDistanceFar = value,
                    section -> section.deadRespawnDistanceFar
            )
            .documentation("Distance threshold for the far respawn range.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMinRelativeY", Codec.DOUBLE),
                    (section, value) -> section.placementMinRelativeY = value,
                    section -> section.placementMinRelativeY
            )
            .documentation("Minimum relative Y offset allowed for placement checks.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("PlacementMaxRelativeY", Codec.DOUBLE),
                    (section, value) -> section.placementMaxRelativeY = value,
                    section -> section.placementMaxRelativeY
            )
            .documentation("Maximum relative Y offset allowed for placement checks.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("LinkedPanelRequireUnlinkConfirm", Codec.BOOLEAN),
                    (section, value) -> section.linkedPanelRequireUnlinkConfirm = value,
                    section -> section.linkedPanelRequireUnlinkConfirm
            )
            .documentation("Requires confirmation before unlinking via linked panel UI.")
            .add()
            .build();

    private static final BuilderCodec<AssetSetsSection> ASSET_SETS_SECTION_CODEC = BuilderCodec.builder(
                    AssetSetsSection.class, AssetSetsSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("TranquilizerShortbow", Codec.BOOLEAN),
                    (section, value) -> section.tranquilizerShortbow = value,
                    section -> section.tranquilizerShortbow
            )
            .documentation("Enables the tranquilizer shortbow asset set.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("TranquilizerArrow", Codec.BOOLEAN),
                    (section, value) -> section.tranquilizerArrow = value,
                    section -> section.tranquilizerArrow
            )
            .documentation("Enables the tranquilizer arrow asset set.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("TranquilizerPotion", Codec.BOOLEAN),
                    (section, value) -> section.tranquilizerPotion = value,
                    section -> section.tranquilizerPotion
            )
            .documentation("Enables the tranquilizer potion asset set.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("FeedTrough", Codec.BOOLEAN),
                    (section, value) -> section.feedTrough = value,
                    section -> section.feedTrough
            )
            .documentation("Enables the feed trough asset set.")
            .add()
            .build();
    private static final BuilderCodec<PopulationSection> POPULATION_SECTION_CODEC = BuilderCodec.builder(
                    PopulationSection.class, PopulationSection::new
            )
            .<Integer>append(
                    new KeyedCodec<>("LimitPerPlayerOwnedTotal", Codec.INTEGER),
                    (section, value) -> section.limitPerPlayerOwnedTotal = value,
                    section -> section.limitPerPlayerOwnedTotal
            )
            .documentation("Maximum tamed NPCs a player can own within the selected scope.")
            .add()
            .<String>append(
                    new KeyedCodec<>("PerPlayerLimitScope", Codec.STRING),
                    (section, value) -> section.perPlayerLimitScope = value,
                    section -> section.perPlayerLimitScope
            )
            .documentation("Scope used when counting per-player ownership limits.")
            .add()
            .build();
    private static final BuilderCodec<SimpleClaimsBreedingSection> SIMPLE_CLAIMS_BREEDING_SECTION_CODEC = BuilderCodec.builder(
                    SimpleClaimsBreedingSection.class, SimpleClaimsBreedingSection::new
            )
            .<Integer>append(
                    new KeyedCodec<>("LimitPerClaimChunk", Codec.INTEGER),
                    (section, value) -> section.limitPerClaimChunk = value,
                    section -> section.limitPerClaimChunk
            )
            .documentation("Maximum breedable tames allowed per claim chunk.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("LimitPerClaimTotal", Codec.INTEGER),
                    (section, value) -> section.limitPerClaimTotal = value,
                    section -> section.limitPerClaimTotal
            )
            .documentation("Maximum breedable tames allowed per claim.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("BreedingRequiresClaim", Codec.BOOLEAN),
                    (section, value) -> section.breedingRequiresClaim = value,
                    section -> section.breedingRequiresClaim
            )
            .documentation("Requires breeding to happen inside a valid claim.")
            .add()
            .build();
    private static final BuilderCodec<SimpleClaimsDamageSection> SIMPLE_CLAIMS_DAMAGE_SECTION_CODEC = BuilderCodec.builder(
                    SimpleClaimsDamageSection.class, SimpleClaimsDamageSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("ProtectTamedFromNonMembers", Codec.BOOLEAN),
                    (section, value) -> section.protectTamedFromNonMembers = value,
                    section -> section.protectTamedFromNonMembers
            )
            .documentation("Prevents non-members from damaging protected tamed NPCs.")
            .add()
            .<String>append(
                    new KeyedCodec<>("AllowDamagePermissionKey", Codec.STRING),
                    (section, value) -> section.allowDamagePermissionKey = value,
                    section -> section.allowDamagePermissionKey
            )
            .documentation("Permission key that allows non-members to damage protected tames.")
            .add()
            .build();
    private static final BuilderCodec<SimpleClaimsSection> SIMPLE_CLAIMS_SECTION_CODEC = BuilderCodec.builder(
                    SimpleClaimsSection.class, SimpleClaimsSection::new
            )
            .<Boolean>append(
                    new KeyedCodec<>("SimpleClaimsEnabled", Codec.BOOLEAN),
                    (section, value) -> section.simpleClaimsEnabled = value,
                    section -> section.simpleClaimsEnabled
            )
            .documentation("Enables Simple Claims integration for tame protection and limits.")
            .add()
            .<SimpleClaimsBreedingSection>append(
                    new KeyedCodec<>("Breeding", SIMPLE_CLAIMS_BREEDING_SECTION_CODEC),
                    (section, value) -> section.breeding = value,
                    section -> section.breeding
            )
            .documentation("Breeding-related settings for this config.")
            .add()
            .<SimpleClaimsDamageSection>append(
                    new KeyedCodec<>("Damage", SIMPLE_CLAIMS_DAMAGE_SECTION_CODEC),
                    (section, value) -> section.damage = value,
                    section -> section.damage
            )
            .documentation("Simple Claims damage-protection settings for tamed NPCs.")
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwGlobalConfig> CODEC =
            AssetBuilderCodec.builder(
                    TwGlobalConfig.class,
                    TwGlobalConfig::new,
                    Codec.STRING,
                    (asset, id) -> asset.id = id,
                    asset -> asset.id,
                    (asset, data) -> asset.data = data,
                    asset -> asset.data
            )
            .documentation("Global defaults and settings for Alec's Tamework.")
            .<GeneralSection>append(
                    new KeyedCodec<>("General", GENERAL_SECTION_CODEC),
                    TwGlobalConfig::applyGeneralSection,
                    TwGlobalConfig::toGeneralSection
            )
            .documentation("Organized section for global enabled/priority settings. Inheritance: omitted section "
                    + "inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<OwnershipProtectionSection>append(
                    new KeyedCodec<>("OwnershipProtection", OWNERSHIP_PROTECTION_SECTION_CODEC),
                    TwGlobalConfig::applyOwnershipProtectionSection,
                    TwGlobalConfig::toOwnershipProtectionSection
            )
            .documentation("Organized section for owner-damage and ownership protection settings. Inheritance: omitted "
                    + "section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<OwnershipRequirementsSection>append(
                    new KeyedCodec<>("OwnershipRequirements", OWNERSHIP_REQUIREMENTS_SECTION_CODEC),
                    TwGlobalConfig::applyOwnershipRequirementsSection,
                    TwGlobalConfig::toOwnershipRequirementsSection
            )
            .documentation("Global ownership fallback toggles for capture/spawn/interactions/command linking. Inheritance: "
                    + "omitted section inherits from parent; when present, only explicitly defined nested fields "
                    + "override parent.")
            .add()
            .<InteractionDefaultsSection>append(
                    new KeyedCodec<>("InteractionDefaults", INTERACTION_DEFAULTS_SECTION_CODEC),
                    TwGlobalConfig::applyInteractionDefaultsSection,
                    TwGlobalConfig::toInteractionDefaultsSection
            )
            .documentation("Organized section for interaction parameter defaults. Inheritance: omitted section inherits "
                    + "from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<CommandSection>append(
                    new KeyedCodec<>("Command", COMMAND_SECTION_CODEC),
                    TwGlobalConfig::applyCommandSection,
                    TwGlobalConfig::toCommandSection
            )
            .documentation("Organized section for command runtime and respawn tuning settings. Inheritance: omitted "
                    + "section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<AssetSetsSection>append(
                    new KeyedCodec<>("AssetSets", ASSET_SETS_SECTION_CODEC),
                    TwGlobalConfig::applyAssetSetsSection,
                    TwGlobalConfig::toAssetSetsSection
            )
            .documentation("Opt-in asset-set gates that can be enabled by any loaded TwGlobalConfig asset. Inheritance: "
                    + "omitted section inherits from parent; when present, only explicitly defined nested fields "
                    + "override parent.")
            .add()
            .<PopulationSection>append(
                    new KeyedCodec<>("Population", POPULATION_SECTION_CODEC),
                    TwGlobalConfig::applyPopulationSection,
                    TwGlobalConfig::toPopulationSection
            )
            .documentation("Server-level owned NPC population limits. Inheritance: omitted section inherits from parent; "
                    + "when present, only explicitly defined nested fields override parent.")
            .add()
            .<SimpleClaimsSection>append(
                    new KeyedCodec<>("SimpleClaims", SIMPLE_CLAIMS_SECTION_CODEC),
                    TwGlobalConfig::applySimpleClaimsSection,
                    TwGlobalConfig::toSimpleClaimsSection
            )
            .documentation("Server-level SimpleClaims integration settings for Tamework systems. Inheritance: omitted "
                    + "section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .build();

    private static AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean CACHE_DIRTY = true;
    private static volatile TwGlobalConfig ACTIVE_CONFIG;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private boolean blockOwnerDamage;
    private boolean blockAllPlayerDamageIfOwned;
    private boolean invulnerableIfOwned;
    private boolean ownershipCaptureRequiresOwner;
    private boolean ownershipSpawnRequiresOwner;
    private boolean ownershipInteractionRequiresOwner = true;
    private boolean ownershipLinkingRequiresOwner = true;
    private String interactionConfigParam = "InteractionConfigId";
    private String lovedItemsParam = "LovedItems";
    private String isHarvestableParam = "IsHarvestable";
    private String isMountableParam = "IsMountable";
    private String harvestContextParam = "HarvestInteractionContext";
    private String harvestAlarmName = "Harvest_Ready";
    private String interactionCooldownAlarmPrefix = "TameworkInteract_Cooldown";
    private double commandReturnHomeTeleportDistance = 96.0;
    private double commandReturnHomePathDistanceBeforeTeleport = 24.0;
    private int commandReturnHomeTeleportDelayMs = 2500;
    private double commandRecallSafeSpawnDistance = 20.0;
    private double commandRecallForceRelocateDistance = 80.0;
    private int commandRelocationRetryIntervalMs = 2000;
    private int commandRelocationMaxWaitMs = 120000;
    private int commandRelocationMaxRetryAttempts = 60;
    private boolean commandDeadRespawnEnabled;
    private int commandDeadRespawnCooldownMs = 60000;
    private int commandDeadRespawnFollowRetryDelayMs = 1250;
    private double commandDeadRespawnDistanceClose = 5.0;
    private double commandDeadRespawnDistanceNear = 8.0;
    private double commandDeadRespawnDistanceMid = 12.0;
    private double commandDeadRespawnDistanceFar = 16.0;
    private double commandPlacementMinRelativeY = -2.0;
    private double commandPlacementMaxRelativeY = 4.0;
    private boolean commandLinkedPanelRequireUnlinkConfirm = true;
    private boolean tranquilizerShortbowAssetSetEnabled;
    private boolean tranquilizerArrowAssetSetEnabled;
    private boolean tranquilizerPotionAssetSetEnabled;
    private boolean feedTroughAssetSetEnabled;
    private int populationLimitPerPlayerOwnedTotal;
    private PerPlayerLimitScope populationPerPlayerLimitScope = PerPlayerLimitScope.PER_WORLD;
    private boolean simpleClaimsEnabled;
    private int simpleClaimsBreedingLimitPerClaimChunk;
    private int simpleClaimsBreedingLimitPerClaimTotal;
    private boolean simpleClaimsBreedingRequiresClaim;
    private boolean simpleClaimsDamageProtectTamedFromNonMembers;
    private String simpleClaimsDamageAllowDamagePermissionKey = DEFAULT_SIMPLE_CLAIMS_DAMAGE_ALLOW_DAMAGE_PERMISSION_KEY;
    private boolean simpleClaimsSectionDefined;

    public static AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwGlobalConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwGlobalConfig> getAssetMap() {
        AssetStore<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwGlobalConfig> assetMap = (DefaultAssetMap<String, TwGlobalConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    // Clears the cached active global config.
    public static void clearCache() {
        INHERITANCE_CACHE_DIRTY = true;
        CACHE_DIRTY = true;
    }

    // Returns the active global config (or defaults if none exist).
    public static TwGlobalConfig resolveActive() {
        DefaultAssetMap<String, TwGlobalConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return defaultConfig();
        }
        TwGlobalConfig cached = ACTIVE_CONFIG;
        if (CACHE_DIRTY || cached == null) {
            synchronized (CACHE_LOCK) {
                if (CACHE_DIRTY || ACTIVE_CONFIG == null) {
                    ACTIVE_CONFIG = selectBest(assetMap);
                    CACHE_DIRTY = false;
                }
                cached = ACTIVE_CONFIG;
            }
        }
        return cached != null ? cached : defaultConfig();
    }

    /**
     * Resolves the best config source for SimpleClaims integration settings.
     *
     * <p>Unlike {@link #resolveActive()}, this skips unrelated global configs that do not define a
     * {@code SimpleClaims} section, so sectionless feature-gate configs cannot suppress claim limits.
     */
    @Nonnull
    public static TwGlobalConfig resolveSimpleClaimsSettingsConfig() {
        DefaultAssetMap<String, TwGlobalConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return resolveActive();
        }
        TwGlobalConfig best = selectBestSimpleClaimsCandidate(assetMap.getAssetMap().values());
        return best != null ? best : resolveActive();
    }

    // Builds a default global config without relying on asset store state.
    public static TwGlobalConfig defaultConfig() {
        return new TwGlobalConfig();
    }

    // Resolves feature gates by OR-ing enabled values across all enabled global configs.
    @Nonnull
    public static AssetSetToggles resolveEnabledAssetSets() {
        DefaultAssetMap<String, TwGlobalConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return AssetSetToggles.disabled();
        }
        boolean tranquilizerShortbowEnabled = false;
        boolean tranquilizerArrowEnabled = false;
        boolean tranquilizerPotionEnabled = false;
        boolean feedTroughEnabled = false;
        for (TwGlobalConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            if (candidate.isTranquilizerShortbowAssetSetEnabled()) {
                tranquilizerShortbowEnabled = true;
            }
            if (candidate.isTranquilizerArrowAssetSetEnabled()) {
                tranquilizerArrowEnabled = true;
            }
            if (candidate.isTranquilizerPotionAssetSetEnabled()) {
                tranquilizerPotionEnabled = true;
            }
            if (candidate.isFeedTroughAssetSetEnabled()) {
                feedTroughEnabled = true;
            }
            if (tranquilizerShortbowEnabled
                    && tranquilizerArrowEnabled
                    && tranquilizerPotionEnabled
                    && feedTroughEnabled) {
                break;
            }
        }
        return new AssetSetToggles(
                tranquilizerShortbowEnabled,
                tranquilizerArrowEnabled,
                tranquilizerPotionEnabled,
                feedTroughEnabled
        );
    }

    private static TwGlobalConfig selectBest(DefaultAssetMap<String, TwGlobalConfig> assetMap) {
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwGlobalConfig best = null;
        int bestPriority = Integer.MIN_VALUE;
        String bestId = null;
        for (TwGlobalConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            int candidatePriority = candidate.getPriority();
            if (best == null || candidatePriority > bestPriority) {
                best = candidate;
                bestPriority = candidatePriority;
                bestId = candidate.getId();
                continue;
            }
            if (candidatePriority == bestPriority) {
                String candidateId = candidate.getId();
                if (compareIds(candidateId, bestId) < 0) {
                    best = candidate;
                    bestId = candidateId;
                }
            }
        }
        return best;
    }

    @Nullable
    static TwGlobalConfig selectBestSimpleClaimsCandidate(@Nullable Iterable<TwGlobalConfig> candidates) {
        if (candidates == null) {
            return null;
        }
        TwGlobalConfig best = null;
        int bestPriority = Integer.MIN_VALUE;
        String bestId = null;
        for (TwGlobalConfig candidate : candidates) {
            if (candidate == null || !candidate.isEnabled() || !candidate.hasSimpleClaimsSectionDefined()) {
                continue;
            }
            int candidatePriority = candidate.getPriority();
            if (best == null || candidatePriority > bestPriority) {
                best = candidate;
                bestPriority = candidatePriority;
                bestId = candidate.getId();
                continue;
            }
            if (candidatePriority != bestPriority) {
                continue;
            }
            // For equal-priority SimpleClaims configs, prefer the one that explicitly enables integration.
            if (candidate.isSimpleClaimsEnabled() != best.isSimpleClaimsEnabled()) {
                if (candidate.isSimpleClaimsEnabled()) {
                    best = candidate;
                    bestId = candidate.getId();
                }
                continue;
            }
            String candidateId = candidate.getId();
            if (compareIds(candidateId, bestId) < 0) {
                best = candidate;
                bestId = candidateId;
            }
        }
        return best;
    }

    private static int compareIds(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwGlobalConfig> assetMap) {
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

    protected TwGlobalConfig() {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwGlobalConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwGlobalConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        inheritGeneralSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritOwnershipProtectionSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritOwnershipRequirementsSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritInteractionDefaultsSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritCommandSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritAssetSetsSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritPopulationSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
        inheritSimpleClaimsSection(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    @Nullable
    private static TameworkSettingsStore.GlobalOverrides resolveRuntimeOverrides() {
        return TameworkSettingsStore.loadRuntimeGlobalOverrides();
    }

    public boolean isBlockOwnerDamage() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.blockOwnerDamage() != null) {
            return overrides.blockOwnerDamage();
        }
        return blockOwnerDamage;
    }

    public boolean isBlockAllPlayerDamageIfOwned() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.blockAllPlayerDamageIfOwned() != null) {
            return overrides.blockAllPlayerDamageIfOwned();
        }
        return blockAllPlayerDamageIfOwned;
    }

    public boolean isInvulnerableIfOwned() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.invulnerableIfOwned() != null) {
            return overrides.invulnerableIfOwned();
        }
        return invulnerableIfOwned;
    }

    public boolean isOwnershipCaptureRequiresOwner() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.captureRequiresOwner() != null) {
            return overrides.captureRequiresOwner();
        }
        return ownershipCaptureRequiresOwner;
    }

    public boolean isOwnershipSpawnRequiresOwner() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.spawnRequiresOwner() != null) {
            return overrides.spawnRequiresOwner();
        }
        return ownershipSpawnRequiresOwner;
    }

    public boolean isOwnershipInteractionRequiresOwner() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.interactionRequiresOwner() != null) {
            return overrides.interactionRequiresOwner();
        }
        return ownershipInteractionRequiresOwner;
    }

    public boolean isOwnershipLinkingRequiresOwner() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.linkingRequiresOwner() != null) {
            return overrides.linkingRequiresOwner();
        }
        return ownershipLinkingRequiresOwner;
    }

    public String getInteractionConfigParam() {
        return interactionConfigParam;
    }

    public String getLovedItemsParam() {
        return lovedItemsParam;
    }

    public String getIsHarvestableParam() {
        return isHarvestableParam;
    }

    public String getIsMountableParam() {
        return isMountableParam;
    }

    public String getHarvestContextParam() {
        return harvestContextParam;
    }

    public String getHarvestAlarmName() {
        return harvestAlarmName;
    }

    public String getInteractionCooldownAlarmPrefix() {
        return interactionCooldownAlarmPrefix;
    }

    public double getCommandReturnHomeTeleportDistance() {
        return commandReturnHomeTeleportDistance;
    }

    public double getCommandReturnHomePathDistanceBeforeTeleport() {
        return commandReturnHomePathDistanceBeforeTeleport;
    }

    public int getCommandReturnHomeTeleportDelayMs() {
        return commandReturnHomeTeleportDelayMs;
    }

    public double getCommandRecallSafeSpawnDistance() {
        return commandRecallSafeSpawnDistance;
    }

    public double getCommandRecallForceRelocateDistance() {
        return commandRecallForceRelocateDistance;
    }

    public int getCommandRelocationRetryIntervalMs() {
        return commandRelocationRetryIntervalMs;
    }

    public int getCommandRelocationMaxWaitMs() {
        return commandRelocationMaxWaitMs;
    }

    public int getCommandRelocationMaxRetryAttempts() {
        return commandRelocationMaxRetryAttempts;
    }

    public boolean isCommandDeadRespawnEnabled() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.reviveSystemEnabled() != null) {
            return overrides.reviveSystemEnabled();
        }
        return commandDeadRespawnEnabled;
    }

    public int getCommandDeadRespawnCooldownMs() {
        return commandDeadRespawnCooldownMs;
    }

    public int getCommandDeadRespawnFollowRetryDelayMs() {
        return commandDeadRespawnFollowRetryDelayMs;
    }

    public double getCommandDeadRespawnDistanceClose() {
        return commandDeadRespawnDistanceClose;
    }

    public double getCommandDeadRespawnDistanceNear() {
        return commandDeadRespawnDistanceNear;
    }

    public double getCommandDeadRespawnDistanceMid() {
        return commandDeadRespawnDistanceMid;
    }

    public double getCommandDeadRespawnDistanceFar() {
        return commandDeadRespawnDistanceFar;
    }

    public double getCommandPlacementMinRelativeY() {
        return commandPlacementMinRelativeY;
    }

    public double getCommandPlacementMaxRelativeY() {
        return commandPlacementMaxRelativeY;
    }

    public boolean isCommandLinkedPanelRequireUnlinkConfirm() {
        return commandLinkedPanelRequireUnlinkConfirm;
    }

    public boolean isTranquilizerShortbowAssetSetEnabled() {
        return tranquilizerShortbowAssetSetEnabled;
    }

    public boolean isTranquilizerArrowAssetSetEnabled() {
        return tranquilizerArrowAssetSetEnabled;
    }

    public boolean isTranquilizerPotionAssetSetEnabled() {
        return tranquilizerPotionAssetSetEnabled;
    }

    public boolean isFeedTroughAssetSetEnabled() {
        return feedTroughAssetSetEnabled;
    }

    public int getPopulationLimitPerPlayerOwnedTotal() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.populationLimitPerPlayerOwnedTotal() != null) {
            return Math.max(0, overrides.populationLimitPerPlayerOwnedTotal());
        }
        return Math.max(0, populationLimitPerPlayerOwnedTotal);
    }

    @Nonnull
    public PerPlayerLimitScope getPopulationPerPlayerLimitScope() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.populationPerPlayerLimitScope() != null) {
            return PerPlayerLimitScope.fromConfigValue(overrides.populationPerPlayerLimitScope());
        }
        if (populationPerPlayerLimitScope == null) {
            return PerPlayerLimitScope.PER_WORLD;
        }
        return populationPerPlayerLimitScope;
    }

    public boolean isSimpleClaimsEnabled() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.simpleClaimsEnabled() != null) {
            return overrides.simpleClaimsEnabled();
        }
        return simpleClaimsEnabled;
    }

    public int getSimpleClaimsBreedingLimitPerClaimChunk() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.simpleClaimsLimitPerClaimChunk() != null) {
            return Math.max(0, overrides.simpleClaimsLimitPerClaimChunk());
        }
        return Math.max(0, simpleClaimsBreedingLimitPerClaimChunk);
    }

    public int getSimpleClaimsBreedingLimitPerClaimTotal() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.simpleClaimsLimitPerClaimTotal() != null) {
            return Math.max(0, overrides.simpleClaimsLimitPerClaimTotal());
        }
        return Math.max(0, simpleClaimsBreedingLimitPerClaimTotal);
    }

    public boolean isSimpleClaimsBreedingRequiresClaim() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.simpleClaimsBreedingRequiresClaim() != null) {
            return overrides.simpleClaimsBreedingRequiresClaim();
        }
        return simpleClaimsBreedingRequiresClaim;
    }

    public boolean isSimpleClaimsDamageProtectTamedFromNonMembers() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null && overrides.simpleClaimsProtectTamedFromNonMembers() != null) {
            return overrides.simpleClaimsProtectTamedFromNonMembers();
        }
        return simpleClaimsDamageProtectTamedFromNonMembers;
    }

    @Nonnull
    public String getSimpleClaimsDamageAllowDamagePermissionKey() {
        if (simpleClaimsDamageAllowDamagePermissionKey == null
                || simpleClaimsDamageAllowDamagePermissionKey.isBlank()) {
            return DEFAULT_SIMPLE_CLAIMS_DAMAGE_ALLOW_DAMAGE_PERMISSION_KEY;
        }
        return simpleClaimsDamageAllowDamagePermissionKey.trim();
    }

    public boolean hasSimpleClaimsSectionDefined() {
        TameworkSettingsStore.GlobalOverrides overrides = resolveRuntimeOverrides();
        if (overrides != null
                && (overrides.simpleClaimsEnabled() != null
                || overrides.simpleClaimsLimitPerClaimChunk() != null
                || overrides.simpleClaimsLimitPerClaimTotal() != null
                || overrides.simpleClaimsBreedingRequiresClaim() != null
                || overrides.simpleClaimsProtectTamedFromNonMembers() != null)) {
            return true;
        }
        return simpleClaimsSectionDefined;
    }

    public int resolveSimpleClaimsBreedingLimitPerClaimChunkCap(int claimChunkCount) {
        int perChunk = getSimpleClaimsBreedingLimitPerClaimChunk();
        if (perChunk <= 0) {
            return 0;
        }
        int safeChunkCount = Math.max(0, claimChunkCount);
        long multiplied = (long) perChunk * (long) safeChunkCount;
        if (multiplied > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) multiplied;
    }

    private void applyGeneralSection(@Nullable GeneralSection section) {
        if (section == null) {
            return;
        }
        if (section.enabled != null) {
            enabled = section.enabled;
        }
        if (section.priority != null) {
            priority = section.priority;
        }
    }

    private GeneralSection toGeneralSection() {
        GeneralSection section = new GeneralSection();
        section.enabled = enabled;
        section.priority = priority;
        return section;
    }

    private void applyOwnershipProtectionSection(@Nullable OwnershipProtectionSection section) {
        if (section == null) {
            return;
        }
        if (section.blockOwnerDamage != null) {
            blockOwnerDamage = section.blockOwnerDamage;
        }
        if (section.blockAllPlayerDamageIfOwned != null) {
            blockAllPlayerDamageIfOwned = section.blockAllPlayerDamageIfOwned;
        }
        if (section.invulnerableIfOwned != null) {
            invulnerableIfOwned = section.invulnerableIfOwned;
        }
    }

    private OwnershipProtectionSection toOwnershipProtectionSection() {
        OwnershipProtectionSection section = new OwnershipProtectionSection();
        section.blockOwnerDamage = blockOwnerDamage;
        section.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
        section.invulnerableIfOwned = invulnerableIfOwned;
        return section;
    }

    private void applyOwnershipRequirementsSection(@Nullable OwnershipRequirementsSection section) {
        if (section == null) {
            return;
        }
        if (section.captureRequiresOwner != null) {
            ownershipCaptureRequiresOwner = section.captureRequiresOwner;
        }
        if (section.spawnRequiresOwner != null) {
            ownershipSpawnRequiresOwner = section.spawnRequiresOwner;
        }
        if (section.interactionRequiresOwner != null) {
            ownershipInteractionRequiresOwner = section.interactionRequiresOwner;
        }
        if (section.linkingRequiresOwner != null) {
            ownershipLinkingRequiresOwner = section.linkingRequiresOwner;
        }
    }

    private OwnershipRequirementsSection toOwnershipRequirementsSection() {
        OwnershipRequirementsSection section = new OwnershipRequirementsSection();
        section.captureRequiresOwner = ownershipCaptureRequiresOwner;
        section.spawnRequiresOwner = ownershipSpawnRequiresOwner;
        section.interactionRequiresOwner = ownershipInteractionRequiresOwner;
        section.linkingRequiresOwner = ownershipLinkingRequiresOwner;
        return section;
    }

    private void applyInteractionDefaultsSection(@Nullable InteractionDefaultsSection section) {
        if (section == null) {
            return;
        }
        if (section.interactionConfigParam != null) {
            interactionConfigParam = section.interactionConfigParam;
        }
        if (section.lovedItemsParam != null) {
            lovedItemsParam = section.lovedItemsParam;
        }
        if (section.isHarvestableParam != null) {
            isHarvestableParam = section.isHarvestableParam;
        }
        if (section.isMountableParam != null) {
            isMountableParam = section.isMountableParam;
        }
        if (section.harvestContextParam != null) {
            harvestContextParam = section.harvestContextParam;
        }
        if (section.harvestAlarmName != null) {
            harvestAlarmName = section.harvestAlarmName;
        }
        if (section.interactionCooldownAlarmPrefix != null) {
            interactionCooldownAlarmPrefix = section.interactionCooldownAlarmPrefix;
        }
    }

    private InteractionDefaultsSection toInteractionDefaultsSection() {
        InteractionDefaultsSection section = new InteractionDefaultsSection();
        section.interactionConfigParam = interactionConfigParam;
        section.lovedItemsParam = lovedItemsParam;
        section.isHarvestableParam = isHarvestableParam;
        section.isMountableParam = isMountableParam;
        section.harvestContextParam = harvestContextParam;
        section.harvestAlarmName = harvestAlarmName;
        section.interactionCooldownAlarmPrefix = interactionCooldownAlarmPrefix;
        return section;
    }

    private void applyCommandSection(@Nullable CommandSection section) {
        if (section == null) {
            return;
        }
        if (section.returnHomeTeleportDistance != null) {
            commandReturnHomeTeleportDistance = section.returnHomeTeleportDistance;
        }
        if (section.returnHomePathDistanceBeforeTeleport != null) {
            commandReturnHomePathDistanceBeforeTeleport = section.returnHomePathDistanceBeforeTeleport;
        }
        if (section.returnHomeTeleportDelayMs != null) {
            commandReturnHomeTeleportDelayMs = section.returnHomeTeleportDelayMs;
        }
        if (section.recallSafeSpawnDistance != null) {
            commandRecallSafeSpawnDistance = section.recallSafeSpawnDistance;
        }
        if (section.recallForceRelocateDistance != null) {
            commandRecallForceRelocateDistance = section.recallForceRelocateDistance;
        }
        if (section.relocationRetryIntervalMs != null) {
            commandRelocationRetryIntervalMs = section.relocationRetryIntervalMs;
        }
        if (section.relocationMaxWaitMs != null) {
            commandRelocationMaxWaitMs = section.relocationMaxWaitMs;
        }
        if (section.relocationMaxRetryAttempts != null) {
            commandRelocationMaxRetryAttempts = section.relocationMaxRetryAttempts;
        }
        if (section.deadRespawnEnabled != null) {
            commandDeadRespawnEnabled = section.deadRespawnEnabled;
        }
        if (section.deadRespawnCooldownMins != null) {
            commandDeadRespawnCooldownMs = minutesToMillis(
                    section.deadRespawnCooldownMins,
                    commandDeadRespawnCooldownMs
            );
        } else if (section.deadRespawnCooldownMs != null) {
            commandDeadRespawnCooldownMs = section.deadRespawnCooldownMs;
        }
        if (section.deadRespawnFollowRetryDelayMs != null) {
            commandDeadRespawnFollowRetryDelayMs = section.deadRespawnFollowRetryDelayMs;
        }
        if (section.deadRespawnDistanceClose != null) {
            commandDeadRespawnDistanceClose = section.deadRespawnDistanceClose;
        }
        if (section.deadRespawnDistanceNear != null) {
            commandDeadRespawnDistanceNear = section.deadRespawnDistanceNear;
        }
        if (section.deadRespawnDistanceMid != null) {
            commandDeadRespawnDistanceMid = section.deadRespawnDistanceMid;
        }
        if (section.deadRespawnDistanceFar != null) {
            commandDeadRespawnDistanceFar = section.deadRespawnDistanceFar;
        }
        if (section.placementMinRelativeY != null) {
            commandPlacementMinRelativeY = section.placementMinRelativeY;
        }
        if (section.placementMaxRelativeY != null) {
            commandPlacementMaxRelativeY = section.placementMaxRelativeY;
        }
        if (section.linkedPanelRequireUnlinkConfirm != null) {
            commandLinkedPanelRequireUnlinkConfirm = section.linkedPanelRequireUnlinkConfirm;
        }
    }

    private CommandSection toCommandSection() {
        CommandSection section = new CommandSection();
        section.returnHomeTeleportDistance = commandReturnHomeTeleportDistance;
        section.returnHomePathDistanceBeforeTeleport = commandReturnHomePathDistanceBeforeTeleport;
        section.returnHomeTeleportDelayMs = commandReturnHomeTeleportDelayMs;
        section.recallSafeSpawnDistance = commandRecallSafeSpawnDistance;
        section.recallForceRelocateDistance = commandRecallForceRelocateDistance;
        section.relocationRetryIntervalMs = commandRelocationRetryIntervalMs;
        section.relocationMaxWaitMs = commandRelocationMaxWaitMs;
        section.relocationMaxRetryAttempts = commandRelocationMaxRetryAttempts;
        section.deadRespawnEnabled = commandDeadRespawnEnabled;
        section.deadRespawnCooldownMs = commandDeadRespawnCooldownMs;
        section.deadRespawnFollowRetryDelayMs = commandDeadRespawnFollowRetryDelayMs;
        section.deadRespawnDistanceClose = commandDeadRespawnDistanceClose;
        section.deadRespawnDistanceNear = commandDeadRespawnDistanceNear;
        section.deadRespawnDistanceMid = commandDeadRespawnDistanceMid;
        section.deadRespawnDistanceFar = commandDeadRespawnDistanceFar;
        section.placementMinRelativeY = commandPlacementMinRelativeY;
        section.placementMaxRelativeY = commandPlacementMaxRelativeY;
        section.linkedPanelRequireUnlinkConfirm = commandLinkedPanelRequireUnlinkConfirm;
        return section;
    }

    private void applyAssetSetsSection(@Nullable AssetSetsSection section) {
        if (section == null) {
            return;
        }
        if (section.tranquilizerShortbow != null) {
            tranquilizerShortbowAssetSetEnabled = section.tranquilizerShortbow;
        }
        if (section.tranquilizerArrow != null) {
            tranquilizerArrowAssetSetEnabled = section.tranquilizerArrow;
        }
        if (section.tranquilizerPotion != null) {
            tranquilizerPotionAssetSetEnabled = section.tranquilizerPotion;
        }
        if (section.feedTrough != null) {
            feedTroughAssetSetEnabled = section.feedTrough;
        }
    }

    private AssetSetsSection toAssetSetsSection() {
        AssetSetsSection section = new AssetSetsSection();
        section.tranquilizerShortbow = tranquilizerShortbowAssetSetEnabled;
        section.tranquilizerArrow = tranquilizerArrowAssetSetEnabled;
        section.tranquilizerPotion = tranquilizerPotionAssetSetEnabled;
        section.feedTrough = feedTroughAssetSetEnabled;
        return section;
    }

    private void applyPopulationSection(@Nullable PopulationSection section) {
        if (section == null) {
            return;
        }
        if (section.limitPerPlayerOwnedTotal != null) {
            populationLimitPerPlayerOwnedTotal = section.limitPerPlayerOwnedTotal;
        }
        if (section.perPlayerLimitScope != null) {
            populationPerPlayerLimitScope = PerPlayerLimitScope.fromConfigValue(section.perPlayerLimitScope);
        }
    }

    private PopulationSection toPopulationSection() {
        PopulationSection section = new PopulationSection();
        section.limitPerPlayerOwnedTotal = populationLimitPerPlayerOwnedTotal;
        section.perPlayerLimitScope = getPopulationPerPlayerLimitScope().configValue();
        return section;
    }

    private void applySimpleClaimsSection(@Nullable SimpleClaimsSection section) {
        if (section == null) {
            return;
        }
        simpleClaimsSectionDefined = true;
        if (section.simpleClaimsEnabled != null) {
            simpleClaimsEnabled = section.simpleClaimsEnabled;
        }
        if (section.breeding != null) {
            SimpleClaimsBreedingSection breeding = section.breeding;
            if (breeding.limitPerClaimChunk != null) {
                simpleClaimsBreedingLimitPerClaimChunk = breeding.limitPerClaimChunk;
            }
            if (breeding.limitPerClaimTotal != null) {
                simpleClaimsBreedingLimitPerClaimTotal = breeding.limitPerClaimTotal;
            }
            if (breeding.breedingRequiresClaim != null) {
                simpleClaimsBreedingRequiresClaim = breeding.breedingRequiresClaim;
            }
        }
        if (section.damage != null) {
            SimpleClaimsDamageSection damage = section.damage;
            if (damage.protectTamedFromNonMembers != null) {
                simpleClaimsDamageProtectTamedFromNonMembers = damage.protectTamedFromNonMembers;
            }
            if (damage.allowDamagePermissionKey != null) {
                simpleClaimsDamageAllowDamagePermissionKey = damage.allowDamagePermissionKey;
            }
        }
    }

    private SimpleClaimsSection toSimpleClaimsSection() {
        SimpleClaimsSection section = new SimpleClaimsSection();
        section.simpleClaimsEnabled = simpleClaimsEnabled;
        section.breeding = new SimpleClaimsBreedingSection();
        section.breeding.limitPerClaimChunk = simpleClaimsBreedingLimitPerClaimChunk;
        section.breeding.limitPerClaimTotal = simpleClaimsBreedingLimitPerClaimTotal;
        section.breeding.breedingRequiresClaim = simpleClaimsBreedingRequiresClaim;
        section.damage = new SimpleClaimsDamageSection();
        section.damage.protectTamedFromNonMembers = simpleClaimsDamageProtectTamedFromNonMembers;
        section.damage.allowDamagePermissionKey = getSimpleClaimsDamageAllowDamagePermissionKey();
        return section;
    }

    // Returns the names of required string fields that are missing or blank.
    public String[] listMissingRequiredFields() {
        List<String> missing = new ArrayList<>();
        collectMissing(missing, "InteractionConfigParam", interactionConfigParam);
        collectMissing(missing, "LovedItemsParam", lovedItemsParam);
        collectMissing(missing, "IsHarvestableParam", isHarvestableParam);
        collectMissing(missing, "IsMountableParam", isMountableParam);
        collectMissing(missing, "HarvestContextParam", harvestContextParam);
        collectMissing(missing, "HarvestAlarmName", harvestAlarmName);
        collectMissing(missing, "InteractionCooldownAlarmPrefix", interactionCooldownAlarmPrefix);
        return missing.isEmpty() ? new String[0] : missing.toArray(new String[0]);
    }

    private void collectMissing(List<String> missing, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            missing.add(fieldName);
        }
    }

    private void inheritGeneralSection(@Nonnull TwGlobalConfig parent,
                                       @Nonnull Set<String> explicitTopLevelKeys,
                                       @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("General")) {
            enabled = parent.enabled;
            priority = parent.priority;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("General");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("Enabled")) {
            enabled = parent.enabled;
        }
        if (!nestedExplicit.contains("Priority")) {
            priority = parent.priority;
        }
    }

    private void inheritOwnershipProtectionSection(@Nonnull TwGlobalConfig parent,
                                                   @Nonnull Set<String> explicitTopLevelKeys,
                                                   @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("OwnershipProtection")) {
            blockOwnerDamage = parent.blockOwnerDamage;
            blockAllPlayerDamageIfOwned = parent.blockAllPlayerDamageIfOwned;
            invulnerableIfOwned = parent.invulnerableIfOwned;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("OwnershipProtection");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("BlockOwnerDamage")) {
            blockOwnerDamage = parent.blockOwnerDamage;
        }
        if (!nestedExplicit.contains("BlockAllPlayerDamageIfOwned")) {
            blockAllPlayerDamageIfOwned = parent.blockAllPlayerDamageIfOwned;
        }
        if (!nestedExplicit.contains("InvulnerableIfOwned")) {
            invulnerableIfOwned = parent.invulnerableIfOwned;
        }
    }

    private void inheritOwnershipRequirementsSection(@Nonnull TwGlobalConfig parent,
                                                     @Nonnull Set<String> explicitTopLevelKeys,
                                                     @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("OwnershipRequirements")) {
            ownershipCaptureRequiresOwner = parent.ownershipCaptureRequiresOwner;
            ownershipSpawnRequiresOwner = parent.ownershipSpawnRequiresOwner;
            ownershipInteractionRequiresOwner = parent.ownershipInteractionRequiresOwner;
            ownershipLinkingRequiresOwner = parent.ownershipLinkingRequiresOwner;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("OwnershipRequirements");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("CaptureRequiresOwner")) {
            ownershipCaptureRequiresOwner = parent.ownershipCaptureRequiresOwner;
        }
        if (!nestedExplicit.contains("SpawnRequiresOwner")) {
            ownershipSpawnRequiresOwner = parent.ownershipSpawnRequiresOwner;
        }
        if (!nestedExplicit.contains("InteractionRequiresOwner")) {
            ownershipInteractionRequiresOwner = parent.ownershipInteractionRequiresOwner;
        }
        if (!nestedExplicit.contains("LinkingRequiresOwner")) {
            ownershipLinkingRequiresOwner = parent.ownershipLinkingRequiresOwner;
        }
    }

    private void inheritInteractionDefaultsSection(@Nonnull TwGlobalConfig parent,
                                                   @Nonnull Set<String> explicitTopLevelKeys,
                                                   @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("InteractionDefaults")) {
            interactionConfigParam = parent.interactionConfigParam;
            lovedItemsParam = parent.lovedItemsParam;
            isHarvestableParam = parent.isHarvestableParam;
            isMountableParam = parent.isMountableParam;
            harvestContextParam = parent.harvestContextParam;
            harvestAlarmName = parent.harvestAlarmName;
            interactionCooldownAlarmPrefix = parent.interactionCooldownAlarmPrefix;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("InteractionDefaults");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("InteractionConfigParam")) {
            interactionConfigParam = parent.interactionConfigParam;
        }
        if (!nestedExplicit.contains("LovedItemsParam")) {
            lovedItemsParam = parent.lovedItemsParam;
        }
        if (!nestedExplicit.contains("IsHarvestableParam")) {
            isHarvestableParam = parent.isHarvestableParam;
        }
        if (!nestedExplicit.contains("IsMountableParam")) {
            isMountableParam = parent.isMountableParam;
        }
        if (!nestedExplicit.contains("HarvestContextParam")) {
            harvestContextParam = parent.harvestContextParam;
        }
        if (!nestedExplicit.contains("HarvestAlarmName")) {
            harvestAlarmName = parent.harvestAlarmName;
        }
        if (!nestedExplicit.contains("InteractionCooldownAlarmPrefix")) {
            interactionCooldownAlarmPrefix = parent.interactionCooldownAlarmPrefix;
        }
    }

    private void inheritCommandSection(@Nonnull TwGlobalConfig parent,
                                       @Nonnull Set<String> explicitTopLevelKeys,
                                       @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Command")) {
            commandReturnHomeTeleportDistance = parent.commandReturnHomeTeleportDistance;
            commandReturnHomePathDistanceBeforeTeleport = parent.commandReturnHomePathDistanceBeforeTeleport;
            commandReturnHomeTeleportDelayMs = parent.commandReturnHomeTeleportDelayMs;
            commandRecallSafeSpawnDistance = parent.commandRecallSafeSpawnDistance;
            commandRecallForceRelocateDistance = parent.commandRecallForceRelocateDistance;
            commandRelocationRetryIntervalMs = parent.commandRelocationRetryIntervalMs;
            commandRelocationMaxWaitMs = parent.commandRelocationMaxWaitMs;
            commandRelocationMaxRetryAttempts = parent.commandRelocationMaxRetryAttempts;
            commandDeadRespawnEnabled = parent.commandDeadRespawnEnabled;
            commandDeadRespawnCooldownMs = parent.commandDeadRespawnCooldownMs;
            commandDeadRespawnFollowRetryDelayMs = parent.commandDeadRespawnFollowRetryDelayMs;
            commandDeadRespawnDistanceClose = parent.commandDeadRespawnDistanceClose;
            commandDeadRespawnDistanceNear = parent.commandDeadRespawnDistanceNear;
            commandDeadRespawnDistanceMid = parent.commandDeadRespawnDistanceMid;
            commandDeadRespawnDistanceFar = parent.commandDeadRespawnDistanceFar;
            commandPlacementMinRelativeY = parent.commandPlacementMinRelativeY;
            commandPlacementMaxRelativeY = parent.commandPlacementMaxRelativeY;
            commandLinkedPanelRequireUnlinkConfirm = parent.commandLinkedPanelRequireUnlinkConfirm;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("Command");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("ReturnHomeTeleportDistance")) {
            commandReturnHomeTeleportDistance = parent.commandReturnHomeTeleportDistance;
        }
        if (!nestedExplicit.contains("ReturnHomePathDistanceBeforeTeleport")) {
            commandReturnHomePathDistanceBeforeTeleport = parent.commandReturnHomePathDistanceBeforeTeleport;
        }
        if (!nestedExplicit.contains("ReturnHomeTeleportDelayMs")) {
            commandReturnHomeTeleportDelayMs = parent.commandReturnHomeTeleportDelayMs;
        }
        if (!nestedExplicit.contains("RecallSafeSpawnDistance")) {
            commandRecallSafeSpawnDistance = parent.commandRecallSafeSpawnDistance;
        }
        if (!nestedExplicit.contains("RecallForceRelocateDistance")) {
            commandRecallForceRelocateDistance = parent.commandRecallForceRelocateDistance;
        }
        if (!nestedExplicit.contains("RelocationRetryIntervalMs")) {
            commandRelocationRetryIntervalMs = parent.commandRelocationRetryIntervalMs;
        }
        if (!nestedExplicit.contains("RelocationMaxWaitMs")) {
            commandRelocationMaxWaitMs = parent.commandRelocationMaxWaitMs;
        }
        if (!nestedExplicit.contains("RelocationMaxRetryAttempts")) {
            commandRelocationMaxRetryAttempts = parent.commandRelocationMaxRetryAttempts;
        }
        if (!nestedExplicit.contains("DeadRespawnEnabled")) {
            commandDeadRespawnEnabled = parent.commandDeadRespawnEnabled;
        }
        if (!hasDeadRespawnCooldownOverride(nestedExplicit)) {
            commandDeadRespawnCooldownMs = parent.commandDeadRespawnCooldownMs;
        }
        if (!nestedExplicit.contains("DeadRespawnFollowRetryDelayMs")) {
            commandDeadRespawnFollowRetryDelayMs = parent.commandDeadRespawnFollowRetryDelayMs;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceClose")) {
            commandDeadRespawnDistanceClose = parent.commandDeadRespawnDistanceClose;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceNear")) {
            commandDeadRespawnDistanceNear = parent.commandDeadRespawnDistanceNear;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceMid")) {
            commandDeadRespawnDistanceMid = parent.commandDeadRespawnDistanceMid;
        }
        if (!nestedExplicit.contains("DeadRespawnDistanceFar")) {
            commandDeadRespawnDistanceFar = parent.commandDeadRespawnDistanceFar;
        }
        if (!nestedExplicit.contains("PlacementMinRelativeY")) {
            commandPlacementMinRelativeY = parent.commandPlacementMinRelativeY;
        }
        if (!nestedExplicit.contains("PlacementMaxRelativeY")) {
            commandPlacementMaxRelativeY = parent.commandPlacementMaxRelativeY;
        }
        if (!nestedExplicit.contains("LinkedPanelRequireUnlinkConfirm")) {
            commandLinkedPanelRequireUnlinkConfirm = parent.commandLinkedPanelRequireUnlinkConfirm;
        }
    }

    private void inheritAssetSetsSection(@Nonnull TwGlobalConfig parent,
                                         @Nonnull Set<String> explicitTopLevelKeys,
                                         @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("AssetSets")) {
            tranquilizerShortbowAssetSetEnabled = parent.tranquilizerShortbowAssetSetEnabled;
            tranquilizerArrowAssetSetEnabled = parent.tranquilizerArrowAssetSetEnabled;
            tranquilizerPotionAssetSetEnabled = parent.tranquilizerPotionAssetSetEnabled;
            feedTroughAssetSetEnabled = parent.feedTroughAssetSetEnabled;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("AssetSets");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("TranquilizerShortbow")) {
            tranquilizerShortbowAssetSetEnabled = parent.tranquilizerShortbowAssetSetEnabled;
        }
        if (!nestedExplicit.contains("TranquilizerArrow")) {
            tranquilizerArrowAssetSetEnabled = parent.tranquilizerArrowAssetSetEnabled;
        }
        if (!nestedExplicit.contains("TranquilizerPotion")) {
            tranquilizerPotionAssetSetEnabled = parent.tranquilizerPotionAssetSetEnabled;
        }
        if (!nestedExplicit.contains("FeedTrough")) {
            feedTroughAssetSetEnabled = parent.feedTroughAssetSetEnabled;
        }
    }

    private void inheritPopulationSection(@Nonnull TwGlobalConfig parent,
                                          @Nonnull Set<String> explicitTopLevelKeys,
                                          @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Population")) {
            populationLimitPerPlayerOwnedTotal = parent.populationLimitPerPlayerOwnedTotal;
            populationPerPlayerLimitScope = parent.populationPerPlayerLimitScope;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("Population");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("LimitPerPlayerOwnedTotal")) {
            populationLimitPerPlayerOwnedTotal = parent.populationLimitPerPlayerOwnedTotal;
        }
        if (!nestedExplicit.contains("PerPlayerLimitScope")) {
            populationPerPlayerLimitScope = parent.populationPerPlayerLimitScope;
        }
    }

    private void inheritSimpleClaimsSection(@Nonnull TwGlobalConfig parent,
                                            @Nonnull Set<String> explicitTopLevelKeys,
                                            @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("SimpleClaims")) {
            simpleClaimsEnabled = parent.simpleClaimsEnabled;
            simpleClaimsBreedingLimitPerClaimChunk = parent.simpleClaimsBreedingLimitPerClaimChunk;
            simpleClaimsBreedingLimitPerClaimTotal = parent.simpleClaimsBreedingLimitPerClaimTotal;
            simpleClaimsBreedingRequiresClaim = parent.simpleClaimsBreedingRequiresClaim;
            simpleClaimsDamageProtectTamedFromNonMembers = parent.simpleClaimsDamageProtectTamedFromNonMembers;
            simpleClaimsDamageAllowDamagePermissionKey = parent.simpleClaimsDamageAllowDamagePermissionKey;
            simpleClaimsSectionDefined = parent.simpleClaimsSectionDefined;
            return;
        }
        Set<String> nestedExplicit = explicitNestedKeysByTopLevel == null
                ? null
                : explicitNestedKeysByTopLevel.get("SimpleClaims");
        if (nestedExplicit == null) {
            return;
        }
        if (!nestedExplicit.contains("SimpleClaimsEnabled")) {
            simpleClaimsEnabled = parent.simpleClaimsEnabled;
        }
        if (!nestedExplicit.contains("Breeding")) {
            simpleClaimsBreedingLimitPerClaimChunk = parent.simpleClaimsBreedingLimitPerClaimChunk;
            simpleClaimsBreedingLimitPerClaimTotal = parent.simpleClaimsBreedingLimitPerClaimTotal;
            simpleClaimsBreedingRequiresClaim = parent.simpleClaimsBreedingRequiresClaim;
        } else {
            if (!isSimpleClaimsNestedFieldExplicit(nestedExplicit, "Breeding", "LimitPerClaimChunk")) {
                simpleClaimsBreedingLimitPerClaimChunk = parent.simpleClaimsBreedingLimitPerClaimChunk;
            }
            if (!isSimpleClaimsNestedFieldExplicit(nestedExplicit, "Breeding", "LimitPerClaimTotal")) {
                simpleClaimsBreedingLimitPerClaimTotal = parent.simpleClaimsBreedingLimitPerClaimTotal;
            }
            if (!isSimpleClaimsNestedFieldExplicit(nestedExplicit, "Breeding", "BreedingRequiresClaim")) {
                simpleClaimsBreedingRequiresClaim = parent.simpleClaimsBreedingRequiresClaim;
            }
        }
        if (!nestedExplicit.contains("Damage")) {
            simpleClaimsDamageProtectTamedFromNonMembers = parent.simpleClaimsDamageProtectTamedFromNonMembers;
            simpleClaimsDamageAllowDamagePermissionKey = parent.simpleClaimsDamageAllowDamagePermissionKey;
        } else {
            if (!isSimpleClaimsNestedFieldExplicit(nestedExplicit, "Damage", "ProtectTamedFromNonMembers")) {
                simpleClaimsDamageProtectTamedFromNonMembers = parent.simpleClaimsDamageProtectTamedFromNonMembers;
            }
            if (!isSimpleClaimsNestedFieldExplicit(nestedExplicit, "Damage", "AllowDamagePermissionKey")) {
                simpleClaimsDamageAllowDamagePermissionKey = parent.simpleClaimsDamageAllowDamagePermissionKey;
            }
        }
    }

    private static boolean isSimpleClaimsNestedFieldExplicit(@Nonnull Set<String> nestedExplicit,
                                                             @Nonnull String sectionName,
                                                             @Nonnull String fieldName) {
        String qualified = sectionName + "." + fieldName;
        if (nestedExplicit.contains(qualified)) {
            return true;
        }
        return !containsNestedPrefix(nestedExplicit, sectionName + ".");
    }

    private static boolean containsNestedPrefix(@Nonnull Set<String> values, @Nonnull String prefix) {
        for (String value : values) {
            if (value != null && value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeadRespawnCooldownOverride(@Nonnull Set<String> nestedExplicit) {
        return nestedExplicit.contains("DeadRespawnCooldownMs")
                || nestedExplicit.contains("DeadRespawnCooldownMins");
    }

    private static int minutesToMillis(double minutes, int fallbackMs) {
        if (!Double.isFinite(minutes) || minutes < 0) {
            return fallbackMs;
        }
        double millis = minutes * MILLIS_PER_MINUTE;
        if (millis >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(millis);
    }

    private static final class GeneralSection {
        private Boolean enabled;
        private Integer priority;
    }

    private static final class OwnershipProtectionSection {
        private Boolean blockOwnerDamage;
        private Boolean blockAllPlayerDamageIfOwned;
        private Boolean invulnerableIfOwned;
    }

    private static final class OwnershipRequirementsSection {
        private Boolean captureRequiresOwner;
        private Boolean spawnRequiresOwner;
        private Boolean interactionRequiresOwner;
        private Boolean linkingRequiresOwner;
    }

    private static final class InteractionDefaultsSection {
        private String interactionConfigParam;
        private String lovedItemsParam;
        private String isHarvestableParam;
        private String isMountableParam;
        private String harvestContextParam;
        private String harvestAlarmName;
        private String interactionCooldownAlarmPrefix;
    }

    private static final class CommandSection {
        private Double returnHomeTeleportDistance;
        private Double returnHomePathDistanceBeforeTeleport;
        private Integer returnHomeTeleportDelayMs;
        private Double recallSafeSpawnDistance;
        private Double recallForceRelocateDistance;
        private Integer relocationRetryIntervalMs;
        private Integer relocationMaxWaitMs;
        private Integer relocationMaxRetryAttempts;
        private Boolean deadRespawnEnabled;
        private Integer deadRespawnCooldownMs;
        private Double deadRespawnCooldownMins;
        private Integer deadRespawnFollowRetryDelayMs;
        private Double deadRespawnDistanceClose;
        private Double deadRespawnDistanceNear;
        private Double deadRespawnDistanceMid;
        private Double deadRespawnDistanceFar;
        private Double placementMinRelativeY;
        private Double placementMaxRelativeY;
        private Boolean linkedPanelRequireUnlinkConfirm;
    }

    private static final class AssetSetsSection {
        private Boolean tranquilizerShortbow;
        private Boolean tranquilizerArrow;
        private Boolean tranquilizerPotion;
        private Boolean feedTrough;
    }

    private static final class PopulationSection {
        private Integer limitPerPlayerOwnedTotal;
        private String perPlayerLimitScope;
    }

    private static final class SimpleClaimsSection {
        private Boolean simpleClaimsEnabled;
        private SimpleClaimsBreedingSection breeding;
        private SimpleClaimsDamageSection damage;
    }

    private static final class SimpleClaimsBreedingSection {
        private Integer limitPerClaimChunk;
        private Integer limitPerClaimTotal;
        private Boolean breedingRequiresClaim;
    }

    private static final class SimpleClaimsDamageSection {
        private Boolean protectTamedFromNonMembers;
        private String allowDamagePermissionKey;
    }

    public enum PerPlayerLimitScope {
        PER_WORLD("PerWorld"),
        GLOBAL("Global");

        private final String configValue;

        PerPlayerLimitScope(@Nonnull String configValue) {
            this.configValue = configValue;
        }

        @Nonnull
        public String configValue() {
            return configValue;
        }

        @Nonnull
        public static PerPlayerLimitScope fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return PER_WORLD;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("global".equals(normalized)) {
                return GLOBAL;
            }
            if ("perworld".equals(normalized)
                    || "per_world".equals(normalized)
                    || "per-world".equals(normalized)) {
                return PER_WORLD;
            }
            return PER_WORLD;
        }
    }

    public static final class AssetSetToggles {
        private static final AssetSetToggles DISABLED = new AssetSetToggles(false, false, false, false);

        private final boolean tranquilizerShortbowEnabled;
        private final boolean tranquilizerArrowEnabled;
        private final boolean tranquilizerPotionEnabled;
        private final boolean feedTroughEnabled;

        public AssetSetToggles(boolean tranquilizerShortbowEnabled,
                               boolean tranquilizerArrowEnabled,
                               boolean tranquilizerPotionEnabled,
                               boolean feedTroughEnabled) {
            this.tranquilizerShortbowEnabled = tranquilizerShortbowEnabled;
            this.tranquilizerArrowEnabled = tranquilizerArrowEnabled;
            this.tranquilizerPotionEnabled = tranquilizerPotionEnabled;
            this.feedTroughEnabled = feedTroughEnabled;
        }

        public static AssetSetToggles disabled() {
            return DISABLED;
        }

        public boolean isTranquilizerShortbowEnabled() {
            return tranquilizerShortbowEnabled;
        }

        public boolean isTranquilizerArrowEnabled() {
            return tranquilizerArrowEnabled;
        }

        public boolean isTranquilizerPotionEnabled() {
            return tranquilizerPotionEnabled;
        }

        public boolean isFeedTroughEnabled() {
            return feedTroughEnabled;
        }
    }
}



