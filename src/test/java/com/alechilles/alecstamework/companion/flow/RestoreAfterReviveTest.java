package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreAfterReviveTest {
    private final ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
    private final Store<EntityStore> store = registry.addStore(null, null);
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    /** Stands in for the writer: the latest queued snapshot per profile, read before the file. */
    private final Map<UUID, SnapshotEnvelope> queued = new HashMap<>();

    @AfterEach
    void shutDown() {
        registry.removeStore(store);
        registry.shutdown();
    }

    /**
     * After a free revive the stored snapshot must no longer be the death snapshot, or every later
     * recall and Recover of the companion is refused (and a crash leaves it unrestorable).
     */
    @Test
    void aRevivedCompanionCanBeRecalledAfterwards() {
        CompanionRecord dead = insertDead();
        queued.put(dead.profileId(), deathSnapshot(dead));
        RestoreFlow<String> flow = new RestoreFlow<>(index, new LoadedBodies<>(),
                id -> CompletableFuture.completedFuture(queued.get(id)),
                owner -> CompletableFuture.completedFuture(null),
                this::spawnWithProductionFinish,
                (id, body) -> { },
                System::currentTimeMillis, (b, a) -> null);
        RestoreFlow.Destination here = new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f);

        assertEquals(RestoreFlow.Result.RESTORED,
                flow.restore(dead.profileId(), RestoreRules.Reason.REVIVE, here).join());
        assertEquals(RestoreFlow.Result.RESTORED,
                flow.restore(dead.profileId(), RestoreRules.Reason.RECALL, here).join());
    }

    /** Adds a plain body, as the production spawner does after the revive patch, then runs its post-add step. */
    private CompletableFuture<Boolean> spawnWithProductionFinish(CompanionRecord committed, SnapshotEnvelope snapshot,
                                                                 RestoreFlow.Destination destination,
                                                                 RestoreRules.Reason reason) {
        Ref<EntityStore> ref = store.addEntity(registry.newHolder(), AddReason.LOAD);
        HytaleCompanionSpawner.finishAddedBody(ref, store, committed, destination.world(), 0L, reason,
                new CompanionSnapshots(registry::serialize), envelope -> queued.put(envelope.profileId(), envelope));
        return CompletableFuture.completedFuture(true);
    }

    private CompanionRecord insertDead() {
        CompanionRecord live = CompanionTransitions.newLive(UUID.randomUUID(), 0, new CompanionTransitions.BodyFacts(
                UUID.randomUUID(), UUID.randomUUID(), "Alec", "Tamed_Sheep", null, "default", 0, 0, 0, List.of(),
                CompanionSummary.EMPTY));
        index.insert(live);
        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, CompanionSummary.EMPTY, 5L, 6L, "STARVED", null));
        return index.get(live.profileId());
    }

    private static SnapshotEnvelope deathSnapshot(CompanionRecord record) {
        BsonDocument entity = new BsonDocument(SnapshotPatch.COMPONENTS,
                new BsonDocument(SnapshotPatch.DEATH, new BsonDocument()));
        BsonDocument data = new BsonDocument("Entity", entity)
                .append("World", new BsonString("default")).append("GameTimeMs", new BsonInt64(0));
        return new SnapshotEnvelope(record.profileId(), CompanionSnapshots.FORMAT, record.generation(), data);
    }
}
