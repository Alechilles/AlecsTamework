package com.alechilles.alecstamework.items.locate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.CaptureKey;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Holder;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapturedItemLocationIndexTest {
    /** A remapped linked alias finds the same capture, but never an earlier/later recapture. */
    @Test void resolvesCaptureAcrossAliasChangesWithoutConfusingRecaptures() {
        var index = new CapturedItemLocationIndex();
        index.observe(CHEST, List.of(CAPTURE), 100L);
        assertEquals(CHEST, index.find(new CaptureKey(CAPTURE.profileId(), CAPTURE.snapshotId(),
                UUID.randomUUID())).orElseThrow().holder());
        assertTrue(index.find(new CaptureKey(CAPTURE.profileId(), "new-capture", CAPTURE.npcUuid())).isEmpty());
    }
    private static final CaptureKey CAPTURE = new CaptureKey(
            "profile-a", "snapshot-a", UUID.fromString(
                    "00000000-0000-0000-0000-000000000001")
    );
    private static final Holder ALICE = new Holder(
            Kind.PLAYER, "world", "alice", "Alice", 1, 2, 3
    );
    private static final Holder CHEST = new Holder(
            Kind.CONTAINER, "world", "10,64,20", "Chest", 10, 64, 20
    );

    @Test
    void newerTransferSurvivesAStaleObservationFromItsOldSource() {
        CapturedItemLocationIndex index = new CapturedItemLocationIndex();

        index.observe(ALICE, List.of(CAPTURE), 100L);
        index.observe(CHEST, List.of(CAPTURE), 200L);
        index.observe(ALICE, List.of(), 300L);

        var sighting = index.find(CAPTURE).orElseThrow();
        assertEquals(CHEST, sighting.holder());
        assertEquals(200L, sighting.observedAtMs());
        assertTrue(sighting.loaded());
    }

    @Test
    void absentObservationRemovesKnownMembershipButUnloadKeepsLastKnownLocation() {
        CapturedItemLocationIndex index = new CapturedItemLocationIndex();

        index.observe(ALICE, List.of(CAPTURE), 100L);
        index.unload(ALICE);

        var stale = index.find(CAPTURE).orElseThrow();
        assertEquals(ALICE, stale.holder());
        assertFalse(stale.loaded());

        index.observe(ALICE, List.of(), 200L);

        assertTrue(index.find(CAPTURE).isEmpty());
    }

    @Test
    void retainsOnlyTheMostRecentBoundedSightings() {
        CapturedItemLocationIndex index = new CapturedItemLocationIndex();
        Holder holder = new Holder(
                Kind.CONTAINER, "world", "0,64,0", "Chest", 0, 64, 0
        );
        List<CaptureKey> captures = new ArrayList<>();
        for (int value = 0; value <= 8192; value++) {
            captures.add(new CaptureKey(
                    "profile-" + value,
                    "snapshot-" + value,
                    UUID.nameUUIDFromBytes(("npc-" + value).getBytes(
                            StandardCharsets.UTF_8
                    ))
            ));
        }

        index.observe(holder, captures, 100L);

        assertEquals(8192, index.snapshot().size());
        assertTrue(index.find(captures.getFirst()).isEmpty());
        assertTrue(index.find(captures.getLast()).isPresent());
    }
}
