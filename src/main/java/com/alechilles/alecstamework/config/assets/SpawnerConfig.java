package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Asset-backed configuration for spawner items.
 * Stored under Server/Tamework/Items/Spawners.
 */
public class SpawnerConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, SpawnerConfig>> {
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

    private static final EnumCodec<RoleFilterMode> ROLE_FILTER_MODE_CODEC = new EnumCodec<>(RoleFilterMode.class);

    public static final BuilderCodec<AllowedRoles> ALLOWED_ROLES_CODEC = BuilderCodec.builder(
            AllowedRoles.class, AllowedRoles::new
        )
        .<RoleFilterMode>append(
            new KeyedCodec<>("Mode", ROLE_FILTER_MODE_CODEC),
            (settings, value) -> settings.mode = value,
            settings -> settings.mode
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Allowlist", Codec.STRING_ARRAY),
            (settings, value) -> settings.allowlist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.allowlist
        )
        .add()
        .<String[]>append(
            new KeyedCodec<>("Denylist", Codec.STRING_ARRAY),
            (settings, value) -> settings.denylist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.denylist
        )
        .add()
        .build();

    public static final BuilderCodec<SpawnerIconOverride> ICON_OVERRIDE_CODEC = BuilderCodec.builder(
            SpawnerIconOverride.class, SpawnerIconOverride::new
        )
        .<String>append(
            new KeyedCodec<>("Icon", Codec.STRING),
            (override, icon) -> override.icon = icon,
            override -> override.icon
        )
        .add()
        .<Map<String, String>>append(
            new KeyedCodec<>("Attachments", MapCodec.STRING_HASH_MAP_CODEC),
            (override, attachments) -> override.attachments = attachments == null
                ? Collections.emptyMap()
                : attachments,
            override -> override.attachments
        )
        .add()
        .build();

    public static final ArrayCodec<SpawnerIconOverride> ICON_OVERRIDE_ARRAY_CODEC =
        new ArrayCodec<>(ICON_OVERRIDE_CODEC, SpawnerIconOverride[]::new);

    public static final MapCodec<SpawnerIconOverride[], Map<String, SpawnerIconOverride[]>> ICON_OVERRIDES_BY_ROLE_CODEC =
        new MapCodec<>(ICON_OVERRIDE_ARRAY_CODEC, Object2ObjectOpenHashMap::new);

    private static final SpawnerIconOverride[] EMPTY_OVERRIDES = new SpawnerIconOverride[0];

    public static final BuilderCodec<CaptureSettings> CAPTURE_CODEC = BuilderCodec.builder(
            CaptureSettings.class, CaptureSettings::new
        )
        .<Boolean>append(
            new KeyedCodec<>("ClearsOwner", Codec.BOOLEAN),
            (settings, value) -> settings.clearsOwner = value,
            settings -> settings.clearsOwner
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (settings, value) -> settings.requireTamed = value,
            settings -> settings.requireTamed
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("OwnerRestricted", Codec.BOOLEAN),
            (settings, value) -> settings.ownerRestricted = value,
            settings -> settings.ownerRestricted
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireOwner = value,
            settings -> settings.requireOwner
        )
        .add()
        .<String>append(
            new KeyedCodec<>("ParticleSystem", Codec.STRING),
            (settings, value) -> settings.particleSystem = value,
            settings -> settings.particleSystem
        )
        .add()
        .<String>append(
            new KeyedCodec<>("SoundEvent", Codec.STRING),
            (settings, value) -> settings.soundEvent = value,
            settings -> settings.soundEvent
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownMs", Codec.INTEGER),
            (settings, value) -> settings.cooldownMs = value,
            settings -> settings.cooldownMs
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MaxDistance", Codec.DOUBLE),
            (settings, value) -> settings.maxDistance = value,
            settings -> settings.maxDistance
        )
        .add()
        .build();

