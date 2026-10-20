package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionRestoreRecallSinkTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final LoadedBodies<Ref<EntityStore>> loaded = new LoadedBodies<>();
    private final CompanionRestoreRecallSink sink = new CompanionRestoreRecallSink(new RestoreFlow<>(index, loaded,
            id -> CompletableFuture.completedFuture(snapshot(index.get(id))),
            owner -> CompletableFuture.completedFuture(null),
            (committed, snap, dest, reason) -> CompletableFuture.completedFuture(true),
            (id, body) -> { },
            System::currentTimeMillis, (b, a) -> null), new CompanionQueries(index, loaded));

    private static SnapshotEnvelope snapshot(CompanionRecord r) {
        BsonDocument data = new BsonDocument("Entity", new BsonDocument("Components", new BsonDocument()))
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(r.profileId(), CompanionSnapshots.FORMAT, r.generation(), data);
    }

    /** A recall whose body never appeared restores the owner's companion at the frozen spot, and nobody else's. */
    @Test
    void aTimedOutRecallRestoresOnlyTheRecallersCompanionAtTheFrozenDestination() {
        UUID owner = UUID.randomUUID();
        CompanionRecord live = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                UUID.randomUUID(), owner, "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY));
        index.insert(live);
        ImportedRecallRecoverySink.RecallDestination frozen =
                new ImportedRecallRecoverySink.RecallDestination("default", 5, 6, 7);

        assertEquals(ImportedRecallRecoverySink.RecoveryOutcome.NONE, sink.recover(failure(live, UUID.randomUUID(), frozen))
                .toCompletableFuture().join(), "a stranger's recall restores nothing");
        assertEquals(0, index.get(live.profileId()).generation());

        assertEquals(ImportedRecallRecoverySink.RecoveryOutcome.RECOVERED, sink.recover(failure(live, owner, frozen))
                .toCompletableFuture().join());
        CompanionRecord after = index.get(live.profileId());
        assertEquals(1, after.generation());
        assertEquals(5, after.location().x());
        assertEquals(7, after.location().z());
    }

    private static ImportedRecallRecoverySink.RecallFailure failure(CompanionRecord live, UUID recaller,
                                                                   ImportedRecallRecoverySink.RecallDestination at) {
        return new ImportedRecallRecoverySink.RecallFailure(live.currentNpcUuid(), recaller, 0L, 1L, "default", at,
                java.util.Set.of());
    }
}
