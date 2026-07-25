package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionCodec;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression matrix for captured inventory artifacts entering the canonical coop operation.
 *
 * <p>These cases protect against resurrecting a second item/coop persistence graph: source
 * authority remains the exact capture snapshot and lifecycle throughout shared-operation
 * prepare, recovery, and commit.</p>
 */
class SqliteCompanionCoopCapturedItemOperationsTest {
    @TempDir
    Path tempDir;

    private SqliteCompanionCoopCapturedItemFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void capturedItemCommitsExactLifecycleSnapshotsResidencyAndEvents()
            throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);

        OperationWorkflowResult result = fixture.capture(
                1,
                (request, operation) -> {
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase()
                    );
                    assertEquals(
                            LifecycleState.CAPTURED,
                            fixture.lifecycle().state()
                    );
                    assertEquals(
                            new LifecycleRevision(3),
                            fixture.lifecycle().revision()
                    );
                    assertTrue(fixture.slot().reserved());
                    assertTrue(fixture.snapshot(
                            SqliteCompanionCoopCapturedItemFixture
                                    .CAPTURE_SNAPSHOT_ID
                    ).current());
                    return LiveOperationResult.confirmed(
                            "captured_item_receipt_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                () -> String.valueOf(result.failure())
        );
        assertEquals(4, result.events().size());
        assertEquals(
                Set.of(
                        SqliteCompanionCoopCaptureOperations.EVENT_TYPE,
                        CoopResidencyProjectionCodec.EVENT_TYPE,
                        CompanionProfileProjectionChangeCodec.EVENT_TYPE,
                        CompanionLifecycleProjectionChangeCodec.EVENT_TYPE
                ),
                result.events().stream()
                        .map(event -> event.eventType())
                        .collect(Collectors.toSet())
        );
        assertEquals(
                Set.of(
                        OperationScope.operation(
                                result.operation().operationId()
                        ),
                        new OperationScope(
                                com.alechilles.alecstamework.persistence
                                        .operation.OperationScopeType.FEATURE,
                                SqliteCompanionCoopCaptureOperations
                                        .FEATURE_SCOPE
                        ),
                        OperationScope.profile(
                                SqliteCompanionCoopCapturedItemFixture.PROFILE
                        ),
                        OperationScope.coop(
                                SqliteCompanionCoopCapturedItemFixture.SLOT
                                        .toString()
                        ),
                        OperationScope.owner(
                                com.alechilles.alecstamework.companion.identity
                                        .OwnerId.parse(
                                        SqliteCompanionCoopCapturedItemFixture
                                                .ACTOR.toString()
                                )
                        )
                ),
                Set.copyOf(result.operation().participants())
        );
        assertEquals(LifecycleState.COOP, fixture.lifecycle().state());
        assertEquals(new LifecycleRevision(4), fixture.lifecycle().revision());
        assertEquals(
                SqliteCompanionCoopCapturedItemFixture.OWNER,
                fixture.lifecycle().ownerId()
        );
        assertEquals(
                LifecycleLocationKind.COOP_SLOT,
                fixture.lifecycle().location().kind()
        );
        assertNull(fixture.lifecycle().activeOperationId());
        assertFalse(fixture.snapshot(
                SqliteCompanionCoopCapturedItemFixture.CAPTURE_SNAPSHOT_ID
        ).current());
        assertTrue(fixture.snapshot(
                SqliteCompanionCoopCapturedItemFixture.COOP_SNAPSHOT_ID
        ).current());
        assertEquals(
                SqliteCompanionCoopCapturedItemFixture.PROFILE,
                fixture.residency().orElseThrow().profileId()
        );
        assertFalse(fixture.slot().reserved());
        assertEquals(
                CompanionAlias.State.CURRENT,
                fixture.alias().state()
        );
        assertFalse(result.events().stream()
                .map(event -> event.eventType())
                .map(ProjectionEventType::toString)
                .anyMatch(type -> type.contains("population")
                        || type.contains("command")
                        || type.contains("timed")));
    }

    @Test
    void publishedReplayDoesNotRepeatCapturedItemMutation()
            throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);
        AtomicInteger liveCalls = new AtomicInteger();
        var boundary =
                (com.alechilles.alecstamework.companion.coop
                        .CompanionCoopCaptureLiveBoundary) (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "captured_item_receipt_confirmed"
                    ).completed();
                };

        OperationWorkflowResult first = fixture.capture(2, boundary);
        OperationWorkflowResult replay = fixture.capture(2, boundary);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                first.status(),
                () -> String.valueOf(first.failure())
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, liveCalls.get());
        assertEquals(1, fixture.slot().residencyRevision());
        assertEquals(new LifecycleRevision(4), fixture.lifecycle().revision());
        assertEquals(4, replay.events().size());
    }

    @Test
    void retryPreservesExactCaptureFenceAndResumesWithoutDuplicateCommit()
            throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);
        AtomicInteger resolutions = new AtomicInteger();
        var boundary =
                (com.alechilles.alecstamework.companion.coop
                        .CompanionCoopCaptureLiveBoundary) (request, operation) -> {
                    if (resolutions.incrementAndGet() == 1) {
                        return LiveOperationResult.retryable(
                                "actor_temporarily_offline", null
                        ).completed();
                    }
                    return LiveOperationResult.confirmed(
                            "captured_item_receipt_confirmed"
                    ).completed();
                };

        OperationWorkflowResult retry = fixture.capture(3, boundary);

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                retry.status()
        );
        assertEquals(LifecycleState.CAPTURED, fixture.lifecycle().state());
        assertEquals(new LifecycleRevision(3), fixture.lifecycle().revision());
        assertTrue(fixture.slot().reserved());
        assertTrue(fixture.residency().isEmpty());
        assertTrue(fixture.snapshot(
                SqliteCompanionCoopCapturedItemFixture.CAPTURE_SNAPSHOT_ID
        ).current());

        OperationWorkflowResult resumed = fixture.capture(3, boundary);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                resumed.status(),
                () -> String.valueOf(resumed.failure())
        );
        assertEquals(2, resolutions.get());
        assertEquals(1, fixture.slot().residencyRevision());
        assertFalse(fixture.snapshot(
                SqliteCompanionCoopCapturedItemFixture.CAPTURE_SNAPSHOT_ID
        ).current());
    }

    @Test
    void staleCaptureSnapshotArtifactNeverCrossesLiveBoundary()
            throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);
        fixture.retireCaptureSnapshotDirectly();
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = fixture.capture(
                4,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
        assertEquals(
                SqliteCompanionCoopCapturedItemFixture.CAPTURED_REVISION,
                fixture.lifecycle().revision()
        );
        assertFalse(fixture.slot().reserved());
        assertTrue(fixture.residency().isEmpty());
    }

    @Test
    void staleLifecycleOrAliasFailsClosedBeforeSlotReservation()
            throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);
        fixture.makeLifecycleStale();
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult staleLifecycle = fixture.capture(
                5,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                staleLifecycle.status()
        );
        assertEquals(0, liveCalls.get());
        assertFalse(fixture.slot().reserved());

        fixture.close();
        fixture = new SqliteCompanionCoopCapturedItemFixture(
                tempDir.resolve("alias")
        );
        fixture.retireAliasDirectly();
        OperationWorkflowResult staleArtifactAlias = fixture.capture(
                6,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                staleArtifactAlias.status()
        );
        assertEquals(0, liveCalls.get());
        assertFalse(fixture.slot().reserved());
    }

    @Test
    void slotConflictRollsBackSourceFenceAtomically() throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);
        fixture.occupySlotDirectly();
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = fixture.capture(
                7,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(0, liveCalls.get());
        assertEquals(
                SqliteCompanionCoopCapturedItemFixture.CAPTURED_REVISION,
                fixture.lifecycle().revision()
        );
        assertTrue(fixture.snapshot(
                SqliteCompanionCoopCapturedItemFixture.CAPTURE_SNAPSHOT_ID
        ).current());
        assertTrue(fixture.residency().isPresent());
    }

    @Test
    void durableSourceConflictRollsBackEveryCommitEffect()
            throws Exception {
        fixture = new SqliteCompanionCoopCapturedItemFixture(tempDir);

        OperationWorkflowResult result = fixture.capture(
                8,
                (request, operation) -> {
                    fixture.retireCaptureSnapshotDirectly();
                    return LiveOperationResult.confirmed(
                            "captured_item_receipt_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                result.status()
        );
        assertEquals(OperationPhase.LIVE_APPLYING, result.operation().phase());
        assertEquals(LifecycleState.CAPTURED, fixture.lifecycle().state());
        assertEquals(new LifecycleRevision(3), fixture.lifecycle().revision());
        assertTrue(fixture.slot().reserved());
        assertTrue(fixture.residency().isEmpty());
        assertTrue(fixture.currentSnapshot(
                CompanionCoopCaptureRequest.SNAPSHOT_KIND
        ).isEmpty());
    }
}
