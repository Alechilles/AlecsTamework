package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationRequest;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused canonical fixture builders shared by timed operation tests. */
abstract class TimedSummonTestSupport
        extends CommandRosterTestSupport {
    protected OperationWorkflowResult submit(
            TimedSummonLeaseMutationRequest mutation,
            int operation
    ) throws Exception {
        return adapter.timedSummonOperations().submit(
                operationId(operation),
                new IdempotencyKey("timed:" + operation),
                mutation
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    protected TimedSummonLease lease() throws Exception {
        PersistenceReadResult.Found<TimedSummonLease> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.timedSummonReader().find(PROFILE_A)
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        return found.value();
    }

    protected TimedSummonLease dormant(
            long revision,
            TimedSummonPolicy policy,
            long changedAtMs
    ) {
        return new TimedSummonLease(
                PROFILE_A,
                revision,
                null,
                null,
                -1_000L,
                policy,
                Set.of(),
                null,
                -5_000,
                changedAtMs
        );
    }

    protected TimedSummonPolicy policy(long duration) {
        return new TimedSummonPolicy(
                "role:timed",
                1L,
                duration,
                2_000,
                true,
                List.of(5_000L, 1_000L)
        );
    }

    protected PreparedTimed prepareStoredTimedProfile(int operation)
            throws Exception {
        createProfile(PROFILE_A, SLOT_A, operation);
        classify(PROFILE_A, 2, operation + 1);
        published(addMembership(PROFILE_A, SLOT_A, 0, operation + 2));
        CompanionSnapshot source = snapshot(
                operation,
                LifecycleRevision.INITIAL,
                "{\"state\":\"stored\"}",
                -5_000
        );
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            assertTrue(new SqliteCompanionSnapshotStore(connection)
                    .replaceCurrent(source).applied());
            connection.commit();
        }
        TimedSummonPolicy policy = new TimedSummonPolicy(
                "role:timed",
                1L,
                10_000,
                1_500,
                true,
                List.of(5_000L, 1_000L)
        );
        TimedSummonLease lease = new TimedSummonLease(
                PROFILE_A,
                1,
                null,
                null,
                -3_500L,
                policy,
                Set.of(),
                null,
                -5_000,
                -4_000
        );
        published(submit(
                new TimedSummonLeaseMutationRequest(
                        null,
                        lease,
                        lifecycleRead(PROFILE_A),
                        -4_000
                ),
                operation + 3
        ));
        return new PreparedTimed(
                lease,
                source,
                NpcAlias.parse(String.format(
                        "60000000-0000-0000-0000-%012d", operation
                ))
        );
    }

    protected TimedSummonTransitionRequest startRequest(
            PreparedTimed prepared,
            CompanionLifecycle before,
            long requestedAtMs
    ) {
        CompanionLifecycle logicalAfter = lifecycle(
                LifecycleState.ACTIVE,
                before.revision().next(),
                prepared.alias(),
                requestedAtMs
        );
        TimedSummonLease afterLease = new TimedSummonLease(
                PROFILE_A,
                prepared.lease().leaseRevision() + 1,
                new TimedSummonSessionId(new UUID(0, 800)),
                prepared.lease().policy().activeDurationMs(),
                null,
                prepared.lease().policy(),
                Set.of(),
                requestedAtMs,
                prepared.lease().createdAtMs(),
                requestedAtMs
        );
        return transition(
                TimedSummonTransitionRequest.Action.START,
                prepared.lease(),
                afterLease,
                before,
                logicalAfter,
                prepared.alias(),
                prepared.source(),
                requestedAtMs
        );
    }

    protected TimedSummonTransitionRequest storeRequest(
            PreparedTimed prepared,
            CompanionLifecycle before,
            TimedSummonLease beforeLease,
            long requestedAtMs
    ) {
        CompanionLifecycle logicalAfter = lifecycle(
                LifecycleState.ROSTER_STORED,
                before.revision().next(),
                prepared.alias(),
                requestedAtMs
        );
        TimedSummonLease afterLease = new TimedSummonLease(
                PROFILE_A,
                beforeLease.leaseRevision() + 1,
                null,
                null,
                -500L,
                beforeLease.policy(),
                Set.of(),
                null,
                beforeLease.createdAtMs(),
                requestedAtMs
        );
        CompanionSnapshot target = snapshot(
                999,
                before.revision().next(),
                "{\"state\":\"returned\"}",
                requestedAtMs
        );
        return transition(
                TimedSummonTransitionRequest.Action.STORE,
                beforeLease,
                afterLease,
                before,
                logicalAfter,
                prepared.alias(),
                target,
                requestedAtMs
        );
    }

    private TimedSummonTransitionRequest transition(
            TimedSummonTransitionRequest.Action action,
            TimedSummonLease beforeLease,
            TimedSummonLease afterLease,
            CompanionLifecycle before,
            CompanionLifecycle after,
            NpcAlias alias,
            CompanionSnapshot snapshot,
            long requestedAtMs
    ) {
        return new TimedSummonTransitionRequest(
                action,
                FAMILY,
                SLOT_A,
                1,
                beforeLease,
                afterLease,
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        1,
                        List.of(super.policy(2, 1)),
                        requestedAtMs
                ),
                alias,
                "world-a",
                action == TimedSummonTransitionRequest.Action.START
                        ? new CompanionSpawnPlacement(
                        "world-a",
                        -12.5,
                        -63.05,
                        -4.5,
                        -0.25f,
                        -1.5f,
                        -0.5f
                )
                        : null,
                snapshot,
                "receipt:" + action + ":" + requestedAtMs,
                requestedAtMs
        );
    }

    private CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleRevision revision,
            NpcAlias alias,
            long requestedAtMs
    ) {
        LifecycleLocation location = state == LifecycleState.ACTIVE
                ? LifecycleLocation.liveEntity(
                        alias.toString(), "world-a"
                )
                : LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        SLOT_A.toString()
                );
        return new CompanionLifecycle(
                PROFILE_A,
                OWNER,
                state,
                location,
                revision,
                null,
                requestedAtMs,
                com.alechilles.alecstamework.companion.lifecycle
                        .ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private CompanionSnapshot snapshot(
            int value,
            LifecycleRevision sourceRevision,
            String payload,
            long createdAtMs
    ) {
        return new CompanionSnapshot(
                new SnapshotId(new UUID(0, value)),
                PROFILE_A,
                TimedSummonTransitionRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                sourceRevision,
                true,
                createdAtMs
        );
    }

    protected record PreparedTimed(
            TimedSummonLease lease,
            CompanionSnapshot source,
            NpcAlias alias
    ) {
    }
}

