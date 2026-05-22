package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.npc.progression.CompanionAttachmentMigrationService;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

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

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
