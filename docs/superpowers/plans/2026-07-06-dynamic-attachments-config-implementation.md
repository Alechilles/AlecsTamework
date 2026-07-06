# Dynamic Attachments Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a dedicated `TwDynamicAttachmentsConfig` system that conditionally changes NPC model attachments with `Permanent` and `WhileMatching` persistence modes while keeping server load negligible.

**Architecture:** Add a role-scoped config family, pure condition/rule resolution services, a small persistent overlay component for reversible `WhileMatching` state, and a low-frequency runtime system that only evaluates configured roles and skips unchanged fingerprints. Reuse existing `TameworkAttachmentsComponent`, `CompanionAttachmentStateService`, and `CompanionModelAttachmentService` for stored attachment validation/application.

**Tech Stack:** Java, Hytale ECS components/systems, Tamework asset codecs, JUnit 5, Maven wrapper.

---

## File Structure

Create:

- `src/main/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfig.java`  
  Asset codec, inheritance fallback, role cache, rule/condition data types, and config-level constants.
- `src/main/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponent.java`  
  Persistent baseline/active overlay state for `WhileMatching` rules.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentNpcSnapshot.java`  
  Immutable-ish runtime snapshot used by pure tests and evaluators.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentSnapshotReader.java`  
  Reads NPC/store components into snapshots with role-specific field requirements.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConditionEvaluator.java`  
  Pure condition matching.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConfigIndex.java`  
  Role-indexed, pre-sorted config/rule cache.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentRuleResolver.java`  
  Computes winning permanent and while-matching slot selections.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentResolution.java`  
  Small result object for resolver output.
- `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationService.java`  
  Merges permanent slots, applies/restores temporary slots, and requests existing attachment sync.
- `src/main/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystem.java`  
  Low-frequency candidate scan, fingerprint skip, and command-buffer writes.
- `src/test/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfigTest.java`
- `src/test/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponentTest.java`
- `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConditionEvaluatorTest.java`
- `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConfigIndexTest.java`
- `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentRuleResolverTest.java`
- `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationServiceTest.java`

Modify:

- `src/main/java/com/alechilles/alecstamework/Tamework.java`  
  Register config asset family, component, event hooks, getter, and runtime system.
- `src/main/java/com/alechilles/alecstamework/config/overrides/TwConfigFamily.java`  
  Add config editor family.
- `src/main/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapter.java`  
  Expose schema codec for the config editor.
- `docs/Config-Discovery.md`
- `wiki/Modder-Documentation/Start-Here/Config-Discovery-Resolution-and-Inheritance.md`
- `wiki/Modder-Documentation/Config-Reference/TwDynamicAttachmentsConfig-Reference.md`
- `wiki/Modder-Documentation/Config-Reference/index.md`
- `CHANGELOG.md`
- `docs/agents/generated-index.md`  
  Regenerate with `.\scripts\tools\build-agent-index.ps1` after package/docs changes.

Do not edit runtime copy paths under `C:\Users\22ale\AppData\Roaming\Hytale\UserData\Mods\alecstamework`.

---

