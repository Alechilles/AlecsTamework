package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Contract tests for immutable public profile projection payloads. */
class CompanionProfileProjectionChangeCodecTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    private static final UUID TOOL =
            UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Test
    void composesReleasedFieldsAndRoundTripsSelfContainedChange() {
        String metadata = """
                {"owner_name":"Owner","custom_name":"Buddy","tamed":true,
                 "coop_id":"coop-a","coop_slot":3}
                """.replaceAll("\\s+", "");
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Display",
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -100,
                -80,
                -80,
                2
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS,
                PROFILE,
                1,
                CompanionAlias.State.CURRENT,
                null,
                -70,
                null
        );
        CoopSlot coopSlot = CoopSlot.unoccupied(
                new CoopSlotKey("world", "coop-a", 1, 2, 3, 3)
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.COOP,
                LifecycleLocation.keyed(
                        com.alechilles.alecstamework.companion.lifecycle
                                .LifecycleLocationKind.COOP_SLOT,
                        coopSlot.key().toString()
                ),
                new LifecycleRevision(4),
                null,
                -60,
                ReconciliationGeneration.INITIAL,
                null
        );
        CompanionToolLink link = new CompanionToolLink(
                PROFILE,
                TOOL,
                "command",
                -90,
                -50
        );
        String payload = "{\"value\":1}";
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                PROFILE,
                new SnapshotKind("capture"),
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                new LifecycleRevision(4),
                true,
                -40
        );
        CompanionProfileProjectionState state =
                CompanionProfileProjectionState.compose(
                        identity,
                        alias,
                        lifecycle,
                        List.of(link),
                        List.of(snapshot),
                        coopSlot
                );
        CompanionProfileProjectionChange expected =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.ALIAS,
                        PROFILE,
                        1,
                        null,
                        state,
                        -30
                );

        CompanionProfileProjectionChange actual =
                CompanionProfileProjectionChangeCodec.decode(
                        CompanionProfileProjectionChangeCodec.VERSION,
                        CompanionProfileProjectionChangeCodec.encode(expected)
                );

        assertEquals(expected, actual);
        assertEquals(
                "profile-observer-alias:" + PROFILE,
                CompanionProfileProjectionChangeCodec.aggregateId(actual)
        );
        assertEquals("Owner", actual.after().ownerName());
        assertEquals("Buddy", actual.after().customName());
        assertEquals(LifecycleState.COOP, actual.after().lifecycleState());
        assertEquals("coop-a", actual.after().coopId());
        assertEquals(3, actual.after().coopSlot());
        assertEquals(-40, actual.after().lastUpdatedAtMs());
    }

    @Test
    void malformedOptionalMetadataDegradesToReleasedDefaults() {
        String metadata = """
                {"owner_name":{},"custom_name":[],"tamed":{},"coop_slot":"nope"}
                """.replaceAll("\\s+", "");
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                null,
                null,
                metadata,
                Sha256Hash.ofUtf8(metadata),
                null,
                -100,
                -90,
                -90,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                null,
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -90,
                ReconciliationGeneration.INITIAL,
                null
        );

        CompanionProfileProjectionState state =
                CompanionProfileProjectionState.compose(
                        identity,
                        null,
                        lifecycle,
                        List.of(),
                        List.of(),
                        null
                );

        assertNull(state.ownerName());
        assertNull(state.customName());
        assertFalse(state.tamed());
        assertNull(state.coopSlot());
    }

    @Test
    void deathDeadlineSurvivesCompositionAndCodecRoundTrip() {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Display",
                "role",
                "{}",
                Sha256Hash.ofUtf8("{}"),
                "world",
                -100,
                -90,
                -90,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.DEAD_REVIVABLE,
                LifecycleLocation.none(),
                new LifecycleRevision(2),
                null,
                -80,
                ReconciliationGeneration.INITIAL,
                null
        );
        String payload = """
                {"diedAtMs":-260,"respawnAvailableAtMs":-1000}
                """.trim();
        CompanionSnapshot death = new CompanionSnapshot(
                SnapshotId.parse(
                        "50000000-0000-0000-0000-000000000002"
                ),
                PROFILE,
                new SnapshotKind("death"),
                2,
                payload,
                Sha256Hash.ofUtf8(payload),
                new LifecycleRevision(2),
                true,
                -70
        );
        CompanionProfileProjectionState state =
                CompanionProfileProjectionState.compose(
                        identity,
                        null,
                        lifecycle,
                        List.of(),
                        List.of(death),
                        null
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.SNAPSHOT,
                        PROFILE,
                        2,
                        null,
                        state,
                        -60
                );

        CompanionProfileProjectionChange decoded =
                CompanionProfileProjectionChangeCodec.decode(
                        CompanionProfileProjectionChangeCodec.VERSION,
                        CompanionProfileProjectionChangeCodec.encode(change)
                );

        assertEquals(-1000L, state.restorationAvailableAtMs());
        assertEquals(
                -1000L,
                decoded.after().restorationAvailableAtMs()
        );
    }

    @Test
    void existingVersionOnePayloadWithoutDeadlineDecodesAsUnset() {
        CompanionProfileProjectionState state =
                new CompanionProfileProjectionState(
                        PROFILE,
                        null,
                        LifecycleState.DEAD_REVIVABLE,
                        OWNER,
                        null,
                        "role",
                        "Display",
                        null,
                        true,
                        null,
                        null,
                        java.util.Set.of(),
                        java.util.Set.of(new SnapshotKind("death")),
                        -70
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.SNAPSHOT,
                        PROFILE,
                        2,
                        null,
                        state,
                        -60
                );
        JsonObject payload = JsonParser.parseString(
                CompanionProfileProjectionChangeCodec.encode(change)
        ).getAsJsonObject();
        payload.getAsJsonObject("after").remove(
                "restorationAvailableAtMs"
        );

        CompanionProfileProjectionChange decoded =
                CompanionProfileProjectionChangeCodec.decode(
                        CompanionProfileProjectionChangeCodec.VERSION,
                        payload.toString()
                );

        assertEquals(0L, decoded.after().restorationAvailableAtMs());
    }
}
