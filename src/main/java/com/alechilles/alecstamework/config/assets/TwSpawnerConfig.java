package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.common.util.ArrayUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonNull;
import org.bson.BsonValue;

/**
 * Asset-backed configuration for spawner items.
 * Stored under Server/Tamework/Items/Spawners.
 */
public class TwSpawnerConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwSpawnerConfig>>,
        TwParentFallbackAsset<TwSpawnerConfig> {
    public enum RoleFilterMode {
        AllowAll,
        Allowlist,
        Denylist;

        public ItemFeatureConfig.RoleListMode toRoleListMode() {
            switch (this) {
                case Allowlist:
                    return ItemFeatureConfig.RoleListMode.ALLOW;
                case Denylist:
                    return ItemFeatureConfig.RoleListMode.DENY;
                case AllowAll:
                default:
                    return ItemFeatureConfig.RoleListMode.ANY;
            }
        }
    }

    private static final Codec<String[]> NPC_ROLE_ARRAY_CODEC = new TwSilentCodec<>() {
        @Override
        public String[] decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            return TwCodecLenient.asStringArrayOrEmpty(bsonValue);
        }

        @Override
        public BsonValue encode(String[] value, ExtraInfo extraInfo) {
            if (value == null) {
                return new BsonNull();
            }
            return Codec.STRING_ARRAY.encode(value, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            StringSchema roleSchema = new StringSchema();
            roleSchema.setHytaleAssetRef("NPCRole");
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItem(roleSchema);
            return arraySchema;
        }
    };

    private static final Codec<ItemFeatureConfig.SpawnerTooltipMode> TOOLTIP_MODE_CODEC = new TwSilentCodec<>() {
        @Override
        public ItemFeatureConfig.SpawnerTooltipMode decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            String raw = TwCodecLenient.asStringOrNull(bsonValue);
            if (raw == null || raw.isBlank()) {
                return ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE;
            }
            return ItemFeatureConfig.SpawnerTooltipMode.fromString(raw);
        }

        @Override
        public BsonValue encode(ItemFeatureConfig.SpawnerTooltipMode value, ExtraInfo extraInfo) {
            ItemFeatureConfig.SpawnerTooltipMode mode =
                    value != null ? value : ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE;
            return Codec.STRING.encode(mode == ItemFeatureConfig.SpawnerTooltipMode.REPLACE ? "Replace" : "Additive", extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            return Codec.STRING.toSchema(context);
        }
    };

    private static final BuilderCodec<AllowedRoles> ALLOWED_ROLES_BASE_CODEC = BuilderCodec.abstractBuilder(
            AllowedRoles.class
        )
        .build();

    private static final BuilderCodec<AllowAllRoles> ALLOW_ALL_ROLES_CODEC = BuilderCodec.builder(
            AllowAllRoles.class, AllowAllRoles::new, ALLOWED_ROLES_BASE_CODEC
        )
        .build();

    private static final BuilderCodec<AllowlistRoles> ALLOWLIST_ROLES_CODEC = BuilderCodec.builder(
            AllowlistRoles.class, AllowlistRoles::new, ALLOWED_ROLES_BASE_CODEC
        )
        .<String[]>append(
            new KeyedCodec<>("Allowlist", NPC_ROLE_ARRAY_CODEC),
            (settings, value) -> settings.allowlist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.allowlist
        )
        .documentation("Role IDs that are allowed.")
        .add()
        .build();

    private static final BuilderCodec<DenylistRoles> DENYLIST_ROLES_CODEC = BuilderCodec.builder(
            DenylistRoles.class, DenylistRoles::new, ALLOWED_ROLES_BASE_CODEC
        )
        .<String[]>append(
            new KeyedCodec<>("Denylist", NPC_ROLE_ARRAY_CODEC),
            (settings, value) -> settings.denylist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.denylist
        )
        .documentation("Role IDs that are denied.")
        .add()
        .build();

    public static final StringCodecMapCodec<AllowedRoles, BuilderCodec<? extends AllowedRoles>> ALLOWED_ROLES_CODEC =
            new StringCodecMapCodec<>("Mode") { };

    static {
        ALLOWED_ROLES_CODEC.register("AllowAll", AllowAllRoles.class, ALLOW_ALL_ROLES_CODEC);
        ALLOWED_ROLES_CODEC.register("Allowlist", AllowlistRoles.class, ALLOWLIST_ROLES_CODEC);
        ALLOWED_ROLES_CODEC.register("Denylist", DenylistRoles.class, DENYLIST_ROLES_CODEC);
    }

    public static final BuilderCodec<SpawnerIconOverride> ICON_OVERRIDE_CODEC = BuilderCodec.builder(
            SpawnerIconOverride.class, SpawnerIconOverride::new
        )
        .<String>append(
            new KeyedCodec<>("Icon", Codec.STRING),
            (override, icon) -> override.icon = icon,
            override -> override.icon
        )
        .documentation("Item icon asset ID override.")
        .add()
        .<Map<String, String>>append(
            new KeyedCodec<>("Attachments", MapCodec.STRING_HASH_MAP_CODEC),
            (override, attachments) -> override.attachments = attachments == null
                ? Collections.emptyMap()
                : attachments,
            override -> override.attachments
        )
        .documentation("Attachment overrides for the icon.")
        .add()
        .build();

    public static final ArrayCodec<SpawnerIconOverride> ICON_OVERRIDE_ARRAY_CODEC =
        new ArrayCodec<>(ICON_OVERRIDE_CODEC, SpawnerIconOverride[]::new);

    private static final SpawnerIconOverride[] EMPTY_OVERRIDES = new SpawnerIconOverride[0];
    private static final SpawnerIconOverrideGroup[] EMPTY_OVERRIDE_GROUPS = new SpawnerIconOverrideGroup[0];

    public static final BuilderCodec<SpawnerIconOverrideGroup> ICON_OVERRIDE_GROUP_CODEC = BuilderCodec.builder(
            SpawnerIconOverrideGroup.class, SpawnerIconOverrideGroup::new
        )
        .<String[]>append(
            new KeyedCodec<>("Roles", NPC_ROLE_ARRAY_CODEC),
            (group, roles) -> group.roles = roles == null ? ArrayUtil.EMPTY_STRING_ARRAY : roles,
            group -> group.roles
        )
        .documentation("Role IDs that share these icon overrides.")
        .add()
        .<String>append(
            new KeyedCodec<>("IconDefault", Codec.STRING),
            (group, iconDefault) -> group.iconDefault = iconDefault,
            group -> group.iconDefault
        )
        .documentation("Fallback icon for the listed roles when no attachment override matches. Use this for base-only models.")
        .add()
        .<SpawnerIconOverride[]>append(
            new KeyedCodec<>("Overrides", ICON_OVERRIDE_ARRAY_CODEC),
            (group, overrides) -> group.overrides = overrides == null ? EMPTY_OVERRIDES : overrides,
            group -> group.overrides
        )
        .documentation("Icon overrides shared by the listed roles.")
        .add()
        .build();

    public static final ArrayCodec<SpawnerIconOverrideGroup> ICON_OVERRIDE_GROUP_ARRAY_CODEC =
        new ArrayCodec<>(ICON_OVERRIDE_GROUP_CODEC, SpawnerIconOverrideGroup[]::new);

    public static final MapCodec<SpawnerIconOverride[], Map<String, SpawnerIconOverride[]>> ICON_OVERRIDES_BY_ROLE_CODEC =
        new MapCodec<>(ICON_OVERRIDE_ARRAY_CODEC, Object2ObjectOpenHashMap::new);

    public static final BuilderCodec<CaptureSettings> CAPTURE_CODEC =
            TwSpawnerCaptureSettingsCodec.CODEC;
    public static final BuilderCodec<SpawnSettings> SPAWN_CODEC = BuilderCodec.builder(
            SpawnSettings.class, SpawnSettings::new
        )
        .<Boolean>append(
            new KeyedCodec<>("AssignsOwner", Codec.BOOLEAN),
            (settings, value) -> settings.assignsOwner = value,
            settings -> settings.assignsOwner
        )
        .documentation("Assign the interacting player as owner on spawn.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("OwnerRestricted", Codec.BOOLEAN),
            (settings, value) -> settings.ownerRestricted = value,
            settings -> settings.ownerRestricted
        )
        .documentation("Restrict spawning to the owner.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireOwner = value,
            settings -> settings.requireOwner
        )
        .documentation("Require the spawner item to have an owner.")
        .add()
        .<String>append(
            new KeyedCodec<>("ParticleSystem", Codec.STRING),
            (settings, value) -> settings.particleSystem = value,
            settings -> settings.particleSystem
        )
        .documentation("Particle system to play on spawn.")
        .add()
        .<String>append(
            new KeyedCodec<>("SoundEvent", Codec.STRING),
            (settings, value) -> settings.soundEvent = value,
            settings -> settings.soundEvent
        )
        .documentation("Sound event to play on spawn.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownMs", Codec.INTEGER),
            (settings, value) -> settings.cooldownMs = value,
            settings -> settings.cooldownMs
        )
        .documentation("Cooldown after spawn (milliseconds).")
        .add()
        .<Double>append(
            new KeyedCodec<>("MaxDistance", Codec.DOUBLE),
            (settings, value) -> settings.maxDistance = value,
            settings -> settings.maxDistance
        )
        .documentation("Maximum spawn distance.")
        .add()
        .build();

    public static final AssetBuilderCodec<String, TwSpawnerConfig> CODEC =
        AssetBuilderCodec.builder(
                TwSpawnerConfig.class,
                TwSpawnerConfig::new,
                Codec.STRING,
                (asset, id) -> asset.id = id,
                asset -> asset.id,
                (asset, data) -> asset.data = data,
                asset -> asset.data
        )
        .documentation("Spawner item configuration for Alec's Tamework!")
        .<String>append(
            new KeyedCodec<>("EmptyItemId", Codec.STRING),
            (asset, value) -> asset.emptyItemId = value,
            asset -> asset.emptyItemId
        )
        .documentation("Item ID for the empty spawner variant.")
        .add()
        .<String>append(
            new KeyedCodec<>("FilledItemId", Codec.STRING),
            (asset, value) -> asset.filledItemId = value,
            asset -> asset.filledItemId
        )
        .documentation("Item ID for the filled spawner variant.")
        .add()
        .<String>append(
            new KeyedCodec<>("IconDefault", Codec.STRING),
            (asset, value) -> asset.iconDefault = value,
            asset -> asset.iconDefault
        )
        .documentation("Default icon for the spawner item.")
        .add()
        .<AllowedRoles>append(
            new KeyedCodec<>("AllowedRoles", ALLOWED_ROLES_CODEC),
            (asset, value) -> asset.allowedRoles = value == null ? new AllowlistRoles() : value,
            asset -> asset.allowedRoles
        )
        .documentation("Role restrictions for what can be captured/spawned. Inheritance: omitted section inherits "
                + "from parent; when present, only explicitly defined nested fields override parent.")
        .add()
        .<CaptureSettings>append(
            new KeyedCodec<>("Capture", CAPTURE_CODEC),
            (asset, value) -> asset.capture = value == null ? new CaptureSettings() : value,
            asset -> asset.capture
        )
        .documentation("Capture settings for spawner items. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<SpawnSettings>append(
            new KeyedCodec<>("Spawn", SPAWN_CODEC),
            (asset, value) -> asset.spawn = value == null ? new SpawnSettings() : value,
            asset -> asset.spawn
        )
        .documentation("Spawn settings for spawner items. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<TwSpawnerVesselSettings>append(
            new KeyedCodec<>("Vessel", TwSpawnerVesselSettings.CODEC),
            (asset, value) -> asset.vessel = value == null ? new TwSpawnerVesselSettings() : value,
            asset -> asset.vessel
        )
        .documentation("Vessel lifecycle settings. Inheritance: an omitted section inherits the parent section; "
                + "an explicit object inherits missing nested scalar fields, while explicit StateItemIds replaces "
                + "the parent map. Mode defaults to Disposable.")
        .add()
        .<SpawnerIconOverride[]>append(
            new KeyedCodec<>("IconOverrides", ICON_OVERRIDE_ARRAY_CODEC),
            (asset, value) -> asset.iconOverrides = value == null ? EMPTY_OVERRIDES : value,
            asset -> asset.iconOverrides
        )
        .documentation("Icon overrides that apply to all roles. Inheritance: omitted value inherits from parent; "
                + "explicit array replaces parent value (no merge).")
        .add()
        .<Map<String, SpawnerIconOverride[]>>append(
            new KeyedCodec<>("IconOverridesByRole", ICON_OVERRIDES_BY_ROLE_CODEC),
            (asset, value) -> asset.iconOverridesByRole = value == null ? Collections.emptyMap() : value,
            asset -> asset.iconOverridesByRole
        )
        .documentation("Icon overrides keyed by role ID. Inheritance: omitted value inherits from parent; explicit "
                + "map replaces parent value (no merge).")
        .add()
        .<SpawnerIconOverrideGroup[]>append(
            new KeyedCodec<>("IconOverrideGroups", ICON_OVERRIDE_GROUP_ARRAY_CODEC),
            (asset, value) -> asset.iconOverrideGroups = value == null ? EMPTY_OVERRIDE_GROUPS : value,
            asset -> asset.iconOverrideGroups
        )
        .documentation("Ordered shared icon override groups for multiple roles. Inheritance: omitted value inherits "
                + "from parent; explicit array replaces parent value (no merge).")
        .add()
        .<ItemFeatureConfig.SpawnerTooltipMode>append(
            new KeyedCodec<>("TooltipMode", TOOLTIP_MODE_CODEC),
            (asset, value) -> asset.tooltipMode =
                    value == null ? ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE : value,
            asset -> asset.tooltipMode
        )
        .documentation("Tooltip composition mode for captured spawner item display metadata (Additive or Replace).")
        .add()
        .build();

    private static AssetStore<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private String emptyItemId;
    private AllowedRoles allowedRoles = new AllowlistRoles();
    private String filledItemId;
    private String iconDefault;
    private SpawnerIconOverride[] iconOverrides = EMPTY_OVERRIDES;
    private Map<String, SpawnerIconOverride[]> iconOverridesByRole = Collections.emptyMap();
    private SpawnerIconOverrideGroup[] iconOverrideGroups = EMPTY_OVERRIDE_GROUPS;
    private ItemFeatureConfig.SpawnerTooltipMode tooltipMode = ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE;
    private CaptureSettings capture = new CaptureSettings();
    private SpawnSettings spawn = new SpawnSettings();
    private TwSpawnerVesselSettings vessel = new TwSpawnerVesselSettings();

    public static AssetStore<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwSpawnerConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwSpawnerConfig> getAssetMap() {
        AssetStore<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwSpawnerConfig> assetMap = (DefaultAssetMap<String, TwSpawnerConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwSpawnerConfig> assetMap) {
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

    protected TwSpawnerConfig() {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwSpawnerConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwSpawnerConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("EmptyItemId")) emptyItemId = parent.emptyItemId;
        if (!explicitTopLevelKeys.contains("AllowedRoles")) {
            allowedRoles = parent.allowedRoles;
        } else {
            inheritAllowedRolesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "AllowedRoles"));
        }
        if (!explicitTopLevelKeys.contains("FilledItemId")) filledItemId = parent.filledItemId;
        if (!explicitTopLevelKeys.contains("IconDefault")) iconDefault = parent.iconDefault;
        if (!explicitTopLevelKeys.contains("Capture")) {
            capture = parent.capture;
        } else {
            inheritCaptureSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Capture"));
        }
        if (!explicitTopLevelKeys.contains("Spawn")) {
            spawn = parent.spawn;
        } else {
            inheritSpawnSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Spawn"));
        }
        if (!explicitTopLevelKeys.contains("Vessel")) {
            vessel = parent.vessel;
        } else {
            inheritVesselSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Vessel"));
        }
        if (!explicitTopLevelKeys.contains("IconOverrides")) iconOverrides = parent.iconOverrides;
        if (!explicitTopLevelKeys.contains("IconOverridesByRole")) iconOverridesByRole = parent.iconOverridesByRole;
        if (!explicitTopLevelKeys.contains("IconOverrideGroups")) iconOverrideGroups = parent.iconOverrideGroups;
        if (!explicitTopLevelKeys.contains("TooltipMode")) tooltipMode = parent.tooltipMode;
    }

    private void inheritAllowedRolesSection(@Nonnull TwSpawnerConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Mode")) {
            allowedRoles = parent.allowedRoles;
            return;
        }
        if (allowedRoles == null || parent.allowedRoles == null) {
            return;
        }
        if (allowedRoles instanceof AllowlistRoles childAllowlist && parent.allowedRoles instanceof AllowlistRoles parentAllowlist) {
            if (!nestedExplicitKeys.contains("Allowlist")) {
                childAllowlist.allowlist = parentAllowlist.allowlist;
            }
        } else if (allowedRoles instanceof DenylistRoles childDenylist
                && parent.allowedRoles instanceof DenylistRoles parentDenylist) {
            if (!nestedExplicitKeys.contains("Denylist")) {
                childDenylist.denylist = parentDenylist.denylist;
            }
        }
    }

    private void inheritCaptureSection(@Nonnull TwSpawnerConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (capture == null) {
            capture = parent.capture;
            return;
        }
        if (parent.capture == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("ClearsOwner")) capture.clearsOwner = parent.capture.clearsOwner;
        if (!nestedExplicitKeys.contains("RequireTamed")) capture.requireTamed = parent.capture.requireTamed;
        if (!nestedExplicitKeys.contains("TamesTarget")) capture.tamesTarget = parent.capture.tamesTarget;
        if (!nestedExplicitKeys.contains("MaxHealthPercent")) capture.maxHealthPercent = parent.capture.maxHealthPercent;
        if (!nestedExplicitKeys.contains("RequiredEffectId")) capture.requiredEffectId = parent.capture.requiredEffectId;
        if (!nestedExplicitKeys.contains("ChannelAuraEffectId")) capture.channelAuraEffectId = parent.capture.channelAuraEffectId;
        if (!nestedExplicitKeys.contains("TamedRoleOverrides")) capture.tamedRoleOverrides = parent.capture.tamedRoleOverrides;
        if (!nestedExplicitKeys.contains("OwnerRestricted")) capture.ownerRestricted = parent.capture.ownerRestricted;
        if (!nestedExplicitKeys.contains("RequireOwner")) capture.requireOwner = parent.capture.requireOwner;
        if (!nestedExplicitKeys.contains("ParticleSystem")) capture.particleSystem = parent.capture.particleSystem;
        if (!nestedExplicitKeys.contains("SoundEvent")) capture.soundEvent = parent.capture.soundEvent;
        if (!nestedExplicitKeys.contains("CooldownMs")) capture.cooldownMs = parent.capture.cooldownMs;
        if (!nestedExplicitKeys.contains("MaxDistance")) capture.maxDistance = parent.capture.maxDistance;
        if (!nestedExplicitKeys.contains("ChanceMode")) capture.chanceMode = parent.capture.chanceMode;
        if (!nestedExplicitKeys.contains("Power")) capture.power = parent.capture.power;
        if (!nestedExplicitKeys.contains("BaseChance")) capture.baseChance = parent.capture.baseChance;
        if (!nestedExplicitKeys.contains("ChancePerPower")) capture.chancePerPower = parent.capture.chancePerPower;
        if (!nestedExplicitKeys.contains("MinimumChance")) capture.minimumChance = parent.capture.minimumChance;
        if (!nestedExplicitKeys.contains("MaximumChance")) capture.maximumChance = parent.capture.maximumChance;
        if (!nestedExplicitKeys.contains("FailureCooldownMs")) capture.failureCooldownMs = parent.capture.failureCooldownMs;
        if (!nestedExplicitKeys.contains("FailureParticleSystem")) capture.failureParticleSystem = parent.capture.failureParticleSystem;
        if (!nestedExplicitKeys.contains("FailureSoundEvent")) capture.failureSoundEvent = parent.capture.failureSoundEvent;
    }

    private void inheritSpawnSection(@Nonnull TwSpawnerConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (spawn == null) {
            spawn = parent.spawn;
            return;
        }
        if (parent.spawn == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("AssignsOwner")) spawn.assignsOwner = parent.spawn.assignsOwner;
        if (!nestedExplicitKeys.contains("OwnerRestricted")) spawn.ownerRestricted = parent.spawn.ownerRestricted;
        if (!nestedExplicitKeys.contains("RequireOwner")) spawn.requireOwner = parent.spawn.requireOwner;
        if (!nestedExplicitKeys.contains("ParticleSystem")) spawn.particleSystem = parent.spawn.particleSystem;
        if (!nestedExplicitKeys.contains("SoundEvent")) spawn.soundEvent = parent.spawn.soundEvent;
        if (!nestedExplicitKeys.contains("CooldownMs")) spawn.cooldownMs = parent.spawn.cooldownMs;
        if (!nestedExplicitKeys.contains("MaxDistance")) spawn.maxDistance = parent.spawn.maxDistance;
    }

    private void inheritVesselSection(@Nonnull TwSpawnerConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) return;
        if (vessel == null) {
            vessel = parent.vessel;
            return;
        }
        if (parent.vessel != null) vessel.inheritMissingFrom(parent.vessel, nestedExplicitKeys);
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    public String getEmptyItemId() {
        return emptyItemId;
    }

    public String getFilledItemId() {
        return filledItemId;
    }

    public TwSpawnerVesselSettings getVessel() {
        return vessel;
    }

    public ItemFeatureConfig toItemFeatureConfig() {
        CaptureSettings captureSettings = capture != null ? capture : new CaptureSettings();
        SpawnSettings spawnSettings = spawn != null ? spawn : new SpawnSettings();
        RoleFilterMode mode = RoleFilterMode.Allowlist;
        String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;
        String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;
        AllowedRoles allowed = allowedRoles;
        if (allowed != null) {
            mode = allowed.getMode() != null ? allowed.getMode() : mode;
            allowlist = allowed.getAllowlist() != null ? allowed.getAllowlist() : ArrayUtil.EMPTY_STRING_ARRAY;
            denylist = allowed.getDenylist() != null ? allowed.getDenylist() : ArrayUtil.EMPTY_STRING_ARRAY;
        }

        return ItemFeatureConfig.builder()
            .spawnerEnabled(true)
            .whistleEnabled(false)
            .captureClearsOwner(captureSettings.clearsOwner)
            .captureRequireTamed(captureSettings.requireTamed)
            .captureTamesTarget(captureSettings.tamesTarget)
            .captureOwnerRestricted(captureSettings.ownerRestricted)
            .spawnAssignsOwner(spawnSettings.assignsOwner)
            .spawnOwnerRestricted(spawnSettings.ownerRestricted)
            .spawnerRoleAllowlist(toList(allowlist))
            .spawnerRoleDenylist(toList(denylist))
            .spawnerRoleListMode(mode.toRoleListMode())
            .captureRequireOwnerOverride(captureSettings.requireOwner)
            .spawnRequireOwnerOverride(spawnSettings.requireOwner)
            .captureParticleSystem(captureSettings.particleSystem)
            .spawnParticleSystem(spawnSettings.particleSystem)
            .captureSoundEvent(captureSettings.soundEvent)
            .captureRequiredEffectId(captureSettings.requiredEffectId)
            .captureChannelAuraEffectId(captureSettings.channelAuraEffectId)
            .captureMaxHealthPercent(captureSettings.maxHealthPercent)
            .captureTamedRoleOverrides(captureSettings.tamedRoleOverrides)
            .spawnSoundEvent(spawnSettings.soundEvent)
            .captureCooldownMs(captureSettings.cooldownMs)
            .spawnCooldownMs(spawnSettings.cooldownMs)
            .captureMaxDistance(captureSettings.maxDistance)
            .spawnMaxDistance(spawnSettings.maxDistance)
            .spawnerFilledItemId(filledItemId)
            .spawnerIconDefault(iconDefault)
            .spawnerIconOverrides(toOverrides(iconOverrides))
            .spawnerIconOverridesByRole(toOverridesByRole(iconOverridesByRole))
            .spawnerIconOverrideGroups(toOverrideGroups(iconOverrideGroups))
            .spawnerTooltipMode(tooltipMode)
            .captureMechanics(TwSpawnerConfigRuntimeAdapter.captureMechanics(captureSettings))
            .vesselMechanics(TwSpawnerConfigRuntimeAdapter.vesselMechanics(
                    vessel, emptyItemId, filledItemId))
            .build();
    }

    public SpawnerVesselConfigView toVesselConfigView(long revision) {
        return TwSpawnerConfigRuntimeAdapter.vesselView(this, revision);
    }

    public boolean matchesVesselItemId(@Nullable String itemId) {
        return TwSpawnerConfigRuntimeAdapter.matchesVesselItemId(this, itemId);
    }

    public SpawnerCaptureMechanicsView toCaptureMechanicsView(long revision) {
        return TwSpawnerConfigRuntimeAdapter.captureView(this, revision);
    }

    CaptureSettings captureSettings() {
        return capture;
    }

    private static List<String> toList(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return List.of(values);
    }

    private static List<ItemFeatureConfig.SpawnerIconOverride> toOverrides(SpawnerIconOverride[] overrides) {
        if (overrides == null || overrides.length == 0) {
            return List.of();
        }
        List<ItemFeatureConfig.SpawnerIconOverride> list = new ArrayList<>(overrides.length);
        for (SpawnerIconOverride override : overrides) {
            if (override == null || override.icon == null || override.icon.isBlank()) {
                continue;
            }
            list.add(new ItemFeatureConfig.SpawnerIconOverride(override.attachments, override.icon));
        }
        return list.isEmpty() ? List.of() : list;
    }

    private static Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> toOverridesByRole(
        Map<String, SpawnerIconOverride[]> overridesByRole
    ) {
        if (overridesByRole == null || overridesByRole.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ItemFeatureConfig.SpawnerIconOverride>> result = new Object2ObjectOpenHashMap<>();
        for (Map.Entry<String, SpawnerIconOverride[]> entry : overridesByRole.entrySet()) {
            String roleId = entry.getKey();
            if (roleId == null || roleId.isBlank()) {
                continue;
            }
            List<ItemFeatureConfig.SpawnerIconOverride> overrides = toOverrides(entry.getValue());
            if (!overrides.isEmpty()) {
                result.put(roleId, overrides);
            }
        }
        return result.isEmpty() ? Map.of() : result;
    }

    private static List<ItemFeatureConfig.SpawnerIconOverrideGroup> toOverrideGroups(
        SpawnerIconOverrideGroup[] groups
    ) {
        if (groups == null || groups.length == 0) {
            return List.of();
        }
        List<ItemFeatureConfig.SpawnerIconOverrideGroup> result = new ArrayList<>(groups.length);
        for (SpawnerIconOverrideGroup group : groups) {
            if (group == null || group.roles == null || group.roles.length == 0) {
                continue;
            }
            List<ItemFeatureConfig.SpawnerIconOverride> overrides = toOverrides(group.overrides);
            String iconDefault = group.iconDefault;
            if (overrides.isEmpty() && (iconDefault == null || iconDefault.isBlank())) {
                continue;
            }
            List<String> roles = toList(group.roles);
            if (!roles.isEmpty()) {
                result.add(new ItemFeatureConfig.SpawnerIconOverrideGroup(roles, overrides, iconDefault));
            }
        }
        return result.isEmpty() ? List.of() : result;
    }

    /** Base role filter model for spawner capture/spawn restrictions. */
    public abstract static class AllowedRoles {
        public abstract RoleFilterMode getMode();

        public String[] getAllowlist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public String[] getDenylist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
    }

    /** Allow capture/spawn for all NPC roles. */
    public static final class AllowAllRoles extends AllowedRoles {
        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.AllowAll;
        }
    }

    /** Allow capture/spawn only for explicitly listed NPC roles. */
    public static final class AllowlistRoles extends AllowedRoles {
        private String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;

        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.Allowlist;
        }

        @Override
        public String[] getAllowlist() {
            return allowlist;
        }
    }

    /** Deny capture/spawn for explicitly listed NPC roles. */
    public static final class DenylistRoles extends AllowedRoles {
        private String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;

        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.Denylist;
        }

        @Override
        public String[] getDenylist() {
            return denylist;
        }
    }

    public static final class CaptureSettings {
        boolean clearsOwner = true;
        boolean requireTamed = true;
        boolean tamesTarget;
        Double maxHealthPercent;
        String requiredEffectId;
        String channelAuraEffectId;
        Map<String, String> tamedRoleOverrides = Collections.emptyMap();
        boolean ownerRestricted = true;
        Boolean requireOwner;
        String particleSystem;
        String soundEvent;
        int cooldownMs;
        double maxDistance;
        CaptureChanceMode chanceMode = CaptureChanceMode.GUARANTEED;
        int power;
        double baseChance = 1.0D;
        double chancePerPower;
        double minimumChance;
        double maximumChance = 1.0D;
        int failureCooldownMs;
        String failureParticleSystem;
        String failureSoundEvent;

        public ItemFeatureConfig.CaptureItemMechanics toMechanics() {
            return TwSpawnerConfigRuntimeAdapter.captureMechanics(this);
        }
    }

    public static final class SpawnSettings {
        private boolean assignsOwner = true;
        private boolean ownerRestricted = true;
        private Boolean requireOwner;
        private String particleSystem;
        private String soundEvent;
        private int cooldownMs;
        private double maxDistance;
    }

    public static final class SpawnerIconOverride {
        private Map<String, String> attachments = Collections.emptyMap();
        private String icon;

        public Map<String, String> getAttachments() {
            return attachments;
        }

        public String getIcon() {
            return icon;
        }
    }

    public static final class SpawnerIconOverrideGroup {
        private String[] roles = ArrayUtil.EMPTY_STRING_ARRAY;
        private String iconDefault;
        private SpawnerIconOverride[] overrides = EMPTY_OVERRIDES;

        public String[] getRoles() {
            return roles;
        }

        public String getIconDefault() {
            return iconDefault;
        }

        public SpawnerIconOverride[] getOverrides() {
            return overrides;
        }
    }
}





