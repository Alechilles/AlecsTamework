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
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped attachment migration config used to backfill newly split attachment slots from legacy selections.
 * Stored under Server/Tamework/AttachmentMigrations.
 */
public final class TwAttachmentMigrationConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwAttachmentMigrationConfig>>,
        TwParentFallbackAsset<TwAttachmentMigrationConfig> {
    private static final Rule[] EMPTY_RULES = new Rule[0];

    private static final BuilderCodec<Rule> RULE_CODEC = BuilderCodec.builder(
            Rule.class,
            Rule::new
    )
            .<String>append(
                    new KeyedCodec<>("SourceSlot", Codec.STRING),
                    (rule, value) -> rule.sourceSlot = value,
                    rule -> rule.sourceSlot
            )
            .documentation("Attachment slot that already exists on legacy NPCs, such as Coat.")
            .add()
            .<String>append(
                    new KeyedCodec<>("TargetSlot", Codec.STRING),
                    (rule, value) -> rule.targetSlot = value,
                    rule -> rule.targetSlot
            )
            .documentation("Attachment slot to backfill when it is missing, such as Eyes.")
            .add()
            .<Map<String, String>>append(
                    new KeyedCodec<>("SourceToTarget", MapCodec.STRING_HASH_MAP_CODEC),
                    (rule, value) -> rule.sourceToTarget = value == null ? Map.of() : value,
                    rule -> rule.sourceToTarget
            )
            .documentation("Mapping from SourceSlot values to TargetSlot values. Explicit maps replace parent values.")
            .add()
            .build();

    private static final ArrayCodec<Rule> RULE_ARRAY_CODEC = new ArrayCodec<>(RULE_CODEC, Rule[]::new);

    public static final AssetBuilderCodec<String, TwAttachmentMigrationConfig> CODEC = AssetBuilderCodec.builder(
            TwAttachmentMigrationConfig.class,
            TwAttachmentMigrationConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped attachment migration config for legacy model attachment selections.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled
            )
            .documentation("Turns this migration config on or off.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Priority used when multiple configs apply to the same role; higher values take precedence.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds
            )
            .documentation("NPC role IDs this migration applies to. Inheritance: explicit arrays replace parent value.")
            .add()
            .<Rule[]>append(
                    new KeyedCodec<>("Rules", RULE_ARRAY_CODEC),
                    (asset, value) -> asset.rules = value == null ? EMPTY_RULES : value,
                    asset -> asset.rules
            )
            .documentation("Attachment migration rules. Inheritance: explicit arrays replace parent value.")
            .add()
            .build();

    private static AssetStore<String, TwAttachmentMigrationConfig, DefaultAssetMap<String, TwAttachmentMigrationConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwAttachmentMigrationConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private Rule[] rules = EMPTY_RULES;

    public static AssetStore<String, TwAttachmentMigrationConfig, DefaultAssetMap<String, TwAttachmentMigrationConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwAttachmentMigrationConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwAttachmentMigrationConfig> getAssetMap() {
        AssetStore<String, TwAttachmentMigrationConfig, DefaultAssetMap<String, TwAttachmentMigrationConfig>> store =
                getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwAttachmentMigrationConfig> assetMap =
                (DefaultAssetMap<String, TwAttachmentMigrationConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwAttachmentMigrationConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwAttachmentMigrationConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwAttachmentMigrationConfig> cache = ROLE_CACHE;
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
    public static TwAttachmentMigrationConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwAttachmentMigrationConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwAttachmentMigrationConfig> map = assetMap.getAssetMap();
        TwAttachmentMigrationConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwAttachmentMigrationConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwAttachmentMigrationConfig> buildRoleCache(
            @Nullable DefaultAssetMap<String, TwAttachmentMigrationConfig> assetMap) {
        HashMap<String, TwAttachmentMigrationConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwAttachmentMigrationConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                String normalizedRole = roleId.trim().toLowerCase(Locale.ROOT);
                TwAttachmentMigrationConfig existing = cache.get(normalizedRole);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalizedRole, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwAttachmentMigrationConfig candidate,
                                                  @Nullable TwAttachmentMigrationConfig existing) {
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

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwAttachmentMigrationConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwAttachmentMigrationConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Rules")) rules = parent.rules;
    }

    TwAttachmentMigrationConfig() {
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

    public Rule[] getRules() {
        return rules == null ? EMPTY_RULES : rules;
    }

    /** Single source-slot to target-slot migration rule. */
    public static final class Rule {
        private String sourceSlot;
        private String targetSlot;
        private Map<String, String> sourceToTarget = Map.of();

        public Rule() {
        }

        @Nullable
        public String getSourceSlot() {
            return sourceSlot == null || sourceSlot.isBlank() ? null : sourceSlot.trim();
        }

        @Nullable
        public String getTargetSlot() {
            return targetSlot == null || targetSlot.isBlank() ? null : targetSlot.trim();
        }

        public Map<String, String> getSourceToTarget() {
            return sourceToTarget == null ? Map.of() : sourceToTarget;
        }

        @Nullable
        public String resolveTargetValue(@Nullable String sourceValue) {
            if (sourceValue == null || sourceValue.isBlank() || getSourceToTarget().isEmpty()) {
                return null;
            }
            String direct = getSourceToTarget().get(sourceValue);
            if (direct != null && !direct.isBlank()) {
                return direct.trim();
            }
            String normalized = sourceValue.trim();
            for (Map.Entry<String, String> entry : getSourceToTarget().entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                if (entry.getKey().trim().equalsIgnoreCase(normalized)) {
                    return entry.getValue().trim();
                }
            }
            return null;
        }
    }
}
