package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwDynamicAttachmentsConfigTest {
    @Test
    void ruleDefaultsToPermanentPersistence() {
        TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();

        assertEquals(TwDynamicAttachmentsConfig.Persistence.PERMANENT, rule.getPersistence());
    }

    @Test
    void parsesWhileMatchingPersistence() {
        assertEquals(
                TwDynamicAttachmentsConfig.Persistence.WHILE_MATCHING,
                TwDynamicAttachmentsConfig.Persistence.fromConfigValue("WhileMatching")
        );
    }

    @Test
    void conditionDefaultsToIgnoreCaseTrue() {
        TwDynamicAttachmentsConfig.Condition condition = new TwDynamicAttachmentsConfig.Condition();

        assertTrue(condition.isIgnoreCase());
        assertTrue(condition.expectedOrTrue());
    }

    @Test
    void conditionExpectedFalseIsStoredAsBooleanFalse() throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = new TwDynamicAttachmentsConfig.Condition();

        setField(condition, "expected", Boolean.FALSE);

        assertEquals(Boolean.FALSE, condition.getExpected());
        assertFalse(condition.expectedOrTrue());
    }

    @Test
    void conditionDefaultsToEmptyValuesArrayAndNoPercent() {
        TwDynamicAttachmentsConfig.Condition condition = new TwDynamicAttachmentsConfig.Condition();

        assertArrayEquals(new String[0], condition.getValues());
        assertEquals(null, condition.getPercent());
    }

    @Test
    void decodesValuesArrayAndPercentThresholdFromJson() throws Exception {
        String json = """
                {
                  "Enabled": true,
                  "RoleIds": ["Tamed_Moose_Bull"],
                  "Rules": [
                    {
                      "Id": "owner_or_hungry",
                      "Conditions": [
                        {
                          "Type": "OwnerEquals",
                          "Values": ["Alec", "00000000-0000-0000-0000-000000000000"]
                        },
                        {
                          "Type": "NeedBelow",
                          "Need": "Hunger",
                          "Percent": 25
                        }
                      ],
                      "Attachments": {
                        "SaddleBlanket": "Canada"
                      }
                    }
                  ]
                }
                """;

        TwDynamicAttachmentsConfig config = TwDynamicAttachmentsConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );

        TwDynamicAttachmentsConfig.Condition ownerCondition = config.getRules()[0].getConditions()[0];
        TwDynamicAttachmentsConfig.Condition needCondition = config.getRules()[0].getConditions()[1];

        assertArrayEquals(
                new String[] { "Alec", "00000000-0000-0000-0000-000000000000" },
                ownerCondition.getValues()
        );
        assertEquals(25.0, needCondition.getPercent());
    }

    @Test
    void childInheritsOmittedRoleIdsAndRulesWhileKeepingExplicitPriority() throws Exception {
        TwDynamicAttachmentsConfig.Rule parentRule = rule("parent-rule", 0);
        TwDynamicAttachmentsConfig parent = config("Parent", true, 5, new String[] { "Cat_Pet" }, parentRule);
        TwDynamicAttachmentsConfig child = config("Child", true, 12, new String[0]);

        child.inheritMissingTopLevelFrom(parent, Set.of("Priority"));

        assertEquals(12, child.getPriority());
        assertArrayEquals(new String[] { "Cat_Pet" }, child.getRoleIds());
        assertSame(parent.getRules(), child.getRules());
    }

    @Test
    void explicitRulesArrayReplacesParentRules() throws Exception {
        TwDynamicAttachmentsConfig.Rule parentRule = rule("parent-rule", 0);
        TwDynamicAttachmentsConfig.Rule childRule = rule("child-rule", 0);
        TwDynamicAttachmentsConfig parent = config("Parent", true, 0, new String[] { "Cat_Pet" }, parentRule);
        TwDynamicAttachmentsConfig child = config("Child", true, 0, new String[0], childRule);
        TwDynamicAttachmentsConfig.Rule[] childRules = child.getRules();

        child.inheritMissingTopLevelFrom(parent, Set.of("Rules"));

        assertArrayEquals(new String[] { "Cat_Pet" }, child.getRoleIds());
        assertSame(childRules, child.getRules());
        assertSame(childRule, child.getRules()[0]);
    }

    @Test
    void roleIndexSortsConfigPriorityBeforeRulePriority() throws Exception {
        TwDynamicAttachmentsConfig.Rule lowConfigHighRule = rule("low-config-high-rule", 99);
        TwDynamicAttachmentsConfig.Rule highConfigLowRule = rule("high-config-low-rule", 1);
        TwDynamicAttachmentsConfig lowConfig = config(
                "Mod:LowConfig",
                true,
                1,
                new String[] { "Cat_Pet" },
                lowConfigHighRule
        );
        TwDynamicAttachmentsConfig highConfig = config(
                "Mod:HighConfig",
                true,
                10,
                new String[] { "Cat_Pet" },
                highConfigLowRule
        );

        Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> index =
                TwDynamicAttachmentsConfig.buildRoleRuleIndexForTest(List.of(lowConfig, highConfig));

        List<TwDynamicAttachmentsConfig.RoleRuleEntry> entries = index.get("cat_pet");
        assertEquals(2, entries.size());
        assertSame(highConfig, entries.get(0).getConfig());
        assertSame(highConfigLowRule, entries.get(0).getRule());
        assertSame(lowConfig, entries.get(1).getConfig());
        assertSame(lowConfigHighRule, entries.get(1).getRule());
    }

    @Test
    void roleIndexSortsSamePrioritiesByNormalizedAssetIdAscending() throws Exception {
        TwDynamicAttachmentsConfig.Rule zRule = rule("z-rule", 5);
        TwDynamicAttachmentsConfig.Rule aRule = rule("a-rule", 5);
        TwDynamicAttachmentsConfig zConfig = config(
                "Mod:Zeta",
                true,
                10,
                new String[] { "Cat_Pet" },
                zRule
        );
        TwDynamicAttachmentsConfig aConfig = config(
                "mod:alpha",
                true,
                10,
                new String[] { "Cat_Pet" },
                aRule
        );

        Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> index =
                TwDynamicAttachmentsConfig.buildRoleRuleIndexForTest(List.of(zConfig, aConfig));

        List<TwDynamicAttachmentsConfig.RoleRuleEntry> entries = index.get("cat_pet");
        assertEquals(2, entries.size());
        assertSame(aConfig, entries.get(0).getConfig());
        assertSame(zConfig, entries.get(1).getConfig());
    }

    @Test
    void roleIndexPreservesDeclarationOrderForSameAssetAndRulePriority() throws Exception {
        TwDynamicAttachmentsConfig.Rule firstRule = rule("first-rule", 5);
        TwDynamicAttachmentsConfig.Rule secondRule = rule("second-rule", 5);
        TwDynamicAttachmentsConfig config = config(
                "Mod:SameConfig",
                true,
                10,
                new String[] { "Cat_Pet" },
                firstRule,
                secondRule
        );

        Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> index =
                TwDynamicAttachmentsConfig.buildRoleRuleIndexForTest(List.of(config));

        List<TwDynamicAttachmentsConfig.RoleRuleEntry> entries = index.get("cat_pet");
        assertEquals(2, entries.size());
        assertSame(firstRule, entries.get(0).getRule());
        assertSame(secondRule, entries.get(1).getRule());
    }

    @Test
    void roleIndexExcludesDisabledConfigs() throws Exception {
        TwDynamicAttachmentsConfig enabled = config(
                "Enabled",
                true,
                0,
                new String[] { "Cat_Pet" },
                rule("enabled-rule", 0)
        );
        TwDynamicAttachmentsConfig disabled = config(
                "Disabled",
                false,
                100,
                new String[] { "Cat_Pet" },
                rule("disabled-rule", 100)
        );

        Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> index =
                TwDynamicAttachmentsConfig.buildRoleRuleIndexForTest(List.of(disabled, enabled));

        List<TwDynamicAttachmentsConfig.RoleRuleEntry> entries = index.get("cat_pet");
        assertEquals(1, entries.size());
        assertSame(enabled, entries.get(0).getConfig());
        assertTrue(index.values().stream()
                .flatMap(List::stream)
                .noneMatch(entry -> entry.getConfig() == disabled));
    }

    private static TwDynamicAttachmentsConfig config(String id,
                                                     boolean enabled,
                                                     int priority,
                                                     String[] roleIds,
                                                     TwDynamicAttachmentsConfig.Rule... rules) throws Exception {
        TwDynamicAttachmentsConfig config = new TwDynamicAttachmentsConfig();
        setField(config, "id", id);
        setField(config, "enabled", enabled);
        setField(config, "priority", priority);
        setField(config, "roleIds", roleIds);
        setField(config, "rules", rules);
        return config;
    }

    private static TwDynamicAttachmentsConfig.Rule rule(String id, int priority) throws Exception {
        TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();
        setField(rule, "id", id);
        setField(rule, "priority", priority);
        return rule;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
