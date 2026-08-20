package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.persistence.ReplacementProfileSnapshotSink;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpoint;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCapture;
import com.alechilles.alecstamework.items.persistence.checkpoint.CompanionEntityCheckpointCodec;
import com.alechilles.alecstamework.items.persistence.checkpoint.ReplacementCompanionEntityCheckpointSink;
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
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies profile-first publication for the first observed live companion. */
class CommandLinkedNpcStateSnapshotPersistenceTest {
    private static final UUID NPC = UUID.fromString(
            "10000000-0000-0000-0000-000000000901"
    );
    private static final UUID OWNER = UUID.fromString(
            "30000000-0000-0000-0000-000000000901"
    );

    @TempDir
    Path tempDir;

    @Test
    void firstObservationPublishesProfileBeforeExactCheckpoint() {
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
            ReplacementCompanionEntityCheckpointSink checkpoints =
                    new ReplacementCompanionEntityCheckpointSink(
                            facades, ignored -> { }
                    );
            NpcAlias alias = new NpcAlias(NPC);
            CompanionEntityCheckpointCapture capture =
                    new CompanionEntityCheckpointCapture(
                            alias,
                            new OwnerId(OWNER),
                            "world",
                            9.0D,
                            64.0D,
                            -9.0D,
                            CompanionEntityCheckpoint.CaptureBoundary.LOADED,
                            clock.get(),
                            BsonDocument.parse("{\"marker\":9}")
                    );

            var profile = profiles.publish(snapshot(), "world");
            CommandLinkedNpcStateSnapshotService.publishCheckpointAfterProfile(
                    profile,
                    capture,
                    checkpoints::publish,
                    () -> true
            ).join();

            var profileRead = facades.queries().findProfile(alias)
                    .toCompletableFuture().join();
            CompanionProfileReadModel model = (CompanionProfileReadModel)
                    assertInstanceOf(
                            PersistenceReadResult.Found.class, profileRead
                    ).value();
            var extensionRead = facades.queries().findExtension(
                    ReplacementCompanionEntityCheckpointSink.key(
                            model.identity().profileId(), alias
                    )
            ).toCompletableFuture().join();
            ProfileExtensionData extension = (ProfileExtensionData)
                    assertInstanceOf(
                            PersistenceReadResult.Found.class, extensionRead
                    ).value();
            CompanionEntityCheckpoint decoded =
                    new CompanionEntityCheckpointCodec().decode(
                            extension.jsonPayload()
                    );
            assertEquals(9.0D, decoded.x());
            assertEquals(-9.0D, decoded.z());
            assertEquals(BsonDocument.parse("{\"marker\":9}"), decoded.holder());
            assertTrue(profiles.shutdown(Duration.ofSeconds(1)).drained());
            assertTrue(checkpoints.shutdown(Duration.ofSeconds(1)).drained());
        }
    }

    private CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot() {
        return new CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot(
                NPC,
                OWNER,
                "Owner",
                new String[]{
                        "20000000-0000-0000-0000-000000000901"
                },
                "Mob_Test",
                true,
                "Cow",
                "Companion",
                null,
                null
        );
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            AtomicLong clock
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "snapshot-profile-order-test",
                clock::get,
                (claim, operation) -> confirmed("refund"),
                event -> { },
                new PublicPersistenceLiveBoundaries(
                        (request, operation) -> confirmed("capture"),
                        (request, operation) -> confirmed("capture_release"),
                        (request, operation) -> confirmed("restoration"),
                        (request, operation) -> confirmed("coop_capture"),
                        (request, operation) -> confirmed("coop_release")
                ),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }
}
