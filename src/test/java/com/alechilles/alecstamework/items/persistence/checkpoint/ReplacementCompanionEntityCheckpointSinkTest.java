package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.persistence.ReplacementProfileSnapshotSink;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects checkpoint newest-wins admission during an observation burst. */
class ReplacementCompanionEntityCheckpointSinkTest {
    private static final UUID NPC = UUID.fromString(
            "10000000-0000-0000-0000-000000000101"
    );
    private static final UUID OWNER = UUID.fromString(
            "30000000-0000-0000-0000-000000000101"
    );

    @TempDir
    Path tempDir;

    @Test
    void coalescesBurstToNewestDurableCheckpoint() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            ReplacementProfileSnapshotSink profiles =
                    new ReplacementProfileSnapshotSink(
                            facades.queries(),
                            facades.operations(),
                            clock::get,
                            ignored -> { }
                    );
            profiles.publish(
                    snapshot(NPC, UUID.fromString(
                            "20000000-0000-0000-0000-000000000101"
                    ), "Cow"),
                    "world"
            );
            NpcAlias alias = new NpcAlias(NPC);
            assertTrue(await(() -> facades.queries().projectedProfile(alias)
                    .isPresent()));

            CountDownLatch firstPublished = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger publishedCount = new AtomicInteger();
            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            null,
                            checkpoint -> {
                                if (publishedCount.incrementAndGet() == 1) {
                                    firstPublished.countDown();
                                    awaitLatch(releaseFirst);
                                }
                                published.add(checkpoint);
                            },
                            ignored -> false
                    );

            sink.publish(capture(alias, 0));
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
            for (int position = 1; position < 10; position++) {
                clock.set(-100L + position);
                sink.publish(capture(alias, position));
            }
            releaseFirst.countDown();

            assertTrue(await(() -> published.size() == 2));
            assertEquals(2, published.size());
            CompanionEntityCheckpoint finalCheckpoint = published.get(1);
            assertEquals(9.0D, finalCheckpoint.x());
            assertEquals(-9.0D, finalCheckpoint.z());
            assertEquals(-91L, finalCheckpoint.capturedAtMs());
            assertEquals(
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    finalCheckpoint.boundary()
            );
            assertEquals(
                    BsonDocument.parse("{\"marker\":9}"),
                    finalCheckpoint.holder()
            );
            var read = facades.queries().findExtension(
                    ReplacementCompanionEntityCheckpointSink.key(
                            facades.queries().projectedProfile(alias)
                                    .orElseThrow().profileId(),
                            alias
                    )
            ).toCompletableFuture().join();
            var found = assertInstanceOf(
                    PersistenceReadResult.Found.class, read
            );
            CompanionEntityCheckpoint decoded = new CompanionEntityCheckpointCodec()
                    .decode(((com.alechilles.alecstamework.companion.extension.ProfileExtensionData)
                            found.value()).jsonPayload());
            assertEquals(finalCheckpoint, decoded);
        }
    }

    @Test
    void suppressesUnchangedLoadedObservationAfterUnload() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            seedProfile(facades, clock);
            NpcAlias alias = new NpcAlias(NPC);
            AtomicInteger published = new AtomicInteger();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            null,
                            ignored -> published.incrementAndGet(),
                            ignored -> false
                    );

            sink.publish(capture(
                    alias, 4, CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -100L
            )).toCompletableFuture().join();
            assertEquals(1, published.get());

            sink.publish(capture(
                    alias, 4, CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -50L
            )).toCompletableFuture().join();
            assertEquals(1, published.get());
            sink.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void persistsMeaningfulUnloadBoundaryAfterLoadedCheckpoint() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            seedProfile(facades, clock);
            NpcAlias alias = new NpcAlias(NPC);
            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            null,
                            published::add,
                            ignored -> false
                    );

            sink.publish(capture(
                    alias, 4, CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -100L
            )).toCompletableFuture().join();
            sink.publish(capture(
                    alias, 4, CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -50L
            )).toCompletableFuture().join();

            assertEquals(2, published.size());
            assertEquals(
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    published.get(1).boundary()
            );
            sink.shutdown(Duration.ofSeconds(1));
        }
    }

    private CompanionEntityCheckpointCapture capture(
            NpcAlias alias,
            int position
    ) {
        return capture(
                alias,
                position,
                CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                -100L + position
        );
    }

    private CompanionEntityCheckpointCapture capture(
            NpcAlias alias,
            int position,
            CompanionEntityCheckpoint.CaptureBoundary boundary,
            long capturedAtMs
    ) {
        return new CompanionEntityCheckpointCapture(
                alias,
                new OwnerId(OWNER),
                "world",
                position,
                64.0D,
                -position,
                boundary,
                capturedAtMs,
                BsonDocument.parse("{\"marker\":" + position + "}")
        );
    }

    private void seedProfile(
            com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades facades,
            AtomicLong clock
    ) throws InterruptedException {
        ReplacementProfileSnapshotSink profiles =
                new ReplacementProfileSnapshotSink(
                        facades.queries(),
                        facades.operations(),
                        clock::get,
                        ignored -> { }
                );
        profiles.publish(
                snapshot(NPC, UUID.fromString(
                        "20000000-0000-0000-0000-000000000101"
                ), "Cow"),
                "world"
        );
        assertTrue(await(() -> facades.queries().projectedProfile(
                new NpcAlias(NPC)
        ).isPresent()));
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            AtomicLong clock
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "checkpoint-sink-test",
                clock::get,
                (claim, operation) -> confirmed("refund"),
                event -> { },
                boundaries(),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }

    private CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot(
            UUID npcUuid,
            UUID toolId,
            String customName
    ) {
        return new CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot(
                npcUuid,
                OWNER,
                "Owner",
                new String[]{toolId.toString()},
                "Mob_Test",
                true,
                customName,
                "Companion",
                null,
                null
        );
    }

    private boolean await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for checkpoint test");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
