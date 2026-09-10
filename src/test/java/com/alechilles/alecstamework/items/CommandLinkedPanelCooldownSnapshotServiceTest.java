package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedPanelCooldownSnapshotServiceTest {

    @Test
    void harvestCooldownRatioUsesNegativeWorldTimeWindow() {
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot snapshot =
                CommandLinkedPanelCooldownSnapshotService.fromAlarmWindow(
                        true,
                        true,
                        -5_000L,
                        10_000L,
                        -10_000L,
                        20_000L,
                        null
                );

        assertTrue(snapshot.known);
        assertTrue(snapshot.active);
        assertEquals(0.25, snapshot.ratio, 0.0001);
    }

    @Test
    void inactiveHarvestCooldownSnapshotsAreKnownAndReady() {
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot snapshot =
                CommandLinkedPanelCooldownSnapshotService.fromAlarmWindow(
                        true,
                        false,
                        10_000L,
                        10_000L,
                        -10_000L,
                        20_000L,
                        null
                );

        assertTrue(snapshot.known);
        assertFalse(snapshot.active);
        assertEquals(0L, snapshot.remainingMs);
        assertEquals(1.0, snapshot.ratio, 0.0001);
    }

    @Test
    void activeSignedWindowSaturatesRemainingDurationWithoutWrapping() {
        CommandLinkedPanelCooldownSnapshotService.CooldownSnapshot snapshot =
                CommandLinkedPanelCooldownSnapshotService.fromAlarmWindow(
                        true,
                        true,
                        Long.MIN_VALUE,
                        Long.MAX_VALUE,
                        Long.MIN_VALUE + 1L,
                        Long.MAX_VALUE,
                        null
                );

        assertTrue(snapshot.active);
        assertEquals(
                BreedingTimeService.toEstimatedRealDurationMs(Long.MAX_VALUE, null),
                snapshot.remainingMs
        );
        assertEquals(0.0, snapshot.ratio, 0.0001);
    }

    @Test
    void harvestReadinessRequiresAnEnabledSupportedHarvestInteraction() {
        TwInteractionConfig config = configWith("{\"Type\":\"Harvest\"}");
        assertFalse(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(config, false));
        assertTrue(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(config, true));
        assertFalse(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(
                configWith("{\"Type\":\"Harvest\",\"Enabled\":false}"), true));
    }

    @Test
    void harvestInteractionCanExplicitlyOptOutOfTheHarvestabilityRequirement() {
        assertTrue(CommandLinkedPanelCooldownSnapshotService.hasEnabledHarvestInteraction(
                configWith("{\"Type\":\"Harvest\",\"RequireHarvestable\":false}"), false));
    }

    private static TwInteractionConfig configWith(String entry) {
        return TwInteractionConfig.CODEC.decode(org.bson.BsonDocument.parse(
                "{\"Enabled\":true,\"Interactions\":[" + entry + "]}"), new com.hypixel.hytale.codec.ExtraInfo());
    }
}
