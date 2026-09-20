package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

/** Guards against resetting output progress whenever a resident leaves and returns to its coop. */
class DirectLiveCoopProduceServiceTest {
    @Test
    void unresolvedReleaseBlocksProductionAndAnotherRelease() {
        TwCoopConfig config = TwCoopConfig.CODEC.decode(BsonDocument.parse("""
                {"ProduceRules":{"DropsByRole":{"Chicken":"Egg"}}}
                """), new ExtraInfo());
        var container = new SimpleItemContainer((short) 1);
        var coop = new HytaleDirectLiveCoopScanner.LoadedCoop(
                "default", "coop_chicken", new Vector3i(-243, 120, 619), 0,
                config, container);
        var slot = coop.slot(0);
        var profile = new ProfileId(UUID.randomUUID());
        var occupancy = new CoopOccupancy(
                new CoopSlot(slot, 1, OperationId.create(), profile),
                new CoopResidency(slot, profile, null, SnapshotId.create(), 1, 1));

        // A reserved resident must stop before consulting production persistence.
        assertFalse(new DirectLiveCoopProduceService().produceWhileRoaming(
                coop, Map.of(slot, occupancy), Map.of(), null, 1.0));
        assertTrue(container.isEmpty());
    }

    @Test
    void activeAnimalTimeKeepsDailyRoamingIntervalsEligible() {
        long interval = 24L * 60L * 60L * 1_000L;
        long watermark = 10L * interval;

        assertEquals(1, DirectLiveCoopProduceService.cyclesDue(11L * interval, watermark, interval));
    }
}
