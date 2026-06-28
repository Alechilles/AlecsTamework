package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandRespawnServiceTest {
    @Test
    void createRespawnNeedsComponentResetsTransientNeedsState() throws Exception {
        Constructor<TwNeedsConfig> constructor = TwNeedsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwNeedsConfig config = constructor.newInstance();

        TameworkNeedsComponent component = CommandRespawnService.createRespawnNeedsComponent(config, 1234L);

        assertEquals(config.getId(), component.getConfigId());
        assertEquals(config.getValues().getHungerDefault(), component.getHunger(), 0.000001);
        assertEquals(config.getValues().getThirstDefault(), component.getThirst(), 0.000001);
        assertEquals(0.0, component.getAppliedHappinessPenalty(), 0.000001);
        assertEquals(0.0, component.getPendingNeedsDamage(), 0.000001);
        assertEquals(1234L, component.getLastUpdateMs());
        assertEquals(1234L, component.getLastPassiveSweepMs());
        assertEquals(-1.0, component.getRegenSuppressionBaselineHealth(), 0.000001);
        assertEquals(0.0, component.getRegenSuppressionAllowedHeal(), 0.000001);
        assertEquals(-1.0, component.getLastManagedHealth(), 0.000001);
    }

    @Test
    void respawnAttachmentsApplyConfiguredMigrationBeforePersistingSnapshotState() throws Exception {
        TwAttachmentMigrationConfig config = attachmentMigrationConfig("Coat", "Eyes", Map.of("Black", "BrightOrange"));

        Map<String, String> resolved = CommandRespawnService.resolveRespawnAttachmentSelections(
                config,
                Map.of("Coat", "Black"),
                Map.of("Coat", Set.of("Black"), "Eyes", Set.of("BrightOrange", "Hazel"))
        );

        assertEquals("Black", resolved.get("Coat"));
        assertEquals("BrightOrange", resolved.get("Eyes"));
    }

    @Test
    void respawnRunsProgressionBootstrapAfterRestoringSnapshotState() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", "CommandRespawnService.java"
        ), StandardCharsets.UTF_8);

        int restoreIndex = source.indexOf("applyRespawnRecoveryState(spawnedRef, store, deadSnapshot);");
        int bootstrapIndex = source.indexOf(
                "CompanionProgressionBootstrapService.ensureProgressionComponents(spawnedRef, store, roleId);"
        );
        int logIndex = source.indexOf("post_restore recoveryStateApplied=true");

        assertTrue(restoreIndex > 0, "Respawn should restore explicit snapshot and recovery state first.");
        assertTrue(
                bootstrapIndex > restoreIndex,
                "Respawn should re-run progression bootstrap after restore so missing happiness, needs, levels, traits, and talents are reinitialized."
        );
        assertTrue(
                logIndex < 0 || bootstrapIndex < logIndex,
                "Respawn diagnostics should describe state after bootstrap has repaired missing progression components."
        );
    }

    @Test
    void progressionBootstrapIncludesTalentStorageRepair() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "npc", "progression",
                "CompanionProgressionBootstrapService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(
                source.contains("CompanionTalentService.ensureTalentsComponent(npcRef, store, roleId);"),
                "Shared progression bootstrap should repair missing talent storage during revive, lost recovery, and load repair."
        );
    }

    private static TwAttachmentMigrationConfig attachmentMigrationConfig(String sourceSlot,
                                                                         String targetSlot,
                                                                         Map<String, String> sourceToTarget) throws Exception {
        Constructor<TwAttachmentMigrationConfig> constructor = TwAttachmentMigrationConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwAttachmentMigrationConfig config = constructor.newInstance();
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
