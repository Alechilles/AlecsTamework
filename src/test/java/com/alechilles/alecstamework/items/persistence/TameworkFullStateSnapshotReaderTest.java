package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full-state authoring reads have one persistence-free success/failure contract. */
class TameworkFullStateSnapshotReaderTest {
    private static final NpcAlias SOURCE = NpcAlias.parse(
            "10000000-0000-0000-0000-000000000001"
    );

    @Test
    void sourceNeutralReadReturnsTheExactCopiedSnapshot() {
        CoopResidentStateSnapshot source = snapshot(
                SOURCE.value(), null, -1, "pet"
        );
        AtomicReference<TameworkFullStateSnapshotReader.SnapshotContext> seen =
                new AtomicReference<>();
        TameworkFullStateSnapshotReader reader =
                new TameworkFullStateSnapshotReader(
                        (reference, store, npcUuid, context) -> {
                            assertEquals(SOURCE.value(), npcUuid);
                            seen.set(context);
                            return source;
                        }
                );

        TameworkFullStateSnapshotReader.ReadResult result =
                reader.readSourceNeutral(null, null, SOURCE, "pet");

        assertTrue(result.successful());
        assertEquals(
                "owner-before",
                result.snapshot().owner().getOwnerName()
        );
        source.owner().setOwnerName("owner-after");
        assertEquals(
                "owner-before",
                result.snapshot().owner().getOwnerName()
        );
        assertFalse(source == result.snapshot());
        assertFalse(source.owner() == result.snapshot().owner());
        assertNull(result.failure());
        assertFalse(seen.get().coop());
        assertEquals("pet", seen.get().roleId());
    }

    @Test
    void coopReadCarriesOnlyExactSlotContext() {
        CoopResidentStateSnapshot expected = snapshot(
                SOURCE.value(), "farm:coop", 3, "chicken"
        );
        AtomicReference<TameworkFullStateSnapshotReader.SnapshotContext> seen =
                new AtomicReference<>();
        TameworkFullStateSnapshotReader reader =
                new TameworkFullStateSnapshotReader(
                        (reference, store, npcUuid, context) -> {
                            seen.set(context);
                            return expected;
                        }
                );

        TameworkFullStateSnapshotReader.ReadResult result =
                reader.readCoop(
                        null,
                        null,
                        SOURCE,
                        " farm:coop ",
                        3,
                        "chicken"
                );

        assertTrue(result.successful());
        assertTrue(seen.get().coop());
        assertEquals("farm:coop", seen.get().coopId());
        assertEquals(3, seen.get().residentSlot());
        assertEquals("chicken", seen.get().roleId());
    }

    @Test
    void unavailableAndFailedCaptureHaveStableFailures() {
        TameworkFullStateSnapshotReader unavailable =
                new TameworkFullStateSnapshotReader(
                        (reference, store, npcUuid, context) -> null
                );
        TameworkFullStateSnapshotReader failed =
                new TameworkFullStateSnapshotReader(
                        (reference, store, npcUuid, context) -> {
                            throw new IllegalStateException("component read");
                        }
                );

        assertEquals(
                TameworkFullStateSnapshotReader.Failure.SOURCE_UNAVAILABLE,
                unavailable.read(null, null, SOURCE, null).failure()
        );
        assertEquals(
                TameworkFullStateSnapshotReader.Failure.CAPTURE_FAILED,
                failed.read(null, null, SOURCE, null).failure()
        );
    }

    @Test
    void invalidIdentityOrCoopSlotNeverConsultsLiveSource() {
        AtomicInteger calls = new AtomicInteger();
        TameworkFullStateSnapshotReader reader =
                new TameworkFullStateSnapshotReader(
                        (reference, store, npcUuid, context) -> {
                            calls.incrementAndGet();
                            return snapshot(npcUuid, null, -1, null);
                        }
                );

        assertEquals(
                TameworkFullStateSnapshotReader.Failure.INVALID_REQUEST,
                reader.read(null, null, null, null).failure()
        );
        assertEquals(
                TameworkFullStateSnapshotReader.Failure.INVALID_REQUEST,
                reader.readCoop(
                        null, null, SOURCE, " ", 0, null
                ).failure()
        );
        assertEquals(
                TameworkFullStateSnapshotReader.Failure.INVALID_REQUEST,
                reader.readCoop(
                        null, null, SOURCE, "coop", -1, null
                ).failure()
        );
        assertEquals(0, calls.get());
    }

    @Test
    void resultCannotBeBothSuccessAndFailureOrNeither() {
        CoopResidentStateSnapshot snapshot = snapshot(
                SOURCE.value(), null, -1, null
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TameworkFullStateSnapshotReader.ReadResult(
                        snapshot,
                        TameworkFullStateSnapshotReader.Failure.CAPTURE_FAILED
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TameworkFullStateSnapshotReader.ReadResult(
                        null, null
                )
        );
    }

    private CoopResidentStateSnapshot snapshot(
            UUID npcUuid,
            String coopId,
            int residentSlot,
            String roleId
    ) {
        return new CoopResidentStateSnapshot(
                npcUuid,
                coopId,
                residentSlot,
                roleId,
                null,
                new TameworkOwnerComponent(
                        UUID.fromString(
                                "20000000-0000-0000-0000-000000000001"
                        ),
                        "owner-before"
                ),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0.75D,
                -200L
        );
    }
}
