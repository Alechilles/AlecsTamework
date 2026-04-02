package com.alechilles.alecstamework.config.assets;

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
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonNull;
import org.bson.BsonValue;

/**
 * Asset-backed configuration for naming items.
 * Stored under Server/Tamework/Items/Naming.
 */
public class TwNameItemConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwNameItemConfig>>,
        TwParentFallbackAsset<TwNameItemConfig> {
    public enum RoleFilterMode {
        AllowAll,
        Allowlist,
        Denylist
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
            new KeyedCodec<>("AllowUnownedWhenRequireOwner", Codec.BOOLEAN),
            (settings, value) -> settings.allowUnownedWhenRequireOwner = value,
            settings -> settings.allowUnownedWhenRequireOwner
        )
        .documentation("When RequireOwner is true, allow naming unowned NPCs.")
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
        .<String>append(
            new KeyedCodec<>("RandomNamesId", Codec.STRING),
            (settings, value) -> settings.randomNamesId = value,
            settings -> settings.randomNamesId
        )
        .documentation("Optional TwNames asset id used to populate the naming UI Random button.")
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
            (asset, value) -> asset.allowedRoles = value == null ? new AllowAllRoles() : value,
            asset -> asset.allowedRoles
        )
        .documentation("Role restrictions for naming targets. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<NamingSettings>append(
            new KeyedCodec<>("Naming", NAMING_CODEC),
            (asset, value) -> asset.naming = value == null ? new NamingSettings() : value,
            asset -> asset.naming
        )
        .documentation("Naming rules for this item. Inheritance: omitted section inherits from parent; when present, "
                + "only explicitly defined nested fields override parent.")
        .add()
        .build();

    private static AssetStore<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private String itemId;
    private AllowedRoles allowedRoles = new AllowAllRoles();
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
        DefaultAssetMap<String, TwNameItemConfig> assetMap = (DefaultAssetMap<String, TwNameItemConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwNameItemConfig> assetMap) {
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

    protected TwNameItemConfig() {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwNameItemConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwNameItemConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("ItemId")) itemId = parent.itemId;
        if (!explicitTopLevelKeys.contains("AllowedRoles")) {
            allowedRoles = parent.allowedRoles;
        } else {
            inheritAllowedRolesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "AllowedRoles"));
        }
        if (!explicitTopLevelKeys.contains("Naming")) {
            naming = parent.naming;
        } else {
            inheritNamingSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Naming"));
        }
    }

    private void inheritAllowedRolesSection(@Nonnull TwNameItemConfig parent, @Nullable Set<String> nestedExplicitKeys) {
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

    private void inheritNamingSection(@Nonnull TwNameItemConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (naming == null) {
            naming = parent.naming;
            return;
        }
        if (parent.naming == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("RequireTamed")) naming.requireTamed = parent.naming.requireTamed;
        if (!nestedExplicitKeys.contains("RequireOwner")) naming.requireOwner = parent.naming.requireOwner;
        if (!nestedExplicitKeys.contains("AllowUnownedWhenRequireOwner")) {
            naming.allowUnownedWhenRequireOwner = parent.naming.allowUnownedWhenRequireOwner;
        }
        if (!nestedExplicitKeys.contains("AllowRename")) naming.allowRename = parent.naming.allowRename;
        if (!nestedExplicitKeys.contains("MinLength")) naming.minLength = parent.naming.minLength;
        if (!nestedExplicitKeys.contains("MaxLength")) naming.maxLength = parent.naming.maxLength;
        if (!nestedExplicitKeys.contains("AllowedChars")) naming.allowedChars = parent.naming.allowedChars;
        if (!nestedExplicitKeys.contains("RandomNamesId")) naming.randomNamesId = parent.naming.randomNamesId;
        if (!nestedExplicitKeys.contains("TrimWhitespace")) naming.trimWhitespace = parent.naming.trimWhitespace;
        if (!nestedExplicitKeys.contains("ReplaceExisting")) naming.replaceExisting = parent.naming.replaceExisting;
        if (!nestedExplicitKeys.contains("ConsumeItem")) naming.consumeItem = parent.naming.consumeItem;
        if (!nestedExplicitKeys.contains("CooldownMs")) naming.cooldownMs = parent.naming.cooldownMs;
        if (!nestedExplicitKeys.contains("SoundEvent")) naming.soundEvent = parent.naming.soundEvent;
        if (!nestedExplicitKeys.contains("ParticleSystem")) naming.particleSystem = parent.naming.particleSystem;
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
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

    /** Base role filter model for naming targets. */
    public abstract static class AllowedRoles {
        public abstract RoleFilterMode getMode();

        public String[] getAllowlist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public String[] getDenylist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
    }

    /** Allow naming for all NPC roles. */
    public static final class AllowAllRoles extends AllowedRoles {
        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.AllowAll;
        }
    }

    /** Allow naming only for explicitly listed NPC roles. */
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

    /** Deny naming for explicitly listed NPC roles. */
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

    public static final class NamingSettings {
        private boolean requireTamed = true;
        private boolean requireOwner = true;
        private boolean allowUnownedWhenRequireOwner;
        private boolean allowRename = true;
        private int minLength = 1;
        private int maxLength = 24;
        private String allowedChars = "LettersNumbersSpaces";
        private String randomNamesId;
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

        public boolean isAllowUnownedWhenRequireOwner() {
            return allowUnownedWhenRequireOwner;
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

        @Nullable
        public String getRandomNamesId() {
            return randomNamesId;
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
