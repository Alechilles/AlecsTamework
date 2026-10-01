package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSnapshotSourceTest {
    /** An unloaded companion (recall from afar, Recover, Revive) restores from its stored snapshot. */
    @Test
    void withoutALoadedBodyUsesTheStoredSnapshot() {
        UUID profileId = UUID.randomUUID();
        SnapshotEnvelope stored = new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT, 3L, new BsonDocument());
        List<SnapshotEnvelope> queued = new ArrayList<>();
        CompanionSnapshotSource source = new CompanionSnapshotSource(
                id -> null, id -> null, queued::add,
                id -> CompletableFuture.completedFuture(id.equals(profileId) ? stored : null),
                new CompanionSnapshots(holder -> new BsonDocument()));

        assertSame(stored, source.read(profileId).join());
        assertTrue(queued.isEmpty());
    }
}
