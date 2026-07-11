package com.alechilles.alecstamework.npc.components;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.validation.ValidationResults;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests breeding component enable-toggle defaults and cloning behavior. */
class TameworkBreedingComponentTest {

    @Test
    void legacyConstructorDefaultsBreedingDisabled() {
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                1234L,
                true,
                5678L,
                UUID.randomUUID()
        );

        assertFalse(component.isEnabled());
    }

    @Test
    void clonePreservesBreedingEnabledState() {
        long now = System.currentTimeMillis();
        long durationMs = 123456L;
        UUID manualPlayerUuid = UUID.randomUUID();
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                1234L,
                false,
                true,
                now + durationMs,
                UUID.randomUUID(),
                now,
                durationMs,
                manualPlayerUuid,
                now + 5000L
        );

        TameworkBreedingComponent cloned = component.clone();

        assertTrue(cloned.isEnabled());
        assertEquals(now, cloned.getCooldownStartedAtMs());
        assertEquals(durationMs, cloned.getCooldownDurationMs());
        assertEquals(manualPlayerUuid, cloned.getManualBreedingPlayerUuid());
        assertEquals(now + 5000L, cloned.getManualBreedingUntilMs());
    }

    @Test
    void manualBreedingReadinessRequiresSamePlayerAndUnexpiredSelection() {
        TameworkBreedingComponent component = new TameworkBreedingComponent();
        UUID playerUuid = UUID.randomUUID();

        component.markManualBreedingReady(playerUuid, 2000L);

        assertTrue(component.isManualBreedingReadyFor(playerUuid, 1999L));
        assertFalse(component.isManualBreedingReadyFor(UUID.randomUUID(), 1999L));
        assertFalse(component.isManualBreedingReadyFor(playerUuid, 2000L));
    }

    @Test
    void clearManualBreedingReadinessRemovesSelection() {
        TameworkBreedingComponent component = new TameworkBreedingComponent();
        UUID playerUuid = UUID.randomUUID();
        component.markManualBreedingReady(playerUuid, 2000L);

        component.clearManualBreedingReady();

        assertFalse(component.isManualBreedingReadyFor(playerUuid, 1000L));
        assertEquals(0L, component.getManualBreedingUntilMs());
    }

    @Test
    void constructorSetterAndClonePreserveNegativeCooldownStart() {
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                -5_000L,
                false,
                true,
                -1_000L,
                UUID.randomUUID(),
                -3_000L,
                2_000L
        );

        assertEquals(-3_000L, component.getCooldownStartedAtMs());
        component.setCooldownStartedAtMs(-4_000L);
        TameworkBreedingComponent cloned = component.clone();

        assertEquals(-1_000L, cloned.getCooldownUntilMs());
        assertEquals(-4_000L, cloned.getCooldownStartedAtMs());
        assertEquals(2_000L, cloned.getCooldownDurationMs());
    }

    @Test
    void codecPreservesNegativeCooldownTimestamps() {
        String json = """
                {
                  "CooldownUntilMs": -1000,
                  "CooldownStartedAtMs": -3000,
                  "CooldownDurationMs": 2000
                }
                """;
        ExtraInfo extraInfo = new ExtraInfo(ExtraInfo.UNSET_VERSION, ValidationResults::new);

        TameworkBreedingComponent decoded = TameworkBreedingComponent.CODEC.decode(
                BsonDocument.parse(json),
                extraInfo
        );

        assertEquals(-1_000L, decoded.getCooldownUntilMs());
        assertEquals(-3_000L, decoded.getCooldownStartedAtMs());
        assertEquals(2_000L, decoded.getCooldownDurationMs());
    }
}
