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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped dynamic model attachment rules.
 * Stored under Server/Tamework/DynamicAttachments.
 */
public final class TwDynamicAttachmentsConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwDynamicAttachmentsConfig>>,
        TwParentFallbackAsset<TwDynamicAttachmentsConfig> {
    private static final Condition[] EMPTY_CONDITIONS = new Condition[0];
    private static final Rule[] EMPTY_RULES = new Rule[0];

    private static final BuilderCodec<Condition> CONDITION_CODEC = BuilderCodec.builder(
            Condition.class,
            Condition::new
    )
            .<String>append(
                    new KeyedCodec<>("Type", Codec.STRING),
                    (condition, value) -> condition.type = value,
                    condition -> condition.type
            )
            .documentation("Condition type evaluated by later dynamic attachment runtime tasks.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Value", Codec.STRING),
                    (condition, value) -> condition.value = value,
                    condition -> condition.value
            )
            .documentation("String value used by the condition.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("Values", Codec.STRING_ARRAY),
                    (condition, value) -> condition.values = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    condition -> condition.values
            )
            .documentation("String values used by list-based conditions such as OwnerEquals.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("Number", Codec.DOUBLE),
                    (condition, value) -> condition.number = value,
                    condition -> condition.number
            )
            .documentation("Numeric value used by the condition.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("Percent", Codec.DOUBLE),
                    (condition, value) -> condition.percent = value,
                    condition -> condition.percent
            )
            .documentation("Percent threshold used by happiness and need conditions. 25 means 25%.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Need", Codec.STRING),
                    (condition, value) -> condition.need = value,
                    condition -> condition.need
            )
            .documentation("Need identifier used by need-based conditions.")
            .add()
            .<String>append(
                    new KeyedCodec<>("TraitId", Codec.STRING),
                    (condition, value) -> condition.traitId = value,
                    condition -> condition.traitId
            )
            .documentation("Trait identifier used by trait-based conditions.")
            .add()
            .<String>append(
                    new KeyedCodec<>("State", Codec.STRING),
                    (condition, value) -> condition.state = value,
                    condition -> condition.state
            )
            .documentation("State key used by state-based conditions.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Expected", Codec.BOOLEAN),
                    (condition, value) -> condition.expected = value,
                    condition -> condition.expected
            )
            .documentation("Expected boolean result for the condition. Defaults to true when omitted.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("IgnoreCase", Codec.BOOLEAN),
                    (condition, value) -> condition.ignoreCase = value == null || value,
                    condition -> condition.ignoreCase
            )
            .documentation("Whether string comparisons should ignore case. Defaults to true.")
            .add()
            .build();

    private static final ArrayCodec<Condition> CONDITION_ARRAY_CODEC =
            new ArrayCodec<>(CONDITION_CODEC, Condition[]::new);

    private static final BuilderCodec<Rule> RULE_CODEC = BuilderCodec.builder(
            Rule.class,
            Rule::new
    )
            .<String>append(
                    new KeyedCodec<>("Id", Codec.STRING),
                    (rule, value) -> rule.id = value,
                    rule -> rule.id
            )
            .documentation("Stable rule ID used for diagnostics and deterministic ordering.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (rule, value) -> rule.priority = value == null ? 0 : value,
                    rule -> rule.priority
            )
            .documentation("Rule priority within this config; higher values are evaluated first.")
            .add()
            .<String>append(
                    new KeyedCodec<>("Persistence", Codec.STRING),
                    (rule, value) -> rule.persistence = Persistence.fromConfigValue(value),
                    rule -> rule.getPersistence().toConfigValue()
            )
            .documentation("Attachment persistence mode. Defaults to Permanent.")
            .add()
            .<Condition[]>append(
                    new KeyedCodec<>("Conditions", CONDITION_ARRAY_CODEC),
                    (rule, value) -> rule.conditions = value == null ? EMPTY_CONDITIONS : value,
                    rule -> rule.conditions
            )
            .documentation("Conditions required for this rule. Explicit arrays replace parent values.")
            .add()
            .<Map<String, String>>append(
                    new KeyedCodec<>("Attachments", MapCodec.STRING_HASH_MAP_CODEC),
                    (rule, value) -> rule.attachments = value == null ? Map.of() : value,
                    rule -> rule.attachments
            )
            .documentation("Attachment values to apply by slot. Explicit maps replace parent values.")
            .add()
            .build();

    private static final ArrayCodec<Rule> RULE_ARRAY_CODEC = new ArrayCodec<>(RULE_CODEC, Rule[]::new);

    public static final AssetBuilderCodec<String, TwDynamicAttachmentsConfig> CODEC = AssetBuilderCodec.builder(
            TwDynamicAttachmentsConfig.class,
            TwDynamicAttachmentsConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped dynamic attachment config for Alec's Tamework companions.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled
            )
            .documentation("Turns this dynamic attachment config on or off.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Config priority used before rule priority; higher values are evaluated first.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds
            )
            .documentation("NPC role IDs this config applies to. Inheritance: explicit arrays replace parent value.")
            .add()
            .<Rule[]>append(
                    new KeyedCodec<>("Rules", RULE_ARRAY_CODEC),
                    (asset, value) -> asset.rules = value == null ? EMPTY_RULES : value,
                    asset -> asset.rules
            )
            .documentation("Dynamic attachment rules. Inheritance: explicit arrays replace parent value.")
            .add()
            .build();

    private static AssetStore<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>>
            ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_RULE_INDEX_LOCK = new Object();
    private static volatile boolean ROLE_RULE_INDEX_DIRTY = true;
    private static volatile Map<String, List<RoleRuleEntry>> ROLE_RULE_INDEX = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private Rule[] rules = EMPTY_RULES;

    public static AssetStore<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>>
    getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwDynamicAttachmentsConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwDynamicAttachmentsConfig> getAssetMap() {
        AssetStore<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> store =
                getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwDynamicAttachmentsConfig> assetMap =
                (DefaultAssetMap<String, TwDynamicAttachmentsConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleRuleIndexCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_RULE_INDEX_DIRTY = true;
    }

    @Nonnull
    public static List<RoleRuleEntry> resolveRulesForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return List.of();
        }
        DefaultAssetMap<String, TwDynamicAttachmentsConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return List.of();
        }
        Map<String, List<RoleRuleEntry>> index = ROLE_RULE_INDEX;
        if (ROLE_RULE_INDEX_DIRTY || index == null) {
            synchronized (ROLE_RULE_INDEX_LOCK) {
                if (ROLE_RULE_INDEX_DIRTY || ROLE_RULE_INDEX == null) {
                    ROLE_RULE_INDEX = buildRoleRuleIndex(assetMap.getAssetMap().values());
                    ROLE_RULE_INDEX_DIRTY = false;
                }
                index = ROLE_RULE_INDEX;
            }
        }
        return index.getOrDefault(normalizeKey(roleId), List.of());
    }

    static Map<String, List<RoleRuleEntry>> buildRoleRuleIndexForTest(
            @Nonnull List<TwDynamicAttachmentsConfig> configs) {
        return buildRoleRuleIndex(configs);
    }

    @Nonnull
    private static Map<String, List<RoleRuleEntry>> buildRoleRuleIndex(
            @Nullable Collection<TwDynamicAttachmentsConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return Map.of();
        }
        Map<String, List<RoleRuleEntry>> entriesByRole = new HashMap<>();
        int declarationOrder = 0;
        for (TwDynamicAttachmentsConfig config : configs) {
            if (config == null || !config.isEnabled()) {
                continue;
            }
            for (Rule rule : config.getRules()) {
                if (rule == null) {
                    continue;
                }
                int currentOrder = declarationOrder++;
                for (String roleId : config.getRoleIds()) {
                    String normalizedRole = normalizeKey(roleId);
                    if (normalizedRole.isEmpty()) {
                        continue;
                    }
                    entriesByRole.computeIfAbsent(normalizedRole, ignored -> new ArrayList<>())
                            .add(new RoleRuleEntry(config, rule, currentOrder));
                }
            }
        }
        Map<String, List<RoleRuleEntry>> sorted = new HashMap<>();
        for (Map.Entry<String, List<RoleRuleEntry>> entry : entriesByRole.entrySet()) {
            List<RoleRuleEntry> entries = new ArrayList<>(entry.getValue());
            entries.sort(RoleRuleEntry.ORDERING);
            sorted.put(entry.getKey(), List.copyOf(entries));
        }
        return Map.copyOf(sorted);
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwDynamicAttachmentsConfig> assetMap) {
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

    @Nonnull
    private static String normalizeKey(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
    public void inheritMissingTopLevelFrom(@Nonnull TwDynamicAttachmentsConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Rules")) rules = parent.rules;
    }

    TwDynamicAttachmentsConfig() {
    }

    @Override
    @Nullable
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

    /** Persistence behavior for attachments applied by a matching rule. */
    public enum Persistence {
        PERMANENT("Permanent"),
        WHILE_MATCHING("WhileMatching");

        private final String configValue;

        Persistence(String configValue) {
            this.configValue = configValue;
        }

        public static Persistence fromConfigValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return PERMANENT;
            }
            String normalized = normalizeEnumToken(value);
            for (Persistence mode : values()) {
                if (normalizeEnumToken(mode.name()).equals(normalized)
                        || normalizeEnumToken(mode.configValue).equals(normalized)) {
                    return mode;
                }
            }
            return PERMANENT;
        }

        public String toConfigValue() {
            return configValue;
        }
    }

    /** One ordered rule that can apply model attachment values. */
    public static final class Rule {
        private String id;
        private int priority;
        private Persistence persistence = Persistence.PERMANENT;
        private Condition[] conditions = EMPTY_CONDITIONS;
        private Map<String, String> attachments = Map.of();

        public Rule() {
        }

        @Nullable
        public String getId() {
            return id == null || id.isBlank() ? null : id.trim();
        }

        public int getPriority() {
            return priority;
        }

        @Nonnull
        public Persistence getPersistence() {
            return persistence == null ? Persistence.PERMANENT : persistence;
        }

        public Condition[] getConditions() {
            return conditions == null ? EMPTY_CONDITIONS : conditions;
        }

        public Map<String, String> getAttachments() {
            return attachments == null ? Map.of() : attachments;
        }
    }

    /** Condition data carried by dynamic attachment rules for later runtime evaluators. */
    public static final class Condition {
        private String type;
        private String value;
        private String[] values = ArrayUtil.EMPTY_STRING_ARRAY;
        private Double number;
        private Double percent;
        private String need;
        private String traitId;
        private String state;
        private Boolean expected;
        private boolean ignoreCase = true;

        public Condition() {
        }

        @Nullable
        public String getType() {
            return type == null || type.isBlank() ? null : type.trim();
        }

        @Nullable
        public String getValue() {
            return value;
        }

        public String[] getValues() {
            return values == null ? ArrayUtil.EMPTY_STRING_ARRAY : values;
        }

        @Nullable
        public Double getNumber() {
            return number;
        }

        @Nullable
        public Double getPercent() {
            return percent;
        }

        @Nullable
        public String getNeed() {
            return need == null || need.isBlank() ? null : need.trim();
        }

        @Nullable
        public String getTraitId() {
            return traitId == null || traitId.isBlank() ? null : traitId.trim();
        }

        @Nullable
        public String getState() {
            return state == null || state.isBlank() ? null : state.trim();
        }

        @Nullable
        public Boolean getExpected() {
            return expected;
        }

        public boolean expectedOrTrue() {
            return expected == null || expected;
        }

        public boolean isIgnoreCase() {
            return ignoreCase;
        }
    }

    /** Indexed view of one config rule for a normalized role ID. */
    public static final class RoleRuleEntry {
        private static final Comparator<RoleRuleEntry> ORDERING = Comparator
                .comparingInt((RoleRuleEntry entry) -> entry.config.getPriority()).reversed()
                .thenComparing(Comparator.comparingInt((RoleRuleEntry entry) -> entry.rule.getPriority()).reversed())
                .thenComparing(entry -> normalizeKey(entry.config.getId()))
                .thenComparingInt(RoleRuleEntry::getDeclarationOrder);

        private final TwDynamicAttachmentsConfig config;
        private final Rule rule;
        private final int declarationOrder;

        private RoleRuleEntry(@Nonnull TwDynamicAttachmentsConfig config,
                              @Nonnull Rule rule,
                              int declarationOrder) {
            this.config = config;
            this.rule = rule;
            this.declarationOrder = declarationOrder;
        }

        public TwDynamicAttachmentsConfig getConfig() {
            return config;
        }

        public Rule getRule() {
            return rule;
        }

        public int getDeclarationOrder() {
            return declarationOrder;
        }
    }

    @Nonnull
    private static String normalizeEnumToken(@Nonnull String value) {
        return value.trim()
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }
}
