package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            sink.publish(snapshot(npcUuid, toolId, "Second"), "world");
            assertTrue(await(() -> facades.queries().projectedProfile(
                    new NpcAlias(npcUuid)
            ).map(state -> "Second".equals(state.customName()))
                    .orElse(false)));
            assertEquals(new OwnerId(ownerId), facades.queries().projectedProfile(
                    new NpcAlias(npcUuid)
            ).orElseThrow().ownerId());
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

    private CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot(
            UUID npcUuid,
            UUID toolId,
            String customName
    ) {
        return new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
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
                new Vector3d(4, 5, 6),
                0L,
                0L,
                null,
                null,
                0L,
                null,
                null,
                0L,
                null,
                null,
                null,
                0L,
                null,
                0L,
                0L,
                0L,
                0L,
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                false,
                null,
                null,
                false,
                null,
                1,
                0.0D,
                null,
                0,
                null,
                null,
                null,
                null
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
