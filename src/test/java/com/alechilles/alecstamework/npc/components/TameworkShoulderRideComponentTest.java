package com.alechilles.alecstamework.npc.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.ExtraInfo;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Verifies shoulder-state snapshot compatibility across prototype saves. */
class TameworkShoulderRideComponentTest {
    @Test
    void legacyMarkerDoesNotClaimToHaveCapturedPhysicalState() {
        TameworkShoulderRideComponent legacy =
                decode("{}");

        assertFalse(legacy.hasCapturedState());
        assertFalse(legacy.clone().hasCapturedState());
    }

    @Test
    void currentMarkerCapturesPhysicalStateForRestoration() {
        TameworkShoulderRideComponent current =
                new TameworkShoulderRideComponent(UUID.randomUUID(),
                        true, false, true, false);

        assertTrue(current.hasCapturedState());
        TameworkShoulderRideComponent roundTripped =
                TameworkShoulderRideComponent.CODEC.decode(
                        TameworkShoulderRideComponent.CODEC.encode(
                                current, new ExtraInfo()), new ExtraInfo());
        assertTrue(roundTripped.hasCapturedState());
    }

    @Test
    void predecessorMarkerIsRecognizedByItsSerializedSnapshotFields() {
        TameworkShoulderRideComponent predecessor =
                decode("""
                        {
                          "WasInteractable": false,
                          "WasIntangible": false,
                          "WasInvulnerable": false,
                          "WasFrozen": false
                        }
                        """);

        assertTrue(predecessor.hasCapturedState());
        assertTrue(predecessor.clone().hasCapturedState());
    }

    private static TameworkShoulderRideComponent decode(String json) {
        return TameworkShoulderRideComponent.CODEC.decode(
                BsonDocument.parse(json), new ExtraInfo());
    }
}
