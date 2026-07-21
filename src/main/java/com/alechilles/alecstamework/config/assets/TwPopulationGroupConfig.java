package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
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
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonValue;

/** Config-defined companion population group stored under {@code Server/Tamework/PopulationGroups}. */
public final class TwPopulationGroupConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwPopulationGroupConfig>>,
        TwParentFallbackAsset<TwPopulationGroupConfig> {
    private static final Pattern NAMESPACED_GROUP_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_.-]*:[A-Za-z0-9][A-Za-z0-9_./:-]*"
    );

    private static final Codec<PopulationGroupScope> SCOPE_CODEC = new TwSilentCodec<>() {
        @Override
        public PopulationGroupScope decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
            String value = TwCodecLenient.asStringOrNull(bsonValue);
            if (value == null || value.isBlank() || value.equalsIgnoreCase("Global")) {
                return PopulationGroupScope.GLOBAL;
            }
            if (value.equalsIgnoreCase("PerWorld") || value.equalsIgnoreCase("PER_WORLD")) {
                return PopulationGroupScope.PER_WORLD;
            }
            throw new IllegalArgumentException("Unknown population group Scope: " + value);
        }

        @Override
        public BsonValue encode(PopulationGroupScope value, ExtraInfo extraInfo) {
            String encoded = value == PopulationGroupScope.PER_WORLD ? "PerWorld" : "Global";
            return Codec.STRING.encode(encoded, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            StringSchema schema = new StringSchema();
            schema.setEnum(new String[] { "Global", "PerWorld" });
            return schema;
        }
    };

    public static final BuilderCodec<LimitSettings> LIMITS_CODEC = BuilderCodec.builder(
            LimitSettings.class, LimitSettings::new
    )
            .<Integer>append(new KeyedCodec<>("MaxOwnedPerOwner", Codec.INTEGER),
                    (value, decoded) -> value.maxOwnedPerOwner = decoded, value -> value.maxOwnedPerOwner)
            .documentation("Maximum owned profiles per owner; 0 is unlimited. Within explicit Limits, omission inherits.")
            .add()
            .<Integer>append(new KeyedCodec<>("MaxActivePerOwner", Codec.INTEGER),
                    (value, decoded) -> value.maxActivePerOwner = decoded, value -> value.maxActivePerOwner)
            .documentation("Maximum active profiles per owner; 0 is unlimited. Within explicit Limits, omission inherits.")
            .add()
            .<PopulationGroupScope>append(new KeyedCodec<>("Scope", SCOPE_CODEC),
                    (value, decoded) -> value.scope = decoded == null ? PopulationGroupScope.GLOBAL : decoded,
                    value -> value.scope)
            .documentation("Global or PerWorld owner bucket scope. Within explicit Limits, omission inherits.").add()
            .build();

    public static final AssetBuilderCodec<String, TwPopulationGroupConfig> CODEC = AssetBuilderCodec.builder(
            TwPopulationGroupConfig.class,
            TwPopulationGroupConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role membership and atomic owned/active limits for one logical population group.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value, asset -> asset.enabled)
            .documentation("Disabled assets are inert. Omitted value inherits from parent.").add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value, asset -> asset.priority)
            .documentation("Higher priority wins for duplicate GroupId; deterministic asset-ID ordering breaks ties. Omitted inherits.")
            .add()
            .<String>append(new KeyedCodec<>("GroupId", Codec.STRING),
                    (asset, value) -> asset.groupId = value, asset -> asset.groupId)
            .documentation("Stable, namespaced, case-sensitive logical group identity. Omitted inherits.").add()
            .<String[]>append(new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds)
            .documentation("Exact canonical roles. Omitted inherits; an explicit array replaces parent (no merge).")
            .add()
            .<LimitSettings>append(new KeyedCodec<>("Limits", LIMITS_CODEC),
                    (asset, value) -> asset.limits = value == null ? new LimitSettings() : value,
                    asset -> asset.limits)
            .documentation("Per-owner limits; 0 is unlimited. Omitted inherits the parent object; explicit nested fields override and missing nested fields inherit.")
            .add()
            .build();

    private static AssetStore<String, TwPopulationGroupConfig, DefaultAssetMap<String, TwPopulationGroupConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String groupId;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private LimitSettings limits = new LimitSettings();

    protected TwPopulationGroupConfig() {
    }

    public static AssetStore<String, TwPopulationGroupConfig, DefaultAssetMap<String, TwPopulationGroupConfig>> getAssetStore() {
        if (ASSET_STORE == null) ASSET_STORE = AssetRegistry.getAssetStore(TwPopulationGroupConfig.class);
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwPopulationGroupConfig> getAssetMap() {
        AssetStore<String, TwPopulationGroupConfig, DefaultAssetMap<String, TwPopulationGroupConfig>> store = getAssetStore();
        if (store == null) return null;
        DefaultAssetMap<String, TwPopulationGroupConfig> map =
                (DefaultAssetMap<String, TwPopulationGroupConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(map);
        return map;
    }

    public static void clearInheritanceFallbackCache() { INHERITANCE_CACHE_DIRTY = true; }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwPopulationGroupConfig> map) {
        if (!INHERITANCE_CACHE_DIRTY || map == null || map.getAssetMap() == null) return;
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || map.getAssetMap() == null) return;
            TwAssetInheritanceFallback.repairAll(map);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) return null;
        String parent = data.getParentKey().toString();
        return parent == null || parent.isBlank() ? null : parent;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwPopulationGroupConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwPopulationGroupConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("GroupId")) groupId = parent.groupId;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Limits")) {
            limits = parent.limits;
        } else {
            Set<String> nested = explicitNestedKeysByTopLevel == null
                    ? null : explicitNestedKeysByTopLevel.get("Limits");
            inheritLimits(parent, nested);
        }
    }

    private void inheritLimits(TwPopulationGroupConfig parent, @Nullable Set<String> nested) {
        if (nested == null || parent.limits == null) return;
        if (limits == null) {
            limits = parent.limits;
            return;
        }
        if (!nested.contains("MaxOwnedPerOwner")) limits.maxOwnedPerOwner = parent.limits.maxOwnedPerOwner;
        if (!nested.contains("MaxActivePerOwner")) limits.maxActivePerOwner = parent.limits.maxActivePerOwner;
        if (!nested.contains("Scope")) limits.scope = parent.limits.scope;
    }

    public void validateOrThrow() {
        String configId = requireText(id, "config id");
        if (!enabled) return;
        String logicalId = requireText(groupId, "GroupId");
        if (!NAMESPACED_GROUP_ID.matcher(logicalId).matches()) {
            throw new IllegalArgumentException("Population GroupId must be namespaced in " + configId + ": " + logicalId);
        }
        String[] roles = getRoleIds();
        if (roles.length == 0) {
            throw new IllegalArgumentException("Enabled population group " + configId + " requires at least one RoleId.");
        }
        HashSet<String> unique = new HashSet<>();
        for (String role : roles) {
            String normalized = requireText(role, "role id");
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException("Population group " + configId + " repeats role " + normalized + '.');
            }
        }
        LimitSettings policy = getLimits();
        if (policy.maxOwnedPerOwner < 0 || policy.maxActivePerOwner < 0) {
            throw new IllegalArgumentException("Population group limits cannot be negative: " + configId);
        }
        if (policy.scope == null) throw new IllegalArgumentException("Population group Scope is required: " + configId);
    }

    public PopulationGroupDefinitionView toView(long revision) {
        validateOrThrow();
        LimitSettings policy = getLimits();
        return new PopulationGroupDefinitionView(
                id, revision, groupId, Set.copyOf(List.of(getRoleIds())),
                policy.maxOwnedPerOwner, policy.maxActivePerOwner, policy.scope
        );
    }

    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public int getPriority() { return priority; }
    public String getGroupId() { return groupId; }
    public String[] getRoleIds() { return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds.clone(); }
    public LimitSettings getLimits() { return limits == null ? new LimitSettings() : limits; }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
        return value.trim();
    }

    public static final class LimitSettings {
        private int maxOwnedPerOwner;
        private int maxActivePerOwner;
        private PopulationGroupScope scope = PopulationGroupScope.GLOBAL;

        public int getMaxOwnedPerOwner() { return maxOwnedPerOwner; }
        public int getMaxActivePerOwner() { return maxActivePerOwner; }
        public PopulationGroupScope getScope() { return scope; }
    }
}
