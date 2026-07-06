package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAttachmentRuleResolverTest {
    @Test
    void testHigherPriorityEarlierRuleWinsPerSlot() throws Exception {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
                snapshot(),
                List.of(
                        entry(config("mod:high", 10), rule("hat-rule", 0, persistence("PERMANENT"),
                                Map.of("head", "top_hat")), 0),
                        entry(config("mod:low", 0), rule("fallback-hat", 0, persistence("PERMANENT"),
                                Map.of("head", "cap")), 1)
                )
        );

        assertEquals(Map.of("head", "top_hat"), resolution.permanentAttachments());
        assertTrue(resolution.temporaryAttachments().isEmpty());
    }

    @Test
    void testPermanentAndWhileMatchingOutputAreSeparated() throws Exception {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
                snapshot(),
                List.of(
                        entry(config("mod:kit", 0), rule("collar", 0, persistence("PERMANENT"),
                                Map.of("neck", "red_collar")), 0),
                        entry(config("mod:kit", 0), rule("sparkle", 0, persistence("WHILE_MATCHING"),
                                Map.of("aura", "happy_sparkle")), 1)
                )
        );

        assertEquals(Map.of("neck", "red_collar"), resolution.permanentAttachments());
        assertEquals("happy_sparkle", resolution.temporaryAttachments().get("aura").value());
        assertEquals("mod:kit/sparkle", resolution.temporaryAttachments().get("aura").ruleKey());
    }

    @Test
    void testLowerRuleCanFillDifferentSlot() throws Exception {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
                snapshot(),
                List.of(
                        entry(config("mod:first", 0), rule("head", 0, persistence("PERMANENT"),
                                Map.of("head", "top_hat")), 0),
                        entry(config("mod:second", 0), rule("tail", 0, persistence("PERMANENT"),
                                Map.of("tail", "bow")), 1)
                )
        );

        assertEquals(Map.of("head", "top_hat", "tail", "bow"), resolution.permanentAttachments());
    }

    @Test
    void testConditionsFilterUnmatchedRules() throws Exception {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
                DynamicAttachmentNpcSnapshot.builder()
                        .displayName("Mittens")
                        .build(),
                List.of(
                        entry(config("mod:rules", 0), rule("wrong-name", 0, persistence("PERMANENT"),
                                Map.of("head", "cap"), condition("DisplayNameEquals", "Shadow")), 0),
                        entry(config("mod:rules", 0), rule("right-name", 0, persistence("PERMANENT"),
                                Map.of("head", "crown"), condition("DisplayNameEquals", "mittens")), 1)
                )
        );

        assertEquals(Map.of("head", "crown"), resolution.permanentAttachments());
    }

    @Test
    void testBlankSlotAndValueAttachmentsAreIgnored() throws Exception {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
                snapshot(),
                List.of(entry(config("mod:rules", 0), rule("messy", 0, persistence("PERMANENT"),
                        Map.of(" ", "hat", "head", " ", "tail", "bow")), 0))
        );

        assertEquals(Map.of("tail", "bow"), resolution.permanentAttachments());
    }

    @Test
    void testStableRuleKeyUsesConfigAndRuleIdsWhenAvailable() throws Exception {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(
                snapshot(),
                List.of(
                        entry(config("mod:decor", 0), rule("sparkle", 0, persistence("WHILE_MATCHING"),
                                Map.of("aura", "glow")), 7),
                        entry(config("mod:fallback", 0), rule(" ", 0, persistence("WHILE_MATCHING"),
                                Map.of("tail", "ribbon")), 42)
                )
        );

        assertEquals("mod:decor/sparkle", resolution.temporaryAttachments().get("aura").ruleKey());
        assertEquals("mod:fallback#42", resolution.temporaryAttachments().get("tail").ruleKey());
    }

    @Test
    void testEmptyResolutionReportsEmptyAndExposesImmutableMaps() {
        DynamicAttachmentResolution resolution = DynamicAttachmentRuleResolver.resolve(snapshot(), List.of());

        assertTrue(resolution.isEmpty());
        assertTrue(resolution.permanentAttachments().isEmpty());
        assertTrue(resolution.temporaryAttachments().isEmpty());
        assertFalse(resolution.permanentAttachments().containsKey("head"));
    }

    private static DynamicAttachmentNpcSnapshot snapshot() {
        return DynamicAttachmentNpcSnapshot.builder().build();
    }

    private static TwDynamicAttachmentsConfig.Persistence persistence(String name) {
        return TwDynamicAttachmentsConfig.Persistence.valueOf(name);
    }

    private static TwDynamicAttachmentsConfig config(String id, int priority) throws Exception {
        Constructor<TwDynamicAttachmentsConfig> constructor = TwDynamicAttachmentsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwDynamicAttachmentsConfig config = constructor.newInstance();
        setField(config, "id", id);
        setField(config, "priority", priority);
        return config;
    }

    private static TwDynamicAttachmentsConfig.Rule rule(String id,
                                                        int priority,
                                                        TwDynamicAttachmentsConfig.Persistence persistence,
                                                        Map<String, String> attachments,
                                                        TwDynamicAttachmentsConfig.Condition... conditions)
            throws Exception {
        TwDynamicAttachmentsConfig.Rule rule = new TwDynamicAttachmentsConfig.Rule();
        setField(rule, "id", id);
        setField(rule, "priority", priority);
        setField(rule, "persistence", persistence);
        setField(rule, "attachments", attachments);
        setField(rule, "conditions", conditions);
        return rule;
    }

    private static TwDynamicAttachmentsConfig.Condition condition(String type, String value) throws Exception {
        TwDynamicAttachmentsConfig.Condition condition = new TwDynamicAttachmentsConfig.Condition();
        setField(condition, "type", type);
        setField(condition, "value", value);
        return condition;
    }

    private static TwDynamicAttachmentsConfig.RoleRuleEntry entry(TwDynamicAttachmentsConfig config,
                                                                  TwDynamicAttachmentsConfig.Rule rule,
                                                                  int declarationOrder) throws Exception {
        Constructor<TwDynamicAttachmentsConfig.RoleRuleEntry> constructor =
                TwDynamicAttachmentsConfig.RoleRuleEntry.class.getDeclaredConstructor(
                        TwDynamicAttachmentsConfig.class,
                        TwDynamicAttachmentsConfig.Rule.class,
                        int.class
                );
        constructor.setAccessible(true);
        return constructor.newInstance(config, rule, declarationOrder);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
