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
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped passive companion talent tree config.
 * Stored under Server/Tamework/Talents.
 */
public final class TwTalentConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwTalentConfig>>,
        TwParentFallbackAsset<TwTalentConfig> {
    private static final PassiveEffect[] EMPTY_EFFECTS = new PassiveEffect[0];
    private static final TalentDefinition[] EMPTY_TALENTS = new TalentDefinition[0];

    private static final BuilderCodec<PassiveEffect> PASSIVE_EFFECT_CODEC = BuilderCodec.builder(
            PassiveEffect.class,
            PassiveEffect::new
    )
            .<String>append(
                    new KeyedCodec<>("EffectKey", Codec.STRING),
                    (effect, value) -> effect.effectKey = value,
                    effect -> effect.effectKey
            )
            .documentation("Shared progression effect key granted by this talent.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("Multiplier", Codec.DOUBLE),
                    (effect, value) -> effect.multiplier = value,
                    effect -> effect.multiplier
            )
            .documentation("Multiplier applied when this talent is purchased.")
            .add()
            .build();
    private static final ArrayCodec<PassiveEffect> PASSIVE_EFFECT_ARRAY_CODEC =
            new ArrayCodec<>(PASSIVE_EFFECT_CODEC, PassiveEffect[]::new);

    private static final BuilderCodec<TalentDefinition> TALENT_DEFINITION_CODEC = BuilderCodec.builder(
            TalentDefinition.class,
            TalentDefinition::new
    )
            .<String>append(
                    new KeyedCodec<>("Id", Codec.STRING),
                    (definition, value) -> definition.id = value,
                    definition -> definition.id
            )
            .documentation("Unique talent ID.")
            .add()
            .<String>append(
                    new KeyedCodec<>("DisplayName", Codec.STRING),
                    (definition, value) -> definition.displayName = value,
                    definition -> definition.displayName
            )
            .documentation("Player-facing talent name. May be raw text or a server.lang key.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Description", Codec.STRING),
                    (definition, value) -> definition.description = value,
                    definition -> definition.description
            )
            .documentation("Player-facing talent description. May be raw text or a server.lang key.")
            .add()
            .<String>append(
                    new KeyedCodec<>("IconPath", Codec.STRING),
                    (definition, value) -> definition.iconPath = value,
                    definition -> definition.iconPath
            )
            .documentation("Optional icon asset path for this talent.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Tier", Codec.INTEGER),
                    (definition, value) -> definition.tier = value,
                    definition -> definition.tier
            )
            .documentation("Sorting tier used by the talent UI.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Branch", Codec.STRING),
                    (definition, value) -> definition.branch = value,
                    definition -> definition.branch
            )
            .documentation("Optional branch label used by the talent UI. May be raw text or a server.lang key.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("PointCost", Codec.INTEGER),
                    (definition, value) -> definition.pointCost = value,
                    definition -> definition.pointCost
            )
            .documentation("Talent-point cost to purchase this node.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("MinLevel", Codec.INTEGER),
                    (definition, value) -> definition.minLevel = value,
                    definition -> definition.minLevel
            )
            .documentation("Minimum companion level required to unlock this talent.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RequiresTalentIds", Codec.STRING_ARRAY),
                    (definition, value) -> definition.requiresTalentIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    definition -> definition.requiresTalentIds
            )
            .documentation("Talent IDs that must already be purchased. Explicit arrays replace the parent value.")
            .add()
            .<PassiveEffect[]>append(
                    new KeyedCodec<>("Effects", PASSIVE_EFFECT_ARRAY_CODEC),
                    (definition, value) -> definition.effects = value == null ? EMPTY_EFFECTS : value,
                    definition -> definition.effects
            )
            .documentation("Passive effect multipliers granted by this node. Explicit arrays replace the parent value.")
            .add()
            .build();
    private static final ArrayCodec<TalentDefinition> TALENT_DEFINITION_ARRAY_CODEC =
            new ArrayCodec<>(TALENT_DEFINITION_CODEC, TalentDefinition[]::new);

    public static final AssetBuilderCodec<String, TwTalentConfig> CODEC = AssetBuilderCodec.builder(
            TwTalentConfig.class,
            TwTalentConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped passive companion talent tree config.")
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
            .<Long>append(
                    new KeyedCodec<>("AllocationRevision", Codec.LONG),
                    (asset, value) -> asset.setAllocationRevision(value == null ? 0L : value),
                    TwTalentConfig::getAllocationRevision
            )
            .documentation("Revision of the purchasable allocation schema. Changing it resets incompatible saved allocations.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds
            )
            .documentation("NPC role IDs this config applies to. Inheritance: explicit arrays replace the parent value.")
            .add()
            .<TalentDefinition[]>append(
                    new KeyedCodec<>("Talents", TALENT_DEFINITION_ARRAY_CODEC),
                    (asset, value) -> asset.talents = value == null ? EMPTY_TALENTS : value,
                    asset -> asset.talents
            )
            .documentation("Talent definition list. Inheritance: explicit arrays replace the parent value.")
            .add()
            .build();

    private static AssetStore<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwTalentConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private long allocationRevision;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private TalentDefinition[] talents = EMPTY_TALENTS;

    public static AssetStore<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwTalentConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwTalentConfig> getAssetMap() {
        AssetStore<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwTalentConfig> assetMap = (DefaultAssetMap<String, TwTalentConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwTalentConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwTalentConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwTalentConfig> cache = ROLE_CACHE;
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
    public static TwTalentConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwTalentConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwTalentConfig> map = assetMap.getAssetMap();
        TwTalentConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwTalentConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwTalentConfig> buildRoleCache(@Nullable DefaultAssetMap<String, TwTalentConfig> assetMap) {
        Map<String, TwTalentConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwTalentConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                String normalized = roleId.trim().toLowerCase(Locale.ROOT);
                TwTalentConfig existing = cache.get(normalized);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalized, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwTalentConfig candidate,
                                                  @Nullable TwTalentConfig existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        if (candidate.getPriority() != existing.getPriority()) {
            return candidate.getPriority() > existing.getPriority();
        }
        return compareIds(candidate.getId(), existing.getId()) < 0;
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwTalentConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwTalentConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("AllocationRevision")) allocationRevision = parent.allocationRevision;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Talents")) talents = parent.talents;
    }

    protected TwTalentConfig() {
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

    public long getAllocationRevision() {
        return allocationRevision;
    }

    public void setAllocationRevision(long allocationRevision) {
        this.allocationRevision = Math.max(0L, allocationRevision);
    }

    public String[] getRoleIds() {
        return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
    }

    public TalentDefinition[] getTalents() {
        return talents == null ? EMPTY_TALENTS : talents;
    }

    @Nullable
    public TalentDefinition findTalent(@Nullable String talentId) {
        if (talentId == null || talentId.isBlank()) {
            return null;
        }
        for (TalentDefinition talent : getTalents()) {
            if (talent == null || talent.getId() == null) {
                continue;
            }
            if (talent.getId().equalsIgnoreCase(talentId.trim())) {
                return talent;
            }
        }
        return null;
    }

    /** Passive effect granted by a purchased talent. */
    public static final class PassiveEffect {
        private String effectKey;
        private double multiplier = 1.0;

        @Nullable
        public String getEffectKey() {
            if (effectKey == null || effectKey.isBlank()) {
                return null;
            }
            return effectKey;
        }

        public double getMultiplier() {
            return Double.isFinite(multiplier) && multiplier > 0.0 ? multiplier : 1.0;
        }
    }

    /** One purchasable passive talent node. */
    public static final class TalentDefinition {
        private String id;
        private String displayName;
        private String description;
        private String iconPath;
        private int tier = 1;
        private String branch;
        private int pointCost = 1;
        private int minLevel = 1;
        private String[] requiresTalentIds = ArrayUtil.EMPTY_STRING_ARRAY;
        private PassiveEffect[] effects = EMPTY_EFFECTS;

        @Nullable
        public String getId() {
            if (id == null || id.isBlank()) {
                return null;
            }
            return id;
        }

        public String getDisplayName() {
            return displayName == null || displayName.isBlank() ? getId() : displayName;
        }

        @Nullable
        public String getDescription() {
            if (description == null || description.isBlank()) {
                return null;
            }
            return description;
        }

        @Nullable
        public String getIconPath() {
            if (iconPath == null || iconPath.isBlank()) {
                return null;
            }
            return iconPath;
        }

        public int getTier() {
            return Math.max(1, tier);
        }

        @Nullable
        public String getBranch() {
            if (branch == null || branch.isBlank()) {
                return null;
            }
            return branch;
        }

        public int getPointCost() {
            return Math.max(1, pointCost);
        }

        public int getMinLevel() {
            return Math.max(1, minLevel);
        }

        public String[] getRequiresTalentIds() {
            return requiresTalentIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : requiresTalentIds;
        }

        public PassiveEffect[] getEffects() {
            return effects == null ? EMPTY_EFFECTS : effects;
        }
    }
}
