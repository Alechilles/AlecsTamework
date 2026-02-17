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
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.Collections;
import javax.annotation.Nullable;

/**
 * Asset-backed configuration for naming items.
 * Stored under Server/Tamework/Items/Naming.
 */
public class TwNameItemConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwNameItemConfig>> {
    public enum RoleFilterMode {
        AllowAll,
        Allowlist,
        Denylist
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
        .documentation("How to interpret allowlist/denylist for roles.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Allowlist", Codec.STRING_ARRAY),
            (settings, value) -> settings.allowlist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.allowlist
        )
        .documentation("Role IDs that are allowed (when Mode is Allowlist).")
        .add()
        .<String[]>append(
            new KeyedCodec<>("Denylist", Codec.STRING_ARRAY),
            (settings, value) -> settings.denylist = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            settings -> settings.denylist
        )
        .documentation("Role IDs that are denied (when Mode is Denylist).")
        .add()
        .build();

    public static final BuilderCodec<NamingSettings> NAMING_CODEC = BuilderCodec.builder(
            NamingSettings.class, NamingSettings::new
        )
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (settings, value) -> settings.requireTamed = value,
            settings -> settings.requireTamed
        )
        .documentation("Require the NPC to be tamed before naming.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.requireOwner = value,
            settings -> settings.requireOwner
        )
        .documentation("Require the player to be the owner before naming.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("AllowRename", Codec.BOOLEAN),
            (settings, value) -> settings.allowRename = value,
            settings -> settings.allowRename
        )
        .documentation("Allow renaming if the NPC already has a Tamework name.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MinLength", Codec.INTEGER),
            (settings, value) -> settings.minLength = value,
            settings -> settings.minLength
        )
        .documentation("Minimum name length.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxLength", Codec.INTEGER),
            (settings, value) -> settings.maxLength = value,
            settings -> settings.maxLength
        )
        .documentation("Maximum name length.")
        .add()
        .<String>append(
            new KeyedCodec<>("AllowedChars", Codec.STRING),
            (settings, value) -> settings.allowedChars = value,
            settings -> settings.allowedChars
        )
        .documentation("Allowed character preset or regex.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("TrimWhitespace", Codec.BOOLEAN),
            (settings, value) -> settings.trimWhitespace = value,
            settings -> settings.trimWhitespace
        )
        .documentation("Trim leading/trailing whitespace.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ReplaceExisting", Codec.BOOLEAN),
            (settings, value) -> settings.replaceExisting = value,
            settings -> settings.replaceExisting
        )
        .documentation("Replace existing display names (including non-Tamework names).")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ConsumeItem", Codec.BOOLEAN),
            (settings, value) -> settings.consumeItem = value,
            settings -> settings.consumeItem
        )
        .documentation("Consume one naming item on success.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownMs", Codec.INTEGER),
            (settings, value) -> settings.cooldownMs = value,
            settings -> settings.cooldownMs
        )
        .documentation("Cooldown after naming (milliseconds).")
        .add()
        .<String>append(
            new KeyedCodec<>("SoundEvent", Codec.STRING),
            (settings, value) -> settings.soundEvent = value,
            settings -> settings.soundEvent
        )
        .documentation("Sound event to play on success.")
        .add()
        .<String>append(
            new KeyedCodec<>("ParticleSystem", Codec.STRING),
            (settings, value) -> settings.particleSystem = value,
            settings -> settings.particleSystem
        )
        .documentation("Particle system to play on success.")
        .add()
        .build();

    public static final AssetBuilderCodec<String, TwNameItemConfig> CODEC =
        AssetBuilderCodec.builder(
                TwNameItemConfig.class,
                TwNameItemConfig::new,
                Codec.STRING,
                (asset, id) -> asset.id = id,
                asset -> asset.id,
                (asset, data) -> asset.data = data,
                asset -> asset.data
        )
        .documentation("Naming item configuration for Alec's Tamework!")
        .<String>append(
            new KeyedCodec<>("ItemId", Codec.STRING),
            (asset, value) -> asset.itemId = value,
            asset -> asset.itemId
        )
        .documentation("Item ID for the naming item.")
        .add()
        .<AllowedRoles>append(
            new KeyedCodec<>("AllowedRoles", ALLOWED_ROLES_CODEC),
            (asset, value) -> asset.allowedRoles = value == null ? new AllowedRoles() : value,
            asset -> asset.allowedRoles
        )
        .documentation("Role restrictions for naming targets.")
        .add()
        .<NamingSettings>append(
            new KeyedCodec<>("Naming", NAMING_CODEC),
            (asset, value) -> asset.naming = value == null ? new NamingSettings() : value,
            asset -> asset.naming
        )
        .documentation("Naming rules for this item.")
        .add()
        .build();

    private static AssetStore<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> ASSET_STORE;

    private AssetExtraInfo.Data data;
    private String id;
    private String itemId;
    private AllowedRoles allowedRoles = new AllowedRoles();
    private NamingSettings naming = new NamingSettings();

    public static AssetStore<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwNameItemConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwNameItemConfig> getAssetMap() {
        AssetStore<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        return (DefaultAssetMap<String, TwNameItemConfig>) store.getAssetMap();
    }

    protected TwNameItemConfig() {
    }

    public String getId() {
        return id;
    }

    public String getItemId() {
        return itemId;
    }

    public AllowedRoles getAllowedRoles() {
        return allowedRoles;
    }

    public NamingSettings getNaming() {
        return naming;
    }

    public static final class AllowedRoles {
        private RoleFilterMode mode = RoleFilterMode.AllowAll;
        private String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;

        public RoleFilterMode getMode() {
            return mode;
        }

        public String[] getAllowlist() {
            return allowlist;
        }

        public String[] getDenylist() {
            return denylist;
        }
    }

    public static final class NamingSettings {
        private boolean requireTamed = true;
        private boolean requireOwner = true;
        private boolean allowRename = true;
        private int minLength = 1;
        private int maxLength = 24;
        private String allowedChars = "LettersNumbersSpaces";
        private boolean trimWhitespace = true;
        private boolean replaceExisting = true;
        private boolean consumeItem;
        private int cooldownMs;
        private String soundEvent;
        private String particleSystem;

        public boolean isRequireTamed() {
            return requireTamed;
        }

        public boolean isRequireOwner() {
            return requireOwner;
        }

        public boolean isAllowRename() {
            return allowRename;
        }

        public int getMinLength() {
            return minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public String getAllowedChars() {
            return allowedChars;
        }

        public boolean isTrimWhitespace() {
            return trimWhitespace;
        }

        public boolean isReplaceExisting() {
            return replaceExisting;
        }

        public boolean isConsumeItem() {
            return consumeItem;
        }

        public int getCooldownMs() {
            return cooldownMs;
        }

        public String getSoundEvent() {
            return soundEvent;
        }

        public String getParticleSystem() {
            return particleSystem;
        }
    }
}
