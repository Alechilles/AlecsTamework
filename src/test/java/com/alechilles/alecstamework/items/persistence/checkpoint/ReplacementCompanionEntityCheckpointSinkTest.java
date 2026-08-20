package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.persistence.ReplacementProfileSnapshotSink;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceDrainResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertTrue(sink.shutdown(Duration.ofSeconds(1)).drained());
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

    @Test
    void blockedUnloadThenLoadedKeepsCriticalCheckpoint() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            seedProfile(facades, clock, NPC);
            NpcAlias alias = new NpcAlias(NPC);
            CountDownLatch firstPublished = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            null,
                            checkpoint -> {
                                published.add(checkpoint);
                                firstPublished.countDown();
                                awaitLatch(releaseFirst);
                            },
                            ignored -> false
                    );

            var prior = sink.publish(capture(
                    alias, 6, CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -150L
            ));
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
            var unload = sink.publish(capture(
                    alias, 7, CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -100L
            ));
            var loaded = sink.publish(capture(
                    alias, 7, CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -50L
            ));
            releaseFirst.countDown();

            prior.toCompletableFuture().join();
            unload.toCompletableFuture().join();
            loaded.toCompletableFuture().join();
            assertEquals(2, published.size());
            assertEquals(
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    published.get(1).boundary()
            );
            assertEquals(
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    readCheckpoint(facades, alias).boundary()
            );
            sink.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void blockedDestructiveRemoveRunsBeforeTrailingLoadedCheckpoint()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            seedProfile(facades, clock, NPC);
            NpcAlias alias = new NpcAlias(NPC);
            CountDownLatch firstPublished = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            null,
                            checkpoint -> {
                                published.add(checkpoint);
                                firstPublished.countDown();
                                awaitLatch(releaseFirst);
                            },
                            ignored -> false
                    );

            var prior = sink.publish(capture(
                    alias, 6, CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -150L
            ));
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS));
            var destructive = sink.publish(capture(
                    alias, 8,
                    CompanionEntityCheckpoint.CaptureBoundary.DESTRUCTIVE_REMOVE,
                    -100L
            ));
            var loaded = sink.publish(capture(
                    alias, 8, CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -50L
            ));
            releaseFirst.countDown();

            prior.toCompletableFuture().join();
            destructive.toCompletableFuture().join();
            loaded.toCompletableFuture().join();
            assertEquals(3, published.size());
            assertEquals(
                    CompanionEntityCheckpoint.CaptureBoundary.DESTRUCTIVE_REMOVE,
                    published.get(1).boundary()
            );
            assertEquals(
                    CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    readCheckpoint(facades, alias).boundary()
            );
            sink.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void fourUnknownReturnedAliasesReleasePermitsForNormalCheckpoint()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
            List<NpcAlias> returned = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                UUID source = uuid(0x210 + index);
                UUID target = uuid(0x310 + index);
                seedProfile(facades, clock, source);
                rotateAlias(facades, clock, source, target);
                returned.add(new NpcAlias(source));
            }
            UUID normalUuid = uuid(0x410);
            seedProfile(facades, clock, normalUuid);

            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            identities,
                            published::add,
                            ignored -> false
                    );
            List<java.util.concurrent.CompletionStage<Void>> deferred =
                    new java.util.ArrayList<>();
            for (NpcAlias alias : returned) {
                deferred.add(sink.publish(capture(
                        alias, 1,
                        CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                        -100L
                )));
            }
            assertTrue(await(() -> sink.metrics().pendingKeys() == 4));

            var normal = sink.publish(capture(
                    new NpcAlias(normalUuid), 99,
                    CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -50L
            ));
            normal.toCompletableFuture().join();
            assertTrue(published.stream().anyMatch(checkpoint ->
                    checkpoint.alias().equals(new NpcAlias(normalUuid))
            ));
            assertFalse(deferred.get(0).toCompletableFuture().isDone());

            MaintenanceDrainResult drain = sink.shutdown(Duration.ofSeconds(3));
            assertTrue(drain.drained());
            assertEquals(0, drain.pendingWork());
            assertEquals(0, drain.inFlightWork());
            for (var stage : deferred) {
                assertThrows(CompletionException.class,
                        () -> stage.toCompletableFuture().join());
            }
        }
    }

    @Test
    void returnedOriginalEvidenceBecomingKnownCompletesDurableWork()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            UUID source = uuid(0x510);
            UUID target = uuid(0x610);
            seedProfile(facades, clock, source);
            rotateAlias(facades, clock, source, target);
            LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            identities,
                            published::add,
                            ignored -> false
                    );

            var stage = sink.publish(capture(
                    new NpcAlias(source), 2,
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -100L
            ));
            assertTrue(await(() -> sink.metrics().pendingKeys() == 1));
            identities.recordAdded(
                    new NpcAlias(target).value(),
                    new LoadedNpcIdentityIndex.Location("world", "store")
            );

            assertTrue(await(() -> stage.toCompletableFuture().isDone()));
            stage.toCompletableFuture().join();
            assertEquals(1, published.size());
            assertEquals(new NpcAlias(target), published.get(0).alias());
            assertTrue(sink.shutdown(Duration.ofSeconds(1)).drained());
        }
    }

    @Test
    void newerDeferredCaptureSupersedesStaleCapture() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            UUID source = uuid(0x710);
            UUID target = uuid(0x810);
            seedProfile(facades, clock, source);
            rotateAlias(facades, clock, source, target);
            LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
            List<CompanionEntityCheckpoint> published =
                    new CopyOnWriteArrayList<>();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            identities,
                            published::add,
                            ignored -> false
                    );
            NpcAlias sourceAlias = new NpcAlias(source);
            var stale = sink.publish(capture(
                    sourceAlias, 3,
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -100L
            ));
            assertTrue(await(() -> sink.metrics().pendingKeys() == 1));

            var newest = sink.publish(capture(
                    sourceAlias, 4,
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -50L
            ));
            assertTrue(await(() -> sink.metrics().pendingKeys() == 1));
            identities.recordAdded(
                    new NpcAlias(target).value(),
                    new LoadedNpcIdentityIndex.Location("world", "store")
            );

            newest.toCompletableFuture().join();
            stale.toCompletableFuture().join();
            assertEquals(1, published.size());
            assertEquals(
                    BsonDocument.parse("{\"marker\":4}"),
                    published.get(0).holder()
            );
            assertTrue(sink.shutdown(Duration.ofSeconds(1)).drained());
        }
    }

    @Test
    void shutdownCancelsDeferredTimersAndFailsFinalUnknownProbe()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            UUID source = uuid(0x910);
            UUID target = uuid(0xa10);
            seedProfile(facades, clock, source);
            rotateAlias(facades, clock, source, target);
            LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
            AtomicInteger published = new AtomicInteger();
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            ignored -> { },
                            identities,
                            ignored -> published.incrementAndGet(),
                            ignored -> false
                    );
            var stage = sink.publish(capture(
                    new NpcAlias(source), 5,
                    CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                    -100L
            ));
            assertTrue(await(() -> sink.metrics().pendingKeys() == 1));

            var beforeShutdown = published.get();
            var drain = sink.shutdown(Duration.ofSeconds(3));
            assertTrue(drain.drained());
            assertEquals(0, drain.pendingWork());
            assertEquals(0, drain.inFlightWork());
            assertThrows(CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            Thread.sleep(700L);
            assertEquals(beforeShutdown, published.get());
        }
    }

    @Test
    void warningSurfacesDeepestStorageFailureCode() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            persistence.shutdown(Duration.ofSeconds(1));
            ReplacementCompanionEntityCheckpointSink sink =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades,
                            warnings::add,
                            null,
                            ignored -> { },
                            ignored -> false
                    );

            var stage = sink.publish(capture(
                    new NpcAlias(NPC), 6,
                    CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                    -100L
            ));
            assertThrows(CompletionException.class,
                    () -> stage.toCompletableFuture().join());
            assertTrue(await(() -> !warnings.isEmpty()));
            assertTrue(warnings.get(0).contains("read_executor_closed"));
            assertTrue(sink.shutdown(Duration.ofSeconds(1)).drained());
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
        seedProfile(facades, clock, NPC);
    }

    private void seedProfile(
            com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades facades,
            AtomicLong clock,
            UUID npcUuid
    ) throws InterruptedException {
        ReplacementProfileSnapshotSink profiles =
                new ReplacementProfileSnapshotSink(
                        facades.queries(),
                        facades.operations(),
                        clock::get,
                        ignored -> { }
                );
        profiles.publish(
                snapshot(npcUuid, UUID.fromString(
                        "20000000-0000-0000-0000-000000000101"
                ), "Cow"),
                "world"
        );
        assertTrue(await(() -> facades.queries().projectedProfile(
                new NpcAlias(npcUuid)
        ).isPresent()));
    }

    private void rotateAlias(
            com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades facades,
            AtomicLong clock,
            UUID source,
            UUID target
    ) throws InterruptedException {
        var submission = facades.operations().rotateAlias(
                new OperationId(uuid(0xb00 + (source.hashCode() & 0xfff))),
                new IdempotencyKey("test-alias-rotation-" + source),
                new CompanionAliasRotation(
                        new ProfileId(source),
                        new NpcAlias(target),
                        clock.get()
                )
        );
        assertTrue(submission.accepted());
        assertEquals(
                com.alechilles.alecstamework.persistence.operation
                        .OperationWorkflowResult.Status.PUBLISHED,
                submission.completion().toCompletableFuture().join().status()
        );
        assertTrue(await(() -> facades.queries().projectedProfile(
                new NpcAlias(target)
        ).isPresent()));
    }

    private CompanionEntityCheckpoint readCheckpoint(
            com.alechilles.alecstamework.persistence.runtime
                    .PersistenceDomainFacades facades,
            NpcAlias alias
    ) {
        var profile = facades.queries().projectedProfile(alias).orElseThrow();
        var read = facades.queries().findExtension(
                ReplacementCompanionEntityCheckpointSink.key(
                        profile.profileId(), alias
                )
        ).toCompletableFuture().join();
        var found = assertInstanceOf(PersistenceReadResult.Found.class, read);
        var extension = (com.alechilles.alecstamework.companion.extension
                .ProfileExtensionData) found.value();
        return new CompanionEntityCheckpointCodec().decode(
                extension.jsonPayload()
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format(
                "00000000-0000-0000-0000-%012x", suffix
        ));
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