### Task 1: Config Codec And Inheritance

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfig.java`
- Test: `src/test/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfigTest.java`

- [ ] **Step 1: Write the failing config tests**

Create `TwDynamicAttachmentsConfigTest` with reflection helpers matching existing config tests. Cover default persistence, explicit arrays replacing parent arrays, role cache sorting, and case-insensitive role lookup.

```java
package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwDynamicAttachmentsConfigTest {
    @Test
    void ruleDefaultsToPermanentPersistence() {
        TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();

        assertEquals(TwDynamicAttachmentsConfig.PersistenceMode.PERMANENT, rule.getPersistence());
    }

    @Test
    void childInheritsOmittedTopLevelValues() throws Exception {
        TwDynamicAttachmentsConfig parent = newConfig("parent", 25, "Moose");
        setRules(parent, rule("parent_rule", 10, "Blanket", "Blanket_Canada"));
        TwDynamicAttachmentsConfig child = newConfig("child", 0);

        child.inheritMissingTopLevelFrom(parent, Set.of("Priority"));

        assertEquals(0, child.getPriority());
        assertArrayEquals(new String[] {"Moose"}, child.getRoleIds());
        assertEquals("parent_rule", child.getRules()[0].getId());
    }

    @Test
    void explicitRuleArrayReplacesParentRules() throws Exception {
        TwDynamicAttachmentsConfig parent = newConfig("parent", 25, "Moose");
        setRules(parent, rule("parent_rule", 10, "Blanket", "Blanket_Canada"));
        TwDynamicAttachmentsConfig child = newConfig("child", 50, "Moose");
        setRules(child, rule("child_rule", 5, "Blanket", "Blanket_Red"));

        child.inheritMissingTopLevelFrom(parent, Set.of("Priority", "RoleIds", "Rules"));

        assertEquals(1, child.getRules().length);
        assertEquals("child_rule", child.getRules()[0].getId());
    }

    @Test
    void roleIndexSortsByConfigPriorityThenRulePriority() throws Exception {
        TwDynamicAttachmentsConfig low = newConfig("z_low", 10, "Moose");
        setRules(low, rule("low_rule", 500, "Blanket", "Blanket_Red"));
        TwDynamicAttachmentsConfig high = newConfig("a_high", 50, "Moose");
        setRules(high, rule("high_rule", 1, "Blanket", "Blanket_Canada"));

        Map<String, List<TwDynamicAttachmentsConfig.ResolvedRule>> index =
                TwDynamicAttachmentsConfig.buildRoleRuleIndexForTest(List.of(low, high));

        assertEquals("high_rule", index.get("moose").get(0).rule().getId());
        assertEquals("low_rule", index.get("moose").get(1).rule().getId());
    }

    @Test
    void disabledConfigsAreExcludedFromRoleIndex() throws Exception {
        TwDynamicAttachmentsConfig config = newConfig("disabled", 50, "Moose");
        setField(config, "enabled", false);
        setRules(config, rule("rule", 1, "Blanket", "Blanket_Canada"));

        Map<String, List<TwDynamicAttachmentsConfig.ResolvedRule>> index =
                TwDynamicAttachmentsConfig.buildRoleRuleIndexForTest(List.of(config));

        assertFalse(index.containsKey("moose"));
    }

    private static TwDynamicAttachmentsConfig newConfig(String id, int priority, String... roleIds) throws Exception {
        TwDynamicAttachmentsConfig config = new TwDynamicAttachmentsConfig();
        setField(config, "id", id);
        setField(config, "priority", priority);
        setField(config, "roleIds", roleIds);
        return config;
    }

    private static TwDynamicAttachmentsConfig.Rule rule(String id,
                                                        int priority,
                                                        String slot,
                                                        String attachment) throws Exception {
        TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();
        setField(rule, "id", id);
        setField(rule, "priority", priority);
        setField(rule, "attachments", Map.of(slot, attachment));
        return rule;
    }

    private static void setRules(TwDynamicAttachmentsConfig config,
                                 TwDynamicAttachmentsConfig.Rule... rules) throws Exception {
        setField(config, "rules", rules);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
```

- [ ] **Step 2: Run the config tests to verify they fail**

Run:

```powershell
.\mvnw.cmd -Dtest=TwDynamicAttachmentsConfigTest test
```

Expected: compilation fails because `TwDynamicAttachmentsConfig` does not exist.

- [ ] **Step 3: Implement `TwDynamicAttachmentsConfig`**

Create the class with asset codec fields, top-level fallback, and role-index helper. Keep it under 800 lines by limiting it to config data and role-index construction only.

```java
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped rules that conditionally apply persistent or reversible NPC attachment selections.
 * Stored under Server/Tamework/DynamicAttachments.
 */
public final class TwDynamicAttachmentsConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwDynamicAttachmentsConfig>>,
        TwParentFallbackAsset<TwDynamicAttachmentsConfig> {
    private static final Rule[] EMPTY_RULES = new Rule[0];
    private static final Condition[] EMPTY_CONDITIONS = new Condition[0];

    public enum PersistenceMode {
        PERMANENT,
        WHILE_MATCHING
    }

    public enum ConditionType {
        DISPLAY_NAME_EQUALS,
        OWNER_PRESENT,
        TAMED_STATE,
        GENDER,
        LIFE_STAGE,
        TRAIT_PRESENT,
        TRAIT_VALUE,
        HAPPINESS_AT_LEAST,
        HAPPINESS_BELOW,
        NEED_AT_LEAST,
        NEED_BELOW,
        COMMAND_STATE_EQUALS
    }

    private static final BuilderCodec<Condition> CONDITION_CODEC = BuilderCodec.builder(
            Condition.class,
            Condition::new
    )
            .<String>append(new KeyedCodec<>("Type", Codec.STRING), Condition::setTypeName, Condition::getTypeName)
            .documentation("Condition type. Supported values include DisplayNameEquals, NeedBelow, and TraitPresent.")
            .add()
            .<String>append(new KeyedCodec<>("Value", Codec.STRING), Condition::setValue, Condition::getValue)
            .documentation("String comparison value for name, gender, life stage, trait, or state conditions.")
            .add()
            .<Double>append(new KeyedCodec<>("Number", Codec.DOUBLE), Condition::setNumber, Condition::getNumber)
            .documentation("Numeric threshold for happiness, needs, and trait-value conditions.")
            .add()
            .<String>append(new KeyedCodec<>("Need", Codec.STRING), Condition::setNeed, Condition::getNeed)
            .documentation("Need ID for NeedAtLeast and NeedBelow. Built-in values are Hunger and Thirst.")
            .add()
            .<String>append(new KeyedCodec<>("TraitId", Codec.STRING), Condition::setTraitId, Condition::getTraitId)
            .documentation("Trait ID for TraitPresent and TraitValue.")
            .add()
            .<String>append(new KeyedCodec<>("State", Codec.STRING), Condition::setState, Condition::getState)
            .documentation("State key used by StateEquals.")
            .add()
            .<Boolean>append(new KeyedCodec<>("Expected", Codec.BOOLEAN), Condition::setExpected, Condition::getExpected)
            .documentation("Expected boolean for OwnerPresent and TamedState. Defaults to true when omitted.")
            .add()
            .<Boolean>append(new KeyedCodec<>("IgnoreCase", Codec.BOOLEAN), Condition::setIgnoreCase, Condition::isIgnoreCase)
            .documentation("When true, string comparisons ignore case. Defaults to true.")
            .add()
            .build();
    private static final ArrayCodec<Condition> CONDITION_ARRAY_CODEC =
            new ArrayCodec<>(CONDITION_CODEC, Condition[]::new);

    private static final BuilderCodec<Rule> RULE_CODEC = BuilderCodec.builder(Rule.class, Rule::new)
            .<String>append(new KeyedCodec<>("Id", Codec.STRING), (rule, value) -> rule.id = value, Rule::getId)
            .documentation("Stable rule ID used for diagnostics and reversible overlay tracking.")
            .add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER), (rule, value) -> rule.priority = value == null ? 0 : value, Rule::getPriority)
            .documentation("Rule priority within the config priority tier. Higher values win.")
            .add()
            .<String>append(new KeyedCodec<>("Persistence", Codec.STRING), Rule::setPersistenceName, Rule::getPersistenceName)
            .documentation("Permanent writes stored attachments. WhileMatching restores the previous slot value when conditions stop matching.")
            .add()
            .<Condition[]>append(new KeyedCodec<>("Conditions", CONDITION_ARRAY_CODEC), Rule::setConditions, Rule::getConditions)
            .documentation("AND-based rule conditions. Explicit arrays replace parent values.")
            .add()
            .<Map<String, String>>append(new KeyedCodec<>("Attachments", MapCodec.STRING_HASH_MAP_CODEC), Rule::setAttachments, Rule::getAttachments)
            .documentation("Attachment slot to value selections. Explicit maps replace parent values.")
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
            .documentation("Role-scoped dynamic attachment config. Omitted top-level fields inherit from the parent.")
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (asset, value) -> asset.enabled = value == null || value, TwDynamicAttachmentsConfig::isEnabled)
            .documentation("Turns this dynamic attachment config on or off.")
            .add()
            .<Integer>append(new KeyedCodec<>("Priority", Codec.INTEGER), (asset, value) -> asset.priority = value == null ? 0 : value, TwDynamicAttachmentsConfig::getPriority)
            .documentation("Config priority. Higher priority wins before rule priority.")
            .add()
            .<String[]>append(new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY), (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value, TwDynamicAttachmentsConfig::getRoleIds)
            .documentation("NPC role IDs this config applies to. Inheritance: explicit arrays replace parent value.")
            .add()
            .<Rule[]>append(new KeyedCodec<>("Rules", RULE_ARRAY_CODEC), (asset, value) -> asset.rules = value == null ? EMPTY_RULES : value, TwDynamicAttachmentsConfig::getRules)
            .documentation("Dynamic attachment rules. Inheritance: explicit arrays replace parent value.")
            .add()
            .build();

    private static AssetStore<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> ASSET_STORE;
    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean CACHE_DIRTY = true;
    private static volatile Map<String, List<ResolvedRule>> ROLE_RULE_INDEX = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private Rule[] rules = EMPTY_RULES;

    public static AssetStore<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwDynamicAttachmentsConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwDynamicAttachmentsConfig> getAssetMap() {
        AssetStore<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwDynamicAttachmentsConfig> assetMap =
                (DefaultAssetMap<String, TwDynamicAttachmentsConfig>) store.getAssetMap();
        if (assetMap != null && CACHE_DIRTY) {
            TwAssetInheritanceFallback.repairAll(assetMap);
        }
        return assetMap;
    }

    public static void clearCaches() {
        CACHE_DIRTY = true;
    }

    @Nonnull
    public static Map<String, List<ResolvedRule>> roleRuleIndex() {
        DefaultAssetMap<String, TwDynamicAttachmentsConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return Map.of();
        }
        if (CACHE_DIRTY) {
            synchronized (CACHE_LOCK) {
                if (CACHE_DIRTY) {
                    ROLE_RULE_INDEX = buildRoleRuleIndexForTest(new ArrayList<>(assetMap.getAssetMap().values()));
                    CACHE_DIRTY = false;
                }
            }
        }
        return ROLE_RULE_INDEX;
    }

    static Map<String, List<ResolvedRule>> buildRoleRuleIndexForTest(@Nullable List<TwDynamicAttachmentsConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return Map.of();
        }
        HashMap<String, List<ResolvedRule>> index = new HashMap<>();
        for (TwDynamicAttachmentsConfig config : configs) {
            if (config == null || !config.isEnabled()) {
                continue;
            }
            for (String roleId : config.getRoleIds()) {
                String normalizedRole = normalize(roleId);
                if (normalizedRole == null) {
                    continue;
                }
                List<ResolvedRule> rulesForRole = index.computeIfAbsent(normalizedRole, ignored -> new ArrayList<>());
                for (int i = 0; i < config.getRules().length; i++) {
                    Rule rule = config.getRules()[i];
                    if (rule != null && !rule.getAttachments().isEmpty()) {
                        rulesForRole.add(new ResolvedRule(config.getId(), config.getPriority(), i, rule));
                    }
                }
            }
        }
        for (Map.Entry<String, List<ResolvedRule>> entry : index.entrySet()) {
            entry.getValue().sort(ResolvedRule::compareForEvaluation);
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(index);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
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

    public record ResolvedRule(@Nullable String configId, int configPriority, int ruleOrder, @Nonnull Rule rule) {
        private static int compareForEvaluation(@Nonnull ResolvedRule left, @Nonnull ResolvedRule right) {
            if (left.configPriority != right.configPriority) return Integer.compare(right.configPriority, left.configPriority);
            if (left.rule.getPriority() != right.rule.getPriority()) return Integer.compare(right.rule.getPriority(), left.rule.getPriority());
            int asset = safe(left.configId).compareToIgnoreCase(safe(right.configId));
            if (asset != 0) return asset;
            return Integer.compare(left.ruleOrder, right.ruleOrder);
        }

        private static String safe(@Nullable String value) {
            return value == null ? "" : value;
        }
    }

    /** Single dynamic attachment rule. */
    public static final class Rule {
        private String id;
        private int priority;
        private PersistenceMode persistence = PersistenceMode.PERMANENT;
        private Condition[] conditions = EMPTY_CONDITIONS;
        private Map<String, String> attachments = Map.of();

        public String getId() {
            return id == null || id.isBlank() ? "unnamed_rule" : id.trim();
        }

        public int getPriority() {
            return priority;
        }

        public PersistenceMode getPersistence() {
            return persistence == null ? PersistenceMode.PERMANENT : persistence;
        }

        public String getPersistenceName() {
            return getPersistence().name();
        }

        public void setPersistenceName(String value) {
            this.persistence = parsePersistence(value);
        }

        public Condition[] getConditions() {
            return conditions == null ? EMPTY_CONDITIONS : conditions;
        }

        public void setConditions(Condition[] conditions) {
            this.conditions = conditions == null ? EMPTY_CONDITIONS : conditions;
        }

        public Map<String, String> getAttachments() {
            return attachments == null ? Map.of() : attachments;
        }

        public void setAttachments(Map<String, String> attachments) {
            this.attachments = attachments == null ? Map.of() : attachments;
        }
    }

    /** Single rule condition. */
    public static final class Condition {
        private String typeName;
        private String value;
        private Double number;
        private String need;
        private String traitId;
        private String state;
        private Boolean expected;
        private boolean ignoreCase = true;

        public ConditionType getType() {
            return parseConditionType(typeName);
        }

        public String getTypeName() {
            return typeName;
        }

        public void setTypeName(String typeName) {
            this.typeName = typeName;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public Double getNumber() {
            return number;
        }

        public void setNumber(Double number) {
            this.number = number;
        }

        public String getNeed() {
            return need;
        }

        public void setNeed(String need) {
            this.need = need;
        }

        public String getTraitId() {
            return traitId;
        }

        public void setTraitId(String traitId) {
            this.traitId = traitId;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public boolean expectedOrTrue() {
            return expected == null || expected;
        }

        public Boolean getExpected() {
            return expected;
        }

        public void setExpected(Boolean expected) {
            this.expected = expected;
        }

        public boolean isIgnoreCase() {
            return ignoreCase;
        }

        public void setIgnoreCase(Boolean ignoreCase) {
            this.ignoreCase = ignoreCase == null || ignoreCase;
        }
    }

    private static PersistenceMode parsePersistence(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return PersistenceMode.PERMANENT;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if ("WHILEMATCHING".equals(normalized)) {
            return PersistenceMode.WHILE_MATCHING;
        }
        try {
            return PersistenceMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return PersistenceMode.PERMANENT;
        }
    }

    private static ConditionType parseConditionType(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return ConditionType.DISPLAY_NAME_EQUALS;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DISPLAYNAMEEQUALS" -> ConditionType.DISPLAY_NAME_EQUALS;
            case "OWNERPRESENT" -> ConditionType.OWNER_PRESENT;
            case "TAMEDSTATE" -> ConditionType.TAMED_STATE;
            case "LIFESTAGE" -> ConditionType.LIFE_STAGE;
            case "TRAITPRESENT" -> ConditionType.TRAIT_PRESENT;
            case "TRAITVALUE" -> ConditionType.TRAIT_VALUE;
            case "HAPPINESSATLEAST" -> ConditionType.HAPPINESS_AT_LEAST;
            case "HAPPINESSBELOW" -> ConditionType.HAPPINESS_BELOW;
            case "NEEDATLEAST" -> ConditionType.NEED_AT_LEAST;
            case "NEEDBELOW" -> ConditionType.NEED_BELOW;
            case "COMMANDSTATEEQUALS" -> ConditionType.COMMAND_STATE_EQUALS;
            default -> ConditionType.valueOf(normalized);
        };
    }
}
```

- [ ] **Step 4: Run config tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TwDynamicAttachmentsConfigTest test
```

Expected: tests pass. If the compiler reports an exact codec generic mismatch, update the new codec declarations in `TwDynamicAttachmentsConfig.java` to match the pattern used by `TwAttachmentMigrationConfig.java`, then rerun this command until it passes.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfig.java src/test/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfigTest.java
git commit -m "Feat: Add dynamic attachment config model"
```

---

### Task 2: Config Registration And Editor Family

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/overrides/TwConfigFamily.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapter.java`
- Test: `src/test/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapterTest.java`

- [ ] **Step 1: Write failing family/schema tests**

Add this test to `TwConfigSchemaAdapterTest`:

```java
@Test
void dynamicAttachmentsFamilyExposesEditableFields() {
    TwConfigAssetDescriptor descriptor = descriptor(TwConfigFamily.DYNAMIC_ATTACHMENTS, "TwDynamicAttachments_Test");

    List<TwConfigEditorFieldPolicy.EditorFieldSpec> fields = TwConfigSchemaAdapter.fieldsFor(descriptor);

    assertFalse(fields.isEmpty());
    assertTrue(fields.stream().anyMatch(field -> "Rules".equals(field.path())));
    assertEquals(TwConfigFamily.DYNAMIC_ATTACHMENTS, TwConfigFamily.fromStorePath("Tamework/DynamicAttachments"));
    assertEquals("dynamic-attachments", TwConfigFamily.DYNAMIC_ATTACHMENTS.getId());
    assertEquals("Tamework/DynamicAttachments", TwConfigFamily.DYNAMIC_ATTACHMENTS.getStorePath());
}
```

Add this static import to `TwConfigSchemaAdapterTest`:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run:

```powershell
.\mvnw.cmd -Dtest=TwConfigSchemaAdapterTest test
```

Expected: fails because the family is not registered.

- [ ] **Step 3: Register the asset family in `Tamework.java`**

Make these concrete edits:

```java
import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
```

Add a boolean field beside the other asset registration booleans:

```java
private boolean dynamicAttachmentsAssetsRegistered;
```

Call registration after attachment display/migration registration:

```java
registerAttachmentMigrationAssets();
registerAttachmentDisplayAssets();
registerDynamicAttachmentsAssets();
```

Add the registration method:

```java
private void registerDynamicAttachmentsAssets() {
    if (dynamicAttachmentsAssetsRegistered) {
        return;
    }
    getAssetRegistry().register(
            HytaleAssetStore.builder(TwDynamicAttachmentsConfig.class, new DefaultAssetMap<>())
                    .setPath("Tamework/DynamicAttachments")
                    .setCodec(TwDynamicAttachmentsConfig.CODEC)
                    .setKeyFunction(TwDynamicAttachmentsConfig::getId)
                    .build()
    );
    getEventRegistry().register(
            LoadedAssetsEvent.class,
            TwDynamicAttachmentsConfig.class,
            this::onDynamicAttachmentsAssetsLoaded
    );
    getEventRegistry().register(
            RemovedAssetsEvent.class,
            TwDynamicAttachmentsConfig.class,
            this::onDynamicAttachmentsAssetsRemoved
    );
    dynamicAttachmentsAssetsRegistered = true;
}

private void onDynamicAttachmentsAssetsLoaded(
        LoadedAssetsEvent<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> event) {
    TwDynamicAttachmentsConfig.clearCaches();
    if (!event.isInitial()) {
        emitExperimentalConfigReload(TameworkConfigFamily.DYNAMIC_ATTACHMENTS, event.getLoadedAssets().keySet());
    }
}

private void onDynamicAttachmentsAssetsRemoved(
        RemovedAssetsEvent<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> event) {
    TwDynamicAttachmentsConfig.clearCaches();
    emitExperimentalConfigReload(TameworkConfigFamily.DYNAMIC_ATTACHMENTS, event.getRemovedAssets());
}
```

- [ ] **Step 4: Register the config editor family**

In `TwConfigFamily`, add the import and enum value:

```java
import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
```

```java
DYNAMIC_ATTACHMENTS("dynamic-attachments", "Dynamic Attachments", "Tamework/DynamicAttachments", true, true),
```

Add to `getAssetStore()`:

```java
case DYNAMIC_ATTACHMENTS ->
        (AssetStore<String, ?, ? extends AssetMap<String, ?>>) TwDynamicAttachmentsConfig.getAssetStore();
```

In `TwConfigSchemaAdapter`, add:

```java
import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
```

and the switch arm:

```java
case DYNAMIC_ATTACHMENTS -> TwDynamicAttachmentsConfig.CODEC;
```

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TwConfigSchemaAdapterTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/Tamework.java src/main/java/com/alechilles/alecstamework/config/overrides/TwConfigFamily.java src/main/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapter.java src/test/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapterTest.java
git commit -m "Feat: Register dynamic attachment config family"
```

---

### Task 3: Reversible Dynamic Attachment Component

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponent.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponentTest.java`

- [ ] **Step 1: Write failing component tests**

Create tests covering baseline present, baseline absent, active value tracking, and sanitization:

```java
package com.alechilles.alecstamework.npc.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkDynamicAttachmentsComponentTest {
    @Test
    void activeSlotCanRememberPreviousValue() {
        TameworkDynamicAttachmentsComponent.ActiveSlot slot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Blanket", "Blanket_Red", true, "Blanket_Canada", "rule");

        assertEquals("Blanket", slot.getSlot());
        assertTrue(slot.hasPreviousValue());
        assertEquals("Blanket_Red", slot.getPreviousValue());
        assertEquals("Blanket_Canada", slot.getAppliedValue());
    }

    @Test
    void activeSlotCanRememberPreviouslyAbsentValue() {
        TameworkDynamicAttachmentsComponent.ActiveSlot slot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Blanket", null, false, "Blanket_Canada", "rule");

        assertFalse(slot.hasPreviousValue());
        assertEquals("Blanket_Canada", slot.getAppliedValue());
    }

    @Test
    void componentFiltersInvalidSlots() {
        TameworkDynamicAttachmentsComponent component = new TameworkDynamicAttachmentsComponent(new TameworkDynamicAttachmentsComponent.ActiveSlot[] {
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Blanket", null, false, "Blanket_Canada", "rule"),
                new TameworkDynamicAttachmentsComponent.ActiveSlot("", "old", true, "new", "bad")
        });

        assertEquals(1, component.getActiveSlots().length);
    }
}
```

- [ ] **Step 2: Run component tests to verify they fail**

```powershell
.\mvnw.cmd -Dtest=TameworkDynamicAttachmentsComponentTest test
```

Expected: compilation fails because the component does not exist.

- [ ] **Step 3: Implement `TameworkDynamicAttachmentsComponent`**

```java
package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Stores reversible dynamic attachment overlay state for WhileMatching rules.
 */
public final class TameworkDynamicAttachmentsComponent implements Component<EntityStore> {
    private static final ActiveSlot[] EMPTY_ACTIVE_SLOTS = new ActiveSlot[0];
    private static final BuilderCodec<ActiveSlot> ACTIVE_SLOT_CODEC = BuilderCodec.builder(
            ActiveSlot.class,
            ActiveSlot::new
    )
            .append(new KeyedCodec<>("Slot", Codec.STRING), ActiveSlot::setSlot, ActiveSlot::getSlot)
            .add()
            .append(new KeyedCodec<>("PreviousValue", Codec.STRING), ActiveSlot::setPreviousValue, ActiveSlot::getPreviousValue)
            .add()
            .append(new KeyedCodec<>("HasPreviousValue", Codec.BOOLEAN), ActiveSlot::setHasPreviousValue, ActiveSlot::hasPreviousValue)
            .add()
            .append(new KeyedCodec<>("AppliedValue", Codec.STRING), ActiveSlot::setAppliedValue, ActiveSlot::getAppliedValue)
            .add()
            .append(new KeyedCodec<>("RuleKey", Codec.STRING), ActiveSlot::setRuleKey, ActiveSlot::getRuleKey)
            .add()
            .build();
    private static final ArrayCodec<ActiveSlot> ACTIVE_SLOT_ARRAY_CODEC =
            new ArrayCodec<>(ACTIVE_SLOT_CODEC, ActiveSlot[]::new);

    public static final BuilderCodec<TameworkDynamicAttachmentsComponent> CODEC = BuilderCodec.builder(
            TameworkDynamicAttachmentsComponent.class,
            TameworkDynamicAttachmentsComponent::new
    )
            .append(
                    new KeyedCodec<>("ActiveSlots", ACTIVE_SLOT_ARRAY_CODEC),
                    TameworkDynamicAttachmentsComponent::setActiveSlots,
                    TameworkDynamicAttachmentsComponent::getActiveSlots
            )
            .add()
            .build();

    private ActiveSlot[] activeSlots = EMPTY_ACTIVE_SLOTS;

    public TameworkDynamicAttachmentsComponent() {
    }

    public TameworkDynamicAttachmentsComponent(@Nullable ActiveSlot[] activeSlots) {
        setActiveSlots(activeSlots);
    }

    public static ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getDynamicAttachmentsComponentType() : null;
    }

    public ActiveSlot[] getActiveSlots() {
        return activeSlots == null ? EMPTY_ACTIVE_SLOTS : activeSlots;
    }

    public void setActiveSlots(@Nullable ActiveSlot[] activeSlots) {
        this.activeSlots = sanitize(activeSlots);
    }

    @Override
    public TameworkDynamicAttachmentsComponent clone() {
        ActiveSlot[] slots = getActiveSlots();
        ActiveSlot[] cloned = new ActiveSlot[slots.length];
        for (int i = 0; i < slots.length; i++) {
            cloned[i] = slots[i] == null ? null : slots[i].clone();
        }
        return new TameworkDynamicAttachmentsComponent(cloned);
    }

    private static ActiveSlot[] sanitize(@Nullable ActiveSlot[] slots) {
        if (slots == null || slots.length == 0) {
            return EMPTY_ACTIVE_SLOTS;
        }
        List<ActiveSlot> values = new ArrayList<>(slots.length);
        for (ActiveSlot slot : slots) {
            if (slot == null || slot.getSlot() == null || slot.getAppliedValue() == null || slot.getRuleKey() == null) {
                continue;
            }
            values.add(slot.clone());
        }
        return values.isEmpty() ? EMPTY_ACTIVE_SLOTS : values.toArray(new ActiveSlot[0]);
    }

    /** Baseline and active value for a single temporary attachment slot. */
    public static final class ActiveSlot {
        private String slot;
        private String previousValue;
        private boolean hasPreviousValue;
        private String appliedValue;
        private String ruleKey;

        public ActiveSlot() {
        }

        public ActiveSlot(String slot, String previousValue, boolean hasPreviousValue, String appliedValue, String ruleKey) {
            setSlot(slot);
            setPreviousValue(previousValue);
            this.hasPreviousValue = hasPreviousValue;
            setAppliedValue(appliedValue);
            setRuleKey(ruleKey);
        }

        public String getSlot() {
            return clean(slot);
        }

        public void setSlot(String slot) {
            this.slot = clean(slot);
        }

        public String getPreviousValue() {
            return clean(previousValue);
        }

        public void setPreviousValue(String previousValue) {
            this.previousValue = clean(previousValue);
        }

        public boolean hasPreviousValue() {
            return hasPreviousValue;
        }

        public void setHasPreviousValue(boolean hasPreviousValue) {
            this.hasPreviousValue = hasPreviousValue;
        }

        public String getAppliedValue() {
            return clean(appliedValue);
        }

        public void setAppliedValue(String appliedValue) {
            this.appliedValue = clean(appliedValue);
        }

        public String getRuleKey() {
            return clean(ruleKey);
        }

        public void setRuleKey(String ruleKey) {
            this.ruleKey = clean(ruleKey);
        }

        public ActiveSlot clone() {
            return new ActiveSlot(slot, previousValue, hasPreviousValue, appliedValue, ruleKey);
        }

        private static String clean(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
```

- [ ] **Step 4: Register component in `Tamework.java`**

Add import, field, registration, and getter:

```java
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
```

```java
private ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> dynamicAttachmentsComponentType;
```

Register after `TameworkAttachments`:

```java
dynamicAttachmentsComponentType = getEntityStoreRegistry().registerComponent(
        TameworkDynamicAttachmentsComponent.class,
        "TameworkDynamicAttachments",
        TameworkDynamicAttachmentsComponent.CODEC
);
```

Getter:

```java
public ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> getDynamicAttachmentsComponentType() {
    return dynamicAttachmentsComponentType;
}
```

- [ ] **Step 5: Run component tests**

```powershell
.\mvnw.cmd -Dtest=TameworkDynamicAttachmentsComponentTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponent.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponentTest.java
git commit -m "Feat: Add dynamic attachment overlay component"
```

---

### Task 4: Snapshot And Condition Evaluation

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentNpcSnapshot.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConditionEvaluator.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConditionEvaluatorTest.java`

- [ ] **Step 1: Write failing condition tests**

```java
package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentConditionEvaluatorTest {
    @Test
    void displayNameEqualsIgnoresCaseByDefault() {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .displayName("Flash")
                .build();

        assertTrue(DynamicAttachmentConditionEvaluator.matches(condition("DisplayNameEquals", "flash"), snapshot));
    }

    @Test
    void needBelowMatchesNamedNeed() {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .needs(Map.of("hunger", 24.0))
                .build();

        TwDynamicAttachmentsConfig.Condition condition = condition("NeedBelow", null);
        condition.setNeed("Hunger");
        condition.setNumber(25.0);

        assertTrue(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    @Test
    void needBelowStopsMatchingAboveThreshold() {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .needs(Map.of("hunger", 25.0))
                .build();

        TwDynamicAttachmentsConfig.Condition condition = condition("NeedBelow", null);
        condition.setNeed("Hunger");
        condition.setNumber(25.0);

        assertFalse(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    @Test
    void traitValueUsesNumericThreshold() {
        DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
                .traits(Map.of("speed", 1.25))
                .build();

        TwDynamicAttachmentsConfig.Condition condition = condition("TraitValue", null);
        condition.setTraitId("Speed");
        condition.setNumber(1.0);

        assertTrue(DynamicAttachmentConditionEvaluator.matches(condition, snapshot));
    }

    private static TwDynamicAttachmentsConfig.Condition condition(String type, String value) {
        TwDynamicAttachmentsConfig.Condition condition = new TwDynamicAttachmentsConfig.Condition();
        condition.setTypeName(type);
        condition.setValue(value);
        return condition;
    }
}
```

- [ ] **Step 2: Run condition tests to verify they fail**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentConditionEvaluatorTest test
```

Expected: compilation fails because snapshot/evaluator classes do not exist.

- [ ] **Step 3: Implement snapshot and evaluator**

Use normalized lower-case keys for traits, needs, and command states.

```java
package com.alechilles.alecstamework.npc.dynamicattachments;

import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Allocation-light value snapshot used for dynamic attachment condition checks.
 */
public final class DynamicAttachmentNpcSnapshot {
    private final String roleId;
    private final String displayName;
    private final boolean ownerPresent;
    private final boolean tamed;
    private final String gender;
    private final String lifeStage;
    private final double happiness;
    private final Map<String, Double> needs;
    private final Map<String, Double> traits;
    private final Map<String, String> commandStates;

    private DynamicAttachmentNpcSnapshot(Builder builder) {
        this.roleId = builder.roleId;
        this.displayName = builder.displayName;
        this.ownerPresent = builder.ownerPresent;
        this.tamed = builder.tamed;
        this.gender = builder.gender;
        this.lifeStage = builder.lifeStage;
        this.happiness = builder.happiness;
        this.needs = builder.needs == null ? Map.of() : builder.needs;
        this.traits = builder.traits == null ? Map.of() : builder.traits;
        this.commandStates = builder.commandStates == null ? Map.of() : builder.commandStates;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable public String roleId() { return roleId; }
    @Nullable public String displayName() { return displayName; }
    public boolean ownerPresent() { return ownerPresent; }
    public boolean tamed() { return tamed; }
    @Nullable public String gender() { return gender; }
    @Nullable public String lifeStage() { return lifeStage; }
    public double happiness() { return happiness; }
    @Nonnull public Map<String, Double> needs() { return needs; }
    @Nonnull public Map<String, Double> traits() { return traits; }
    @Nonnull public Map<String, String> commandStates() { return commandStates; }

    @Nullable
    static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Builder {
        private String roleId;
        private String displayName;
        private boolean ownerPresent;
        private boolean tamed;
        private String gender;
        private String lifeStage;
        private double happiness;
        private Map<String, Double> needs = Map.of();
        private Map<String, Double> traits = Map.of();
        private Map<String, String> commandStates = Map.of();

        public Builder roleId(String roleId) { this.roleId = roleId; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder ownerPresent(boolean ownerPresent) { this.ownerPresent = ownerPresent; return this; }
        public Builder tamed(boolean tamed) { this.tamed = tamed; return this; }
        public Builder gender(String gender) { this.gender = gender; return this; }
        public Builder lifeStage(String lifeStage) { this.lifeStage = lifeStage; return this; }
        public Builder happiness(double happiness) { this.happiness = happiness; return this; }
        public Builder needs(Map<String, Double> needs) { this.needs = needs; return this; }
        public Builder traits(Map<String, Double> traits) { this.traits = traits; return this; }
        public Builder commandStates(Map<String, String> commandStates) { this.commandStates = commandStates; return this; }
        public DynamicAttachmentNpcSnapshot build() { return new DynamicAttachmentNpcSnapshot(this); }
    }
}
```

```java
package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure condition evaluator for dynamic attachment rules.
 */
public final class DynamicAttachmentConditionEvaluator {
    private DynamicAttachmentConditionEvaluator() {
    }

    public static boolean matches(@Nullable TwDynamicAttachmentsConfig.Condition condition,
                                  @Nonnull DynamicAttachmentNpcSnapshot snapshot) {
        if (condition == null) {
            return false;
        }
        return switch (condition.getType()) {
            case DISPLAY_NAME_EQUALS -> stringEquals(snapshot.displayName(), condition.getValue(), condition.isIgnoreCase());
            case OWNER_PRESENT -> snapshot.ownerPresent() == condition.expectedOrTrue();
            case TAMED_STATE -> snapshot.tamed() == condition.expectedOrTrue();
            case GENDER -> stringEquals(snapshot.gender(), condition.getValue(), condition.isIgnoreCase());
            case LIFE_STAGE -> stringEquals(snapshot.lifeStage(), condition.getValue(), condition.isIgnoreCase());
            case TRAIT_PRESENT -> snapshot.traits().containsKey(DynamicAttachmentNpcSnapshot.normalize(condition.getTraitId()));
            case TRAIT_VALUE -> numberAtLeast(snapshot.traits().get(DynamicAttachmentNpcSnapshot.normalize(condition.getTraitId())), condition.getNumber());
            case HAPPINESS_AT_LEAST -> condition.getNumber() != null && snapshot.happiness() >= condition.getNumber();
            case HAPPINESS_BELOW -> condition.getNumber() != null && snapshot.happiness() < condition.getNumber();
            case NEED_AT_LEAST -> numberAtLeast(snapshot.needs().get(DynamicAttachmentNpcSnapshot.normalize(condition.getNeed())), condition.getNumber());
            case NEED_BELOW -> numberBelow(snapshot.needs().get(DynamicAttachmentNpcSnapshot.normalize(condition.getNeed())), condition.getNumber());
            case COMMAND_STATE_EQUALS -> stringEquals(
                    snapshot.commandStates().get(DynamicAttachmentNpcSnapshot.normalize(condition.getState())),
                    condition.getValue(),
                    condition.isIgnoreCase()
            );
        };
    }

    private static boolean numberAtLeast(@Nullable Double value, @Nullable Double threshold) {
        return value != null && threshold != null && value >= threshold;
    }

    private static boolean numberBelow(@Nullable Double value, @Nullable Double threshold) {
        return value != null && threshold != null && value < threshold;
    }

    private static boolean stringEquals(@Nullable String actual, @Nullable String expected, boolean ignoreCase) {
        if (actual == null || expected == null) {
            return false;
        }
        return ignoreCase ? actual.trim().equalsIgnoreCase(expected.trim()) : actual.trim().equals(expected.trim());
    }
}
```

- [ ] **Step 4: Run condition tests**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentConditionEvaluatorTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentNpcSnapshot.java src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConditionEvaluator.java src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConditionEvaluatorTest.java
git commit -m "Feat: Add dynamic attachment condition evaluator"
```

---

### Task 5: Config Index And Rule Resolution

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConfigIndex.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentResolution.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentRuleResolver.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConfigIndexTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentRuleResolverTest.java`

- [ ] **Step 1: Write failing resolver tests**

Cover slot priority and mode separation:

```java
@Test
void higherConfigPriorityWinsSlotEvenWhenLowerConfigRulePriorityIsHigher() {
    DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
            DynamicAttachmentNpcSnapshot.builder().displayName("Flash").build(),
            List.of(
                    resolved("low", 10, 500, "Blanket", "Blanket_Red", TwDynamicAttachmentsConfig.PersistenceMode.PERMANENT),
                    resolved("high", 50, 1, "Blanket", "Blanket_Canada", TwDynamicAttachmentsConfig.PersistenceMode.PERMANENT)
            )
    );

    assertEquals("Blanket_Canada", resolution.permanentAttachments().get("Blanket"));
}

@Test
void whileMatchingSelectionsAreReturnedSeparately() {
    DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
            DynamicAttachmentNpcSnapshot.builder().needs(Map.of("hunger", 20.0)).build(),
            List.of(resolved("cfg", 50, 1, "Blanket", "Blanket_Canada", TwDynamicAttachmentsConfig.PersistenceMode.WHILE_MATCHING))
    );

    assertTrue(resolution.permanentAttachments().isEmpty());
    assertEquals("Blanket_Canada", resolution.temporaryAttachments().get("Blanket").value());
}
```

Add these helpers to `DynamicAttachmentRuleResolverTest`:

```java
private static TwDynamicAttachmentsConfig.ResolvedRule resolved(String configId,
                                                                int configPriority,
                                                                int rulePriority,
                                                                String slot,
                                                                String value,
                                                                TwDynamicAttachmentsConfig.PersistenceMode mode) throws Exception {
    TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();
    setField(rule, "id", slot + "_" + value);
    setField(rule, "priority", rulePriority);
    setField(rule, "persistence", mode);
    setField(rule, "attachments", Map.of(slot, value));
    setField(rule, "conditions", new TwDynamicAttachmentsConfig.Condition[0]);
    return new TwDynamicAttachmentsConfig.ResolvedRule(configId, configPriority, 0, rule);
}

private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
}
```

Add these imports to the same test:

```java
import java.lang.reflect.Field;
import java.util.Map;
```

- [ ] **Step 2: Run resolver tests to verify they fail**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentRuleResolverTest,DynamicAttachmentConfigIndexTest test
```

Expected: compilation fails because resolver/index classes do not exist.

- [ ] **Step 3: Implement result and resolver classes**

`DynamicAttachmentResolution`:

```java
package com.alechilles.alecstamework.npc.dynamicattachments;

import java.util.Map;
import javax.annotation.Nonnull;

public record DynamicAttachmentResolution(
        @Nonnull Map<String, String> permanentAttachments,
        @Nonnull Map<String, TemporaryAttachment> temporaryAttachments
) {
    public static DynamicAttachmentResolution empty() {
        return new DynamicAttachmentResolution(Map.of(), Map.of());
    }

    public record TemporaryAttachment(@Nonnull String value, @Nonnull String ruleKey) {
    }
}
```

`DynamicAttachmentRuleResolver`:

```java
package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves winning dynamic attachment selections for one NPC snapshot.
 */
public final class DynamicAttachmentRuleResolver {
    private DynamicAttachmentRuleResolver() {
    }

    public static DynamicAttachmentResolution resolve(@Nonnull DynamicAttachmentNpcSnapshot snapshot,
                                                      @Nullable List<TwDynamicAttachmentsConfig.ResolvedRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return DynamicAttachmentResolution.empty();
        }
        HashMap<String, String> permanent = new HashMap<>();
        HashMap<String, DynamicAttachmentResolution.TemporaryAttachment> temporary = new HashMap<>();
        for (TwDynamicAttachmentsConfig.ResolvedRule resolved : rules) {
            if (resolved == null || !matchesAll(resolved.rule(), snapshot)) {
                continue;
            }
            for (Map.Entry<String, String> entry : resolved.rule().getAttachments().entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                String slot = entry.getKey().trim();
                String value = entry.getValue().trim();
                if (resolved.rule().getPersistence() == TwDynamicAttachmentsConfig.PersistenceMode.WHILE_MATCHING) {
                    temporary.putIfAbsent(slot, new DynamicAttachmentResolution.TemporaryAttachment(value, ruleKey(resolved)));
                } else {
                    permanent.putIfAbsent(slot, value);
                }
            }
        }
        return new DynamicAttachmentResolution(Map.copyOf(permanent), Map.copyOf(temporary));
    }

    private static boolean matchesAll(@Nonnull TwDynamicAttachmentsConfig.Rule rule,
                                      @Nonnull DynamicAttachmentNpcSnapshot snapshot) {
        for (TwDynamicAttachmentsConfig.Condition condition : rule.getConditions()) {
            if (!DynamicAttachmentConditionEvaluator.matches(condition, snapshot)) {
                return false;
            }
        }
        return true;
    }

    private static String ruleKey(@Nonnull TwDynamicAttachmentsConfig.ResolvedRule rule) {
        return (rule.configId() == null ? "" : rule.configId()) + ":" + rule.rule().getId();
    }
}
```

`DynamicAttachmentConfigIndex`:

```java
package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Read-only role-indexed view of dynamic attachment rules.
 */
public final class DynamicAttachmentConfigIndex {
    private final Map<String, List<TwDynamicAttachmentsConfig.ResolvedRule>> rulesByRole;

    private DynamicAttachmentConfigIndex(@Nonnull Map<String, List<TwDynamicAttachmentsConfig.ResolvedRule>> rulesByRole) {
        this.rulesByRole = rulesByRole;
    }

    public static DynamicAttachmentConfigIndex current() {
        return new DynamicAttachmentConfigIndex(TwDynamicAttachmentsConfig.roleRuleIndex());
    }

    public boolean hasRulesForRole(@Nullable String roleId) {
        return !rulesForRole(roleId).isEmpty();
    }

    @Nonnull
    public List<TwDynamicAttachmentsConfig.ResolvedRule> rulesForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return List.of();
        }
        List<TwDynamicAttachmentsConfig.ResolvedRule> rules =
                rulesByRole.get(roleId.trim().toLowerCase(Locale.ROOT));
        return rules == null ? List.of() : rules;
    }
}
```

- [ ] **Step 4: Run resolver/index tests**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentRuleResolverTest,DynamicAttachmentConfigIndexTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConfigIndex.java src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentResolution.java src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentRuleResolver.java src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentConfigIndexTest.java src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentRuleResolverTest.java
git commit -m "Feat: Resolve dynamic attachment rules"
```

---

### Task 6: Application Service For Permanent And WhileMatching State

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationService.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationServiceTest.java`

- [ ] **Step 1: Write failing application tests**

Use pure map/component methods first. Required cases:

```java
@Test
void permanentMergePreservesUnrelatedSlots() {
    Map<String, String> result = DynamicAttachmentApplicationService.mergePermanent(
            Map.of("Coat", "Brown"),
            Map.of("Blanket", "Blanket_Canada")
    );

    assertEquals(Map.of("Coat", "Brown", "Blanket", "Blanket_Canada"), result);
}

@Test
void whileMatchingCapturesPreviousValueAndRestoresIt() {
    DynamicAttachmentApplicationService.OverlayMerge merge = DynamicAttachmentApplicationService.mergeTemporary(
            Map.of("Blanket", "Blanket_Red"),
            null,
            Map.of("Blanket", new DynamicAttachmentResolution.TemporaryAttachment("Blanket_Canada", "cfg:hungry"))
    );

    assertEquals("Blanket_Canada", merge.attachments().get("Blanket"));
    assertEquals("Blanket_Red", merge.overlay().getActiveSlots()[0].getPreviousValue());

    Map<String, String> restored = DynamicAttachmentApplicationService.restoreInactiveTemporarySlots(
            merge.attachments(),
            merge.overlay(),
            Map.of()
    ).attachments();

    assertEquals("Blanket_Red", restored.get("Blanket"));
}

@Test
void whileMatchingRestoresAbsentSlotByRemovingIt() {
    DynamicAttachmentApplicationService.OverlayMerge merge = DynamicAttachmentApplicationService.mergeTemporary(
            Map.of(),
            null,
            Map.of("Blanket", new DynamicAttachmentResolution.TemporaryAttachment("Blanket_Canada", "cfg:hungry"))
    );

    DynamicAttachmentApplicationService.OverlayMerge restored =
            DynamicAttachmentApplicationService.restoreInactiveTemporarySlots(merge.attachments(), merge.overlay(), Map.of());

    assertFalse(restored.attachments().containsKey("Blanket"));
}

@Test
void restoreDoesNotOverwriteExternalChange() {
    TameworkDynamicAttachmentsComponent overlay = new TameworkDynamicAttachmentsComponent(new TameworkDynamicAttachmentsComponent.ActiveSlot[] {
            new TameworkDynamicAttachmentsComponent.ActiveSlot("Blanket", "Blanket_Red", true, "Blanket_Canada", "cfg:hungry")
    });

    DynamicAttachmentApplicationService.OverlayMerge restored =
            DynamicAttachmentApplicationService.restoreInactiveTemporarySlots(
                    Map.of("Blanket", "Blanket_Blue"),
                    overlay,
                    Map.of()
            );

    assertEquals("Blanket_Blue", restored.attachments().get("Blanket"));
    assertEquals(0, restored.overlay().getActiveSlots().length);
}
```

- [ ] **Step 2: Run application tests to verify they fail**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentApplicationServiceTest test
```

Expected: compilation fails because service does not exist.

- [ ] **Step 3: Implement pure merge/restore helpers**

Implement these public static helpers first:

```java
public static Map<String, String> mergePermanent(Map<String, String> current, Map<String, String> permanent)
public static OverlayMerge mergeTemporary(Map<String, String> current, TameworkDynamicAttachmentsComponent overlay, Map<String, TemporaryAttachment> temporary)
public static OverlayMerge restoreInactiveTemporarySlots(Map<String, String> current, TameworkDynamicAttachmentsComponent overlay, Map<String, TemporaryAttachment> activeTemporary)
public record OverlayMerge(Map<String, String> attachments, TameworkDynamicAttachmentsComponent overlay) {}
```

Important implementation rules:

- Capture `previousValue` from the current map before writing a temporary value.
- Set `hasPreviousValue=false` when the current map lacks the slot.
- During restore, only restore/remove a slot if `current.get(slot)` still equals `appliedValue`.
- Always remove stale overlay slots after restore attempt, even when skipping overwrite because an external change occurred.
- Return unmodifiable maps with `Map.copyOf(...)`.

- [ ] **Step 4: Add ECS-facing apply method**

Add a method that takes current components and returns new component instances without mutating the store:

```java
public static ApplyResult applyResolution(@Nullable TameworkAttachmentsComponent stored,
                                          @Nullable TameworkDynamicAttachmentsComponent overlay,
                                          @Nonnull DynamicAttachmentResolution resolution)
```

Return:

```java
public record ApplyResult(@Nonnull TameworkAttachmentsComponent attachments,
                          @Nonnull TameworkDynamicAttachmentsComponent overlay,
                          boolean changed) {
}
```

This lets `DynamicAttachmentEvaluationSystem` write through `CommandBuffer` and keeps direct store writes out of the system.

- [ ] **Step 5: Run application tests**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentApplicationServiceTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationService.java src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationServiceTest.java
git commit -m "Feat: Apply dynamic attachment persistence modes"
```

---

### Task 7: Snapshot Reader And Runtime Evaluation System

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentSnapshotReader.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystemTest.java`

- [ ] **Step 1: Write focused system unit tests**

Test pure helpers on the system:

```java
@Test
void shouldEvaluateSkipsUnconfiguredRoles() {
    DynamicAttachmentConfigIndex index = DynamicAttachmentConfigIndex.emptyForTest();

    assertFalse(DynamicAttachmentEvaluationSystem.shouldEvaluateRole("Moose", index));
}

@Test
void unchangedFingerprintSkipsEvaluation() {
    DynamicAttachmentNpcSnapshot snapshot = DynamicAttachmentNpcSnapshot.builder()
            .roleId("Moose")
            .displayName("Flash")
            .build();
    long fingerprint = DynamicAttachmentEvaluationSystem.fingerprintForTest(snapshot);

    assertEquals(fingerprint, DynamicAttachmentEvaluationSystem.fingerprintForTest(snapshot));
}
```

Add these test helpers to `DynamicAttachmentConfigIndex`:

```java
static DynamicAttachmentConfigIndex emptyForTest() {
    return new DynamicAttachmentConfigIndex(Map.of());
}

static DynamicAttachmentConfigIndex forTest(Map<String, List<TwDynamicAttachmentsConfig.ResolvedRule>> rulesByRole) {
    return new DynamicAttachmentConfigIndex(rulesByRole);
}
```

- [ ] **Step 2: Run system tests to verify they fail**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentEvaluationSystemTest test
```

Expected: compilation fails because the system does not exist.

- [ ] **Step 3: Implement `DynamicAttachmentSnapshotReader`**

Read from existing component APIs:

- Role: `CompanionRoleIdResolver.resolveRoleId(ref, store)`
- Display name: `NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(ref, store)`
- Owner: `TameworkOwnerComponent.hasOwner()`
- Tamed: `TameworkTamedComponent.isTamed()`
- Gender/life stage: `TameworkLifeStageComponent`
- Happiness: `TameworkHappinessComponent.getValue()`
- Needs: `TameworkNeedsComponent.getHunger()` and `getThirst()`, normalized as `hunger` and `thirst`
- Traits: `TameworkTraitsComponent.getTraitValues()`
- Command state: expose `has_home=true|false` and `linked_tool_count=<count>` as first-version synthetic command states from `TameworkCommandLinksComponent` because no richer command-state component exists.

Do not access `Player` components.

- [ ] **Step 4: Implement `DynamicAttachmentEvaluationSystem`**

System requirements:

- Extend `TickingSystem<EntityStore>`.
- Sweep every `1500L` ms per store with small jitter derived from `System.identityHashCode(store) % 500`.
- Query `NPCEntity.getComponentType()` only; optional components are read from the store after role filtering.
- Build `DynamicAttachmentConfigIndex.current()` once per sweep.
- Skip refs whose role has no configured rules.
- Maintain `Map<UUID, Long> lastFingerprintByNpc` using `NPCEntity.getUuid()` when available.
- Use `CommandBuffer` inside `store.forEachChunk(..., (chunk, commandBuffer) -> ...)` for component writes.
- Never call `store.putComponent` from this system.

Core write shape:

```java
DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(snapshot, index.rulesForRole(snapshot.roleId()));
DynamicAttachmentApplicationService.ApplyResult applied =
        DynamicAttachmentApplicationService.applyResolution(storedAttachments, dynamicOverlay, resolution);
if (applied.changed()) {
    commandBuffer.putComponent(ref, attachmentsType, applied.attachments());
    commandBuffer.putComponent(ref, dynamicAttachmentsType, applied.overlay());
}
```

If immediate model application is needed, do not write `ModelComponent` directly in this system. Let `CompanionAttachmentSyncSystem` pick up the changed stored attachment component on its next sweep. This keeps ECS writes narrow and avoids duplicating model mutation.

- [ ] **Step 5: Register the system in `Tamework.java`**

After `CompanionAttachmentSyncSystem` registration:

```java
getEntityStoreRegistry().registerSystem(
        new DynamicAttachmentEvaluationSystem(
                NPCEntity.getComponentType(),
                attachmentsComponentType,
                dynamicAttachmentsComponentType
        )
);
```

Constructor parameters should include every component type the system reads frequently: `NPCEntity`, `TameworkAttachmentsComponent`, `TameworkDynamicAttachmentsComponent`, `TameworkOwnerComponent`, `TameworkTamedComponent`, `TameworkHappinessComponent`, `TameworkNeedsComponent`, `TameworkTraitsComponent`, `TameworkLifeStageComponent`, and `TameworkCommandLinksComponent`.

- [ ] **Step 6: Run focused system and ECS guard tests**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentEvaluationSystemTest,EcsWriteSafetyGuardTest test
```

Expected: pass. `EcsWriteSafetyGuardTest` must not require an allowlist change.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentSnapshotReader.java src/main/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystem.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystemTest.java
git commit -m "Feat: Evaluate dynamic attachment rules at runtime"
```

---

### Task 8: Attachment Validation And Sync Integration

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationService.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationServiceTest.java`

- [ ] **Step 1: Write failing unsupported-slot test**

Add a test that demonstrates unsupported slot/value pairs are filtered before persistence when model options are provided:

```java
@Test
void filtersUnsupportedWinningAttachmentsWhenOptionsAreAvailable() {
    Map<String, String> filtered = DynamicAttachmentApplicationService.filterSupportedSelections(
            Map.of("Blanket", "Blanket_Canada", "Invalid", "Missing"),
            Map.of("Blanket", Set.of("Blanket_Canada"))
    );

    assertEquals(Map.of("Blanket", "Blanket_Canada"), filtered);
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentApplicationServiceTest test
```

Expected: fails because `filterSupportedSelections` does not exist.

- [ ] **Step 3: Implement filtering using existing model service semantics**

Add:

```java
public static Map<String, String> filterSupportedSelections(Map<String, String> selections,
                                                            Map<String, Set<String>> attachmentOptions) {
    return CompanionModelAttachmentService.filterAttachmentSelections(selections, attachmentOptions);
}
```

In the runtime path, resolve options with:

```java
Map<String, Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(
        CompanionModelAttachmentService.resolveModelAsset(ref, store)
);
```

Filter only the proposed winning changes before `ApplyResult` is written. If no model/options are available, skip applying changed slots and leave existing attachments unchanged.

- [ ] **Step 4: Run application and attachment state tests**

```powershell
.\mvnw.cmd -Dtest=DynamicAttachmentApplicationServiceTest,CompanionAttachmentStateServiceTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationService.java src/test/java/com/alechilles/alecstamework/npc/dynamicattachments/DynamicAttachmentApplicationServiceTest.java
git commit -m "Feat: Validate dynamic attachment selections"
```

---

### Task 9: Documentation, Example Asset, And Changelog

**Files:**
- Modify: `docs/Config-Discovery.md`
- Modify: `wiki/Modder-Documentation/Start-Here/Config-Discovery-Resolution-and-Inheritance.md`
- Create: `wiki/Modder-Documentation/Config-Reference/TwDynamicAttachmentsConfig-Reference.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/agents/generated-index.md`

- [ ] **Step 1: Update config discovery docs**

Add `TwDynamicAttachmentsConfig` path:

```markdown
- `TwDynamicAttachmentsConfig`: `<ModRoot>/Server/Tamework/DynamicAttachments/*.json`
```

Add it under role-scoped families:

```markdown
- `TwDynamicAttachmentsConfig`
```

Describe reload behavior:

```markdown
`TwDynamicAttachmentsConfig` is asset-registry driven and updates through normal loaded/removed asset events. It is not reloaded by `/tw reloadconfig`.
```

- [ ] **Step 2: Add config reference page**

Create `TwDynamicAttachmentsConfig-Reference.md`:

```markdown
---
title: "TwDynamicAttachmentsConfig Reference"
order: 20
published: true
draft: false
---
# TwDynamicAttachmentsConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwDynamicAttachmentsConfig` changes NPC attachment selections when configured conditions match.

## Path
`Server/Tamework/DynamicAttachments/*.json`

## Resolution
- Role-scoped by `RoleIds`
- Higher config `Priority` wins before rule `Priority`
- Ties are deterministic by asset ID and rule order

## Persistence Modes
- `Permanent`: writes selected slots into stored attachment state.
- `WhileMatching`: applies a reversible overlay. The first time the rule applies, Tamework stores the previous slot value. When the rule stops matching, Tamework restores that value. If the slot was absent before the rule applied, restore removes the slot.

## Example: Named Moose Blanket
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": ["Moose"],
  "Rules": [
    {
      "Id": "flash_canada_blanket",
      "Priority": 100,
      "Persistence": "Permanent",
      "Conditions": [
        {
          "Type": "DisplayNameEquals",
          "Value": "Flash",
          "IgnoreCase": true
        }
      ],
      "Attachments": {
        "Blanket": "Blanket_Canada"
      }
    }
  ]
}
```

## Example: Temporary Hunger Blanket
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": ["Moose"],
  "Rules": [
    {
      "Id": "hungry_blanket",
      "Priority": 100,
      "Persistence": "WhileMatching",
      "Conditions": [
        {
          "Type": "NeedBelow",
          "Need": "Hunger",
          "Number": 25
        }
      ],
      "Attachments": {
        "Blanket": "Blanket_Canada"
      }
    }
  ]
}
```
```

- [ ] **Step 3: Update changelog**

Add a player/modder-facing bullet under the current unreleased section:

```markdown
- Added `TwDynamicAttachmentsConfig`, letting mod packs change NPC attachments from configurable conditions such as display names, needs, life stage, traits, and tamed state. Rules can be permanent or reversible while their conditions match.
```

- [ ] **Step 4: Rebuild generated agent index**

Run:

```powershell
.\scripts\tools\build-agent-index.ps1
```

Expected: updates `docs/agents/generated-index.md` if new files are indexed.

- [ ] **Step 5: Run agent docs check**

Run:

```powershell
.\scripts\tools\check-agent-docs.ps1
```

Expected: pass.

- [ ] **Step 6: Commit**

```powershell
git add docs/Config-Discovery.md wiki/Modder-Documentation/Start-Here/Config-Discovery-Resolution-and-Inheritance.md wiki/Modder-Documentation/Config-Reference/TwDynamicAttachmentsConfig-Reference.md CHANGELOG.md docs/agents/generated-index.md
git commit -m "Docs: Document dynamic attachment config"
```

---

### Task 10: Full Verification

**Files:**
- Modify only files that fail verification commands in this task.

- [ ] **Step 1: Run thread-safety grep**

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no new matches in dynamic attachment runtime paths.

- [ ] **Step 2: Run safety guard tests**

```powershell
.\mvnw.cmd -Dtest=EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: pass.

- [ ] **Step 3: Run focused dynamic attachment tests**

```powershell
.\mvnw.cmd -Dtest=TwDynamicAttachmentsConfigTest,TameworkDynamicAttachmentsComponentTest,DynamicAttachmentConditionEvaluatorTest,DynamicAttachmentConfigIndexTest,DynamicAttachmentRuleResolverTest,DynamicAttachmentApplicationServiceTest,DynamicAttachmentEvaluationSystemTest test
```

Expected: pass.

- [ ] **Step 4: Run full test suite**

```powershell
.\mvnw.cmd test
```

Expected: pass.

- [ ] **Step 5: Inspect dirty state**

```powershell
git status --short --untracked-files=all
```

Expected: only intentional implementation files are modified. Do not revert unrelated pre-existing avatar-flight changes.

- [ ] **Step 6: Commit any verification fixes**

If Step 4 or Step 5 required fixes, stage the exact files shown by `git status --short --untracked-files=all` that belong to this feature:

```powershell
git add src/main/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfig.java src/main/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponent.java src/main/java/com/alechilles/alecstamework/npc/dynamicattachments src/main/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystem.java src/test/java/com/alechilles/alecstamework/config/assets/TwDynamicAttachmentsConfigTest.java src/test/java/com/alechilles/alecstamework/npc/components/TameworkDynamicAttachmentsComponentTest.java src/test/java/com/alechilles/alecstamework/npc/dynamicattachments src/test/java/com/alechilles/alecstamework/npc/systems/DynamicAttachmentEvaluationSystemTest.java
git commit -m "Fix: Stabilize dynamic attachment config"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review

Spec coverage:

- Dedicated `TwDynamicAttachmentsConfig`: Tasks 1, 2, 9.
- Parent fallback and config cache: Tasks 1, 2.
- `Permanent` and `WhileMatching`: Tasks 3, 6, 9.
- Baseline restore, absent-slot removal, reload-safe overlay state, and external-change guard: Tasks 3 and 6.
- Conditions: Task 4.
- Priority by config, then rule, then deterministic tie-break: Tasks 1 and 5.
- Performance: Tasks 5 and 7 use role index, sorted rules, low-frequency sweeps, fingerprints, and no unchanged writes.
- ECS safety: Tasks 7 and 10.
- Attachment validation using existing services: Task 8.
- Documentation/changelog: Task 9.

Placeholder scan:

- No vague implementation markers or unspecified edge handling remain in this plan.

Type consistency:

- Config type: `TwDynamicAttachmentsConfig`.
- Persistent stored attachments: `TameworkAttachmentsComponent`.
- Reversible overlay state: `TameworkDynamicAttachmentsComponent`.
- Runtime package: `com.alechilles.alecstamework.npc.dynamicattachments`.
- Runtime system: `DynamicAttachmentEvaluationSystem`.