    public static final BuilderCodec<SpawnSettings> SPAWN_CODEC = BuilderCodec.builder(
            SpawnSettings.class, SpawnSettings::new
        )
        .<Boolean>append(
            new KeyedCodec<>("AssignsOwner", Codec.BOOLEAN),
            (settings, value) -> settings.assignsOwner = value,
            settings -> settings.assignsOwner
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("OwnerRestricted", Codec.BOOLEAN),
            (settings, value) -> settings.ownerRestricted = value,
            settings -> settings.ownerRestricted
        )
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireOwner = value,
            settings -> settings.requireOwner
        )
        .add()
        .<String>append(
            new KeyedCodec<>("ParticleSystem", Codec.STRING),
            (settings, value) -> settings.particleSystem = value,
            settings -> settings.particleSystem
        )
        .add()
        .<String>append(
            new KeyedCodec<>("SoundEvent", Codec.STRING),
            (settings, value) -> settings.soundEvent = value,
            settings -> settings.soundEvent
        )
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownMs", Codec.INTEGER),
            (settings, value) -> settings.cooldownMs = value,
            settings -> settings.cooldownMs
        )
        .add()
        .<Double>append(
            new KeyedCodec<>("MaxDistance", Codec.DOUBLE),
            (settings, value) -> settings.maxDistance = value,
            settings -> settings.maxDistance
        )
        .add()
        .build();

    public static final AssetBuilderCodec<String, SpawnerConfig> CODEC =
        AssetBuilderCodec.builder(
                SpawnerConfig.class,
                SpawnerConfig::new,
                Codec.STRING,
                (asset, id) -> asset.id = id,
                asset -> asset.id,
                (asset, data) -> asset.data = data,
                asset -> asset.data
        )
        .documentation("Spawner item configuration for Alec's Tamework!")
        .<String>append(
            new KeyedCodec<>("FilledItemId", Codec.STRING),
            (asset, value) -> asset.filledItemId = value,
            asset -> asset.filledItemId
        )
        .add()
        .<String>append(
            new KeyedCodec<>("IconDefault", Codec.STRING),
            (asset, value) -> asset.iconDefault = value,
            asset -> asset.iconDefault
        )
        .add()
        .<AllowedRoles>append(
            new KeyedCodec<>("AllowedRoles", ALLOWED_ROLES_CODEC),
            (asset, value) -> asset.allowedRoles = value == null ? new AllowedRoles() : value,
            asset -> asset.allowedRoles
        )
        .add()
        .<CaptureSettings>append(
            new KeyedCodec<>("Capture", CAPTURE_CODEC),
            (asset, value) -> asset.capture = value == null ? new CaptureSettings() : value,
            asset -> asset.capture
        )
        .add()
        .<SpawnSettings>append(
            new KeyedCodec<>("Spawn", SPAWN_CODEC),
            (asset, value) -> asset.spawn = value == null ? new SpawnSettings() : value,
            asset -> asset.spawn
        )
        .add()
        .<SpawnerIconOverride[]>append(
            new KeyedCodec<>("IconOverrides", ICON_OVERRIDE_ARRAY_CODEC),
            (asset, value) -> asset.iconOverrides = value == null ? EMPTY_OVERRIDES : value,
            asset -> asset.iconOverrides
        )
        .add()
        .<Map<String, SpawnerIconOverride[]>>append(
            new KeyedCodec<>("IconOverridesByRole", ICON_OVERRIDES_BY_ROLE_CODEC),
            (asset, value) -> asset.iconOverridesByRole = value == null ? Collections.emptyMap() : value,
            asset -> asset.iconOverridesByRole
        )
        .add()
        .build();

    private static AssetStore<String, SpawnerConfig, DefaultAssetMap<String, SpawnerConfig>> ASSET_STORE;

    private AssetExtraInfo.Data data;
    private String id;
    private AllowedRoles allowedRoles = new AllowedRoles();
    private String filledItemId;
    private String iconDefault;
    private SpawnerIconOverride[] iconOverrides = EMPTY_OVERRIDES;
    private Map<String, SpawnerIconOverride[]> iconOverridesByRole = Collections.emptyMap();
    private CaptureSettings capture = new CaptureSettings();
    private SpawnSettings spawn = new SpawnSettings();

    public static AssetStore<String, SpawnerConfig, DefaultAssetMap<String, SpawnerConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(SpawnerConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, SpawnerConfig> getAssetMap() {
        AssetStore<String, SpawnerConfig, DefaultAssetMap<String, SpawnerConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, SpawnerConfig>) store.getAssetMap();
    }

    protected SpawnerConfig() {
    }

    public String getId() {
        return id;
    }

    public ItemFeatureConfig toItemFeatureConfig() {
        CaptureSettings captureSettings = capture != null ? capture : new CaptureSettings();
        SpawnSettings spawnSettings = spawn != null ? spawn : new SpawnSettings();
        RoleFilterMode mode = RoleFilterMode.Allowlist;
        String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;
        String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;
        AllowedRoles allowed = allowedRoles;
        if (allowed != null && (allowed.mode != null
                || (allowed.allowlist != null && allowed.allowlist.length > 0)
                || (allowed.denylist != null && allowed.denylist.length > 0))) {
            mode = allowed.mode != null ? allowed.mode : mode;
            allowlist = allowed.allowlist != null ? allowed.allowlist : ArrayUtil.EMPTY_STRING_ARRAY;
            denylist = allowed.denylist != null ? allowed.denylist : ArrayUtil.EMPTY_STRING_ARRAY;
        }

        return ItemFeatureConfig.builder()
            .spawnerEnabled(true)
            .whistleEnabled(false)
            .captureClearsOwner(captureSettings.clearsOwner)
            .captureRequireTamed(captureSettings.requireTamed)
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
            .spawnSoundEvent(spawnSettings.soundEvent)
            .captureCooldownMs(captureSettings.cooldownMs)
            .spawnCooldownMs(spawnSettings.cooldownMs)
            .captureMaxDistance(captureSettings.maxDistance)
            .spawnMaxDistance(spawnSettings.maxDistance)
            .spawnerFilledItemId(filledItemId)
            .spawnerIconDefault(iconDefault)
            .spawnerIconOverrides(toOverrides(iconOverrides))
            .spawnerIconOverridesByRole(toOverridesByRole(iconOverridesByRole))
            .build();
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

    public static final class AllowedRoles {
        private RoleFilterMode mode = RoleFilterMode.Allowlist;
        private String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;
    }

    public static final class CaptureSettings {
        private boolean clearsOwner = true;
        private boolean requireTamed = true;
        private boolean ownerRestricted = true;
        private Boolean requireOwner;
        private String particleSystem;
        private String soundEvent;
        private int cooldownMs;
        private double maxDistance;
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
}
