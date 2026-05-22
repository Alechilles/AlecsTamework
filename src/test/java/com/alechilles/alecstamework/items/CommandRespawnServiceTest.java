package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
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
