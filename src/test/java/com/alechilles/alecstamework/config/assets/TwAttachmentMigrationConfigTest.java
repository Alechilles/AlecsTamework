package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.npc.progression.CompanionAttachmentMigrationService;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwAttachmentMigrationConfigTest {
    @Test
    void addsTargetAttachmentFromMappedSourceWhenTargetMissing() throws Exception {
        TwAttachmentMigrationConfig config = configWithRule("Coat", "Eyes", Map.of("Black", "BrightOrange"));

        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config,
                Map.of("Coat", "Black"),
                Map.of("Coat", Set.of("Black"), "Eyes", Set.of("BrightOrange", "Hazel"))
        );

        assertEquals("Black", migrated.get("Coat"));
        assertEquals("BrightOrange", migrated.get("Eyes"));
    }

    @Test
    void doesNotOverwriteExistingTargetAttachment() throws Exception {
        TwAttachmentMigrationConfig config = configWithRule("Coat", "Eyes", Map.of("Black", "BrightOrange"));

        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config,
                Map.of("Coat", "Black", "Eyes", "Hazel"),
                Map.of("Coat", Set.of("Black"), "Eyes", Set.of("BrightOrange", "Hazel"))
        );

        assertEquals("Hazel", migrated.get("Eyes"));
    }

    @Test
    void skipsMappedTargetWhenModelDoesNotSupportIt() throws Exception {
        TwAttachmentMigrationConfig config = configWithRule("Coat", "Eyes", Map.of("Black", "BrightOrange"));

        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config,
                Map.of("Coat", "Black"),
                Map.of("Coat", Set.of("Black"), "Eyes", Set.of("Hazel"))
        );

        assertFalse(migrated.containsKey("Eyes"));
    }

    @Test
    void linkedRuleOverwritesMismatchedTargetFromSource() throws Exception {
        TwAttachmentMigrationConfig config = linkedConfig(
                linkedRule("Fluff", "Wool", Map.of("White", "White", "Brown", "Brown")));

        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config,
                Map.of("Fluff", "Brown", "Wool", "White"),
                Map.of("Fluff", Set.of("White", "Brown"), "Wool", Set.of("White", "Brown"))
        );

        assertEquals(Map.of("Fluff", "Brown", "Wool", "Brown"), migrated);
        assertTrue(config.hasLinkedRules());
    }

    @Test
    void linkedRuleFillsMissingTargetAndKeepsAlreadyMatchedSelections() throws Exception {
        TwAttachmentMigrationConfig config = linkedConfig(linkedRule("Fluff", "Wool", Map.of("Grey", "Grey")));
        Map<String, Set<String>> options = Map.of("Fluff", Set.of("Grey"), "Wool", Set.of("Grey", "White"));
        Map<String, String> matched = Map.of("Fluff", "Grey", "Wool", "Grey");

        assertEquals(matched, CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config, Map.of("Fluff", "Grey"), options));
        assertEquals(matched, CompanionAttachmentMigrationService.applyConfiguredMigrations(config, matched, options));
    }

    @Test
    void linkedRuleLeavesTargetAloneWhenSourceIsUnmappedOrTargetUnsupported() throws Exception {
        TwAttachmentMigrationConfig config = linkedConfig(
                linkedRule("Fluff", "Wool", Map.of("Brown", "Brown", "Black", "Black")));
        Map<String, Set<String>> options = Map.of(
                "Fluff", Set.of("White", "Brown", "Black"),
                "Wool", Set.of("White", "Brown"));

        assertEquals("White", CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config, Map.of("Fluff", "White", "Wool", "White"), options).get("Wool"));
        assertEquals("White", CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config, Map.of("Fluff", "Black", "Wool", "White"), options).get("Wool"));
    }

    @Test
    void linkedRulesChainInDeclarationOrder() throws Exception {
        TwAttachmentMigrationConfig config = linkedConfig(
                linkedRule("Fluff", "Wool", Map.of("Brown", "Brown")),
                linkedRule("Wool", "Tail", Map.of("Brown", "BrownTuft")));

        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                config,
                Map.of("Fluff", "Brown", "Wool", "White", "Tail", "WhiteTuft"),
                Map.of("Fluff", Set.of("Brown"),
                        "Wool", Set.of("White", "Brown"),
                        "Tail", Set.of("WhiteTuft", "BrownTuft"))
        );

        assertEquals(Map.of("Fluff", "Brown", "Wool", "Brown", "Tail", "BrownTuft"), migrated);
    }

    @Test
    void modeDecodesCaseInsensitivelyAndDefaultsToBackfill() {
        TwAttachmentMigrationConfig linked = TwAttachmentMigrationConfig.CODEC.decode(BsonDocument.parse("""
                {"Rules":[{"SourceSlot":"Fluff","TargetSlot":"Wool","Mode":"linked",
                 "SourceToTarget":{"Brown":"Brown"}}]}
                """), new ExtraInfo());
        TwAttachmentMigrationConfig backfill = TwAttachmentMigrationConfig.CODEC.decode(BsonDocument.parse("""
                {"Rules":[{"SourceSlot":"Coat","TargetSlot":"Eyes","SourceToTarget":{"Black":"Orange"}}]}
                """), new ExtraInfo());

        assertTrue(linked.getRules()[0].isLinked());
        assertTrue(linked.hasLinkedRules());
        assertFalse(backfill.getRules()[0].isLinked());
        assertFalse(backfill.hasLinkedRules());
    }

    @Test
    void roleIdsInheritAndExplicitRulesReplaceParentRules() throws Exception {
        TwAttachmentMigrationConfig parent = configWithRule("Coat", "Eyes", Map.of("Black", "BrightOrange"));
        TwAttachmentMigrationConfig child = configWithRule("Coat", "Eyes", Map.of("White", "IceBlue"));
        TwAttachmentMigrationConfig.Rule[] childRules = child.getRules();
        setField(parent, "roleIds", new String[] { "Cat_Pet" });

        child.inheritMissingTopLevelFrom(parent, Set.of("Rules"));

        assertArrayEquals(new String[] { "Cat_Pet" }, child.getRoleIds());
        assertSame(childRules, child.getRules());
    }

    private static TwAttachmentMigrationConfig configWithRule(String sourceSlot,
                                                              String targetSlot,
                                                              Map<String, String> sourceToTarget) throws Exception {
        TwAttachmentMigrationConfig config = new TwAttachmentMigrationConfig();
        TwAttachmentMigrationConfig.Rule rule = new TwAttachmentMigrationConfig.Rule();
        setField(rule, "sourceSlot", sourceSlot);
        setField(rule, "targetSlot", targetSlot);
        setField(rule, "sourceToTarget", sourceToTarget);
        setField(config, "rules", new TwAttachmentMigrationConfig.Rule[] { rule });
        return config;
    }

    private static TwAttachmentMigrationConfig linkedConfig(TwAttachmentMigrationConfig.Rule... rules)
            throws Exception {
        TwAttachmentMigrationConfig config = new TwAttachmentMigrationConfig();
        setField(config, "rules", rules);
        return config;
    }

    private static TwAttachmentMigrationConfig.Rule linkedRule(String sourceSlot,
                                                               String targetSlot,
                                                               Map<String, String> sourceToTarget) throws Exception {
        TwAttachmentMigrationConfig.Rule rule = new TwAttachmentMigrationConfig.Rule();
        setField(rule, "sourceSlot", sourceSlot);
        setField(rule, "targetSlot", targetSlot);
        setField(rule, "sourceToTarget", sourceToTarget);
        setField(rule, "mode", "Linked");
        return rule;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
