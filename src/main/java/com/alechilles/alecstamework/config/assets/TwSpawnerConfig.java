package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.api.SpawnerCaptureMechanicsView;

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
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.common.util.ArrayUtil;
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
            (asset, value) -> {
                asset.filledItemId = value;
                asset.filledItemIdExplicit = true;
            },
            asset -> asset.filledItemId
        )
        .documentation("Item ID for the filled spawner variant.")
        .add()
        .<String>append(
            new KeyedCodec<>("IconDefault", Codec.STRING),
            (asset, value) -> asset.iconDefault = value,
            asset -> asset.iconDefault
        )
        .documentation("Fallback icon for the filled spawner when no TwDynamicIconConfig matches. Inheritance: omitted inherits from parent.")
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
    private static final TwAssetInheritanceGate INHERITANCE_GATE = new TwAssetInheritanceGate();

    private AssetExtraInfo.Data data;
    private String id;
    private String emptyItemId;
    private AllowedRoles allowedRoles = new AllowlistRoles();
    private String filledItemId;
    private boolean filledItemIdExplicit;
    private String iconDefault;
    private ItemFeatureConfig.SpawnerTooltipMode tooltipMode = ItemFeatureConfig.SpawnerTooltipMode.ADDITIVE;
    private CaptureSettings capture = new CaptureSettings();
    private SpawnSettings spawn = new SpawnSettings();

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
        INHERITANCE_GATE.markDirty();
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwSpawnerConfig> assetMap) {
        INHERITANCE_GATE.repairIfDirty(assetMap);
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
        if (!nestedExplicitKeys.contains("ChannelSoundEvent")) capture.channelSoundEvent = parent.capture.channelSoundEvent;
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
        if (!nestedExplicitKeys.contains("SourceConsumption")) capture.sourceConsumption = parent.capture.sourceConsumption;
        if (!nestedExplicitKeys.contains("SuccessDisposition")) capture.successDisposition = parent.capture.successDisposition;
        if (!nestedExplicitKeys.contains("BondedRosterId")) capture.bondedRosterId = parent.capture.bondedRosterId;
        if (!nestedExplicitKeys.contains("CommandFamilyId")) capture.commandFamilyId = parent.capture.commandFamilyId;
        if (!nestedExplicitKeys.contains("RequiredCommandConfigId")) capture.requiredCommandConfigId = parent.capture.requiredCommandConfigId;
        if (!nestedExplicitKeys.contains("RequireCommandAccessItem")) capture.requireCommandAccessItem = parent.capture.requireCommandAccessItem;
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

    public boolean isFilledItemIdExplicit() {
        return filledItemIdExplicit;
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
            .captureChannelSoundEvent(captureSettings.channelSoundEvent)
            .captureMaxHealthPercent(captureSettings.maxHealthPercent)
            .captureTamedRoleOverrides(captureSettings.tamedRoleOverrides)
            .spawnSoundEvent(spawnSettings.soundEvent)
            .captureCooldownMs(captureSettings.cooldownMs)
            .spawnCooldownMs(spawnSettings.cooldownMs)
            .captureMaxDistance(captureSettings.maxDistance)
            .spawnMaxDistance(spawnSettings.maxDistance)
            .spawnerFilledItemId(filledItemId)
            .spawnerIconDefault(iconDefault)
            .spawnerTooltipMode(tooltipMode)
            .captureMechanics(TwSpawnerConfigRuntimeAdapter.captureMechanics(captureSettings))
            .build();
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
        String channelSoundEvent;
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
        CaptureSourceConsumption sourceConsumption =
                CaptureSourceConsumption.SUCCESS_ONLY;
        CaptureSuccessDisposition successDisposition =
                CaptureSuccessDisposition.CAPTURED_ITEM;
        String bondedRosterId;
        String commandFamilyId;
        String requiredCommandConfigId;
        boolean requireCommandAccessItem;

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

}
