package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletionException;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementProfileSnapshotSinkTest {
    @TempDir
    Path tempDir;

    @Test
    void adoptsMissingObservedProfileAsExactLiveIdentity()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            ReplacementProfileSnapshotSink sink =
                    new ReplacementProfileSnapshotSink(
                            facades.queries(),
                            facades.operations(),
                            clock::get,
                            warning -> {
                            }
                    );
            UUID npcUuid = UUID.fromString(
                    "10000000-0000-0000-0000-000000000101"
            );
            UUID toolId = UUID.fromString(
                    "20000000-0000-0000-0000-000000000101"
            );
            UUID ownerId = UUID.fromString(
                    "30000000-0000-0000-0000-000000000101"
            );
            sink.publish(snapshot(npcUuid, toolId, "First"), "world");

            assertTrue(await(() -> facades.queries().projectedProfile(
                    new NpcAlias(npcUuid)
            ).map(state -> "First".equals(state.customName()))
                    .orElse(false)));
            var created = facades.queries().projectedProfile(
                    new NpcAlias(npcUuid)
            ).orElseThrow();
            assertEquals(new OwnerId(ownerId), created.ownerId());
            var read = facades.queries().findProfile(
                    new NpcAlias(npcUuid)
            ).toCompletableFuture().join();
            var found = assertInstanceOf(
                    PersistenceReadResult.Found.class, read
            );
            var model = (com.alechilles.alecstamework.companion.profile
                    .CompanionProfileReadModel) found.value();
            assertEquals(
                    LifecycleState.ACTIVE,
                    model.lifecycle().state()
            );
            assertEquals(
                    LifecycleLocation.liveEntity(npcUuid.toString(), "world"),
                    model.lifecycle().location()
            );
            assertTrue(created.tamed());
            assertEquals(java.util.Set.of(toolId), created.toolIds());

            clock.set(-50L);
            sink.publish(
                    snapshot(npcUuid, toolId, "Second"),
                    "temporary-coop-world"
            );
            assertTrue(await(() -> facades.queries().projectedProfile(
                    new NpcAlias(npcUuid)
            ).map(state -> "Second".equals(state.customName()))
                    .orElse(false)));
            assertTrue(await(() -> {
                var moved = facades.queries().findProfile(
                        new NpcAlias(npcUuid)
                ).toCompletableFuture().join();
                if (!(moved instanceof PersistenceReadResult.Found<?> next)) {
                    return false;
                }
                var profile = (com.alechilles.alecstamework.companion.profile
                        .CompanionProfileReadModel) next.value();
                return profile.lifecycle().location().equals(
                        LifecycleLocation.liveEntity(
                                npcUuid.toString(),
                                "temporary-coop-world"
                        )
                );
            }));
            var moved = (com.alechilles.alecstamework.companion.profile
                    .CompanionProfileReadModel) ((PersistenceReadResult.Found<?>)
                    facades.queries().findProfile(new NpcAlias(npcUuid))
                            .toCompletableFuture().join()).value();
            assertEquals(
                    "temporary-coop-world",
                    moved.identity().lastKnownWorldKey()
            );
            assertEquals(new OwnerId(ownerId), facades.queries().projectedProfile(
                    new NpcAlias(npcUuid)
            ).orElseThrow().ownerId());
        }
    }

    @Test
    void exactImportedUnloadedAliasReturnsToActiveWhenObserved()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            ProfileId profileId = ProfileId.parse(
                    "40000000-0000-0000-0000-000000000101"
            );
            UUID npcUuid = UUID.fromString(
                    "10000000-0000-0000-0000-000000000101"
            );
            String metadata = "{}";
            CompanionProfileMutation.Create create =
                    new CompanionProfileMutation.Create(
                            new CompanionIdentity(
                                    profileId, "Imported", "Mob_Test",
                                    metadata, Sha256Hash.ofUtf8(metadata), null,
                                    -200L, -200L, -200L, 0L
                            ),
                            new CompanionLifecycle(
                                    profileId,
                                    OwnerId.parse(
                                            "30000000-0000-0000-0000-000000000101"
                                    ),
                                    LifecycleState.UNLOADED,
                                    LifecycleLocation.none(),
                                    LifecycleRevision.INITIAL,
                                    null,
                                    -200L,
                                    ReconciliationGeneration.INITIAL,
                                    null
                            ),
                            List.of(),
                            -200L
                    );
            facades.operations().mutateProfile(
                    OperationId.parse(
                            "50000000-0000-0000-0000-000000000101"
                    ),
                    new IdempotencyKey("seed-imported-unloaded"),
                    create
            ).completion().toCompletableFuture().join();
            facades.operations().rotateAlias(
                    OperationId.parse(
                            "50000000-0000-0000-0000-000000000102"
                    ),
                    new IdempotencyKey("seed-imported-alias"),
                    new CompanionAliasRotation(
                            profileId, new NpcAlias(npcUuid), -150L
                    )
            ).completion().toCompletableFuture().join();
            ReplacementProfileSnapshotSink sink =
                    new ReplacementProfileSnapshotSink(
                            facades.queries(),
                            facades.operations(),
                            clock::get,
                            warning -> {
                            }
                    );

            sink.publish(
                    snapshot(
                            npcUuid,
                            UUID.fromString(
                                    "20000000-0000-0000-0000-000000000101"
                            ),
                            "Returned"
                    ),
                    "loaded-world"
            );

            assertTrue(await(() -> {
                var read = facades.queries().findProfile(
                        new NpcAlias(npcUuid)
                ).toCompletableFuture().join();
                if (!(read instanceof PersistenceReadResult.Found<?> found)) {
                    return false;
                }
                var profile = (com.alechilles.alecstamework.companion.profile
                        .CompanionProfileReadModel) found.value();
                return profile.lifecycle().state() == LifecycleState.ACTIVE
                        && profile.lifecycle().location().equals(
                        LifecycleLocation.liveEntity(
                                npcUuid.toString(), "loaded-world"
                        )
                );
            }));
        }
    }

    @Test
    void publishesFiveHundredDistinctObservationsWithinTheBound()
            throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        List<String> warnings = new ArrayList<>();
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            ReplacementProfileSnapshotSink sink =
                    new ReplacementProfileSnapshotSink(
                            facades.queries(),
                            facades.operations(),
                            clock::get,
                            warnings::add
                    );
            List<java.util.concurrent.CompletionStage<Void>> publications =
                    new ArrayList<>(500);
            for (int index = 0; index < 500; index++) {
                UUID npcUuid = UUID.fromString(String.format(
                        "10000000-0000-0000-0000-%012d", index + 1
                ));
                publications.add(sink.publish(
                        snapshot(npcUuid, UUID.randomUUID(), "Newest-" + index),
                        "world"
                ));
            }

            java.util.concurrent.CompletableFuture.allOf(
                    publications.stream()
                            .map(stage -> stage.toCompletableFuture())
                            .toArray(java.util.concurrent.CompletableFuture[]::new)
            ).get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(500, facades.queries().projectedProfileSnapshot().size());
            assertTrue(sink.metrics().maximumInFlightWork() <= 16);
            assertFalse(warnings.stream().anyMatch(
                    warning -> warning.contains("read_executor_saturated")
            ));
            for (int index = 0; index < 500; index++) {
                UUID npcUuid = UUID.fromString(String.format(
                        "10000000-0000-0000-0000-%012d", index + 1
                ));
                var projected = facades.queries().projectedProfile(
                        new NpcAlias(npcUuid)
                );
                assertEquals(
                        "Newest-" + index,
                        projected.orElseThrow().customName()
                );
            }
            assertTrue(sink.shutdown(Duration.ofSeconds(5)).drained());
        }
    }

    @Test
    void failedReadWarningIncludesTheTypedStorageCode() throws Exception {
        AtomicLong clock = new AtomicLong(-100L);
        List<String> warnings = new ArrayList<>();
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var facades = persistence.facades();
            persistence.shutdown(Duration.ofSeconds(1));
            ReplacementProfileSnapshotSink sink =
                    new ReplacementProfileSnapshotSink(
                            facades.queries(),
                            facades.operations(),
                            clock::get,
                            warnings::add
                    );

            var publication = sink.publish(
                    snapshot(
                            UUID.fromString(
                                    "10000000-0000-0000-0000-000000000999"
                            ),
                            UUID.fromString(
                                    "20000000-0000-0000-0000-000000000999"
                            ),
                            "Closed"
                    ),
                    "world"
            );

            assertThrows(
                    CompletionException.class,
                    () -> publication.toCompletableFuture().join()
            );
            assertTrue(warnings.stream().anyMatch(
                    warning -> warning.contains("read_executor_closed")
            ));
            assertTrue(sink.shutdown(Duration.ofSeconds(1)).drained());
        }
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            AtomicLong clock
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "profile-snapshot-sink-test",
                clock::get,
                (claim, operation) -> confirmed("refund"),
                event -> {
                },
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
                UUID.fromString(
                        "30000000-0000-0000-0000-000000000101"
                ),
                "Owner",
                new String[]{toolId.toString()},
                "Mob_Test",
                true,
                customName,
                "Companion",
                new Vector3d(1, 2, 3),
                new Vector3d(4, 5, 6)
        );
    }

    private boolean await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
