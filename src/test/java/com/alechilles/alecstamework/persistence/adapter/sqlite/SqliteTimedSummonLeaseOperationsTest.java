package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationRequest;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared-envelope mutation, projection, and recovery tests for timed leases. */
class SqliteTimedSummonLeaseOperationsTest
        extends CommandRosterTestSupport {
    @Test
    void registrationAndPolicyRefreshUseOneDatabaseProtocol()
            throws Exception {
        createProfile(PROFILE_A, SLOT_A, 70);
        published(addMembership(PROFILE_A, SLOT_A, 0, 71));
        TimedSummonLease initial = dormant(1, policy(10_000), -3_000);

        published(submit(
                new TimedSummonLeaseMutationRequest(
                        null,
                        initial,
                        lifecycleRead(PROFILE_A),
                        -3_000
                ),
                72
        ));
        assertEquals(initial, lease());
        assertEquals(
                initial,
                adapter.timedSummonIndex().readySnapshot()
                        .get(PROFILE_A).lease()
        );

        TimedSummonLease refreshed = dormant(
                2,
                new TimedSummonPolicy(
                        "role:timed",
                        2L,
                        20_000,
                        4_000,
                        false,
                        List.of(10_000L, 2_000L)
                ),
                -2_000
        );
        published(submit(
                new TimedSummonLeaseMutationRequest(
                        initial,
                        refreshed,
                        lifecycleRead(PROFILE_A),
                        -2_000
                ),
                73
        ));
        assertEquals(refreshed, lease());
    }

    @Test
    void preparedRegistrationRecoversThroughTheSameAdapter()
            throws Exception {
        createProfile(PROFILE_A, SLOT_A, 74);
        published(addMembership(PROFILE_A, SLOT_A, 0, 75));
        TimedSummonLease target = dormant(
                1, policy(10_000), -3_000
        );
        TimedSummonLeaseMutationRequest mutation =
                new TimedSummonLeaseMutationRequest(
                        null,
                        target,
                        lifecycleRead(PROFILE_A),
                        -3_000
                );
        var operationId = operationId(76);
        PersistenceTransactionResult<?> prepared =
                adapter.publicOperations().engine().prepare(
                        TimedSummonLeaseMutationDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                new IdempotencyKey("timed:recover"),
                                mutation,
                                SqliteTimedSummonLeaseOperations.FEATURE_SCOPE,
                                mutation.lifecycle().revision(),
                                List.of(OperationScope.profile(PROFILE_A)),
                                mutation.requestedAtMs()
                        )
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                prepared
        );

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "timed-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(target, lease());
    }

    @Test
    void checkpointCannotIncreaseRemainingTimeOrForgetWarnings() {
        TimedSummonLease active = new TimedSummonLease(
                PROFILE_A,
                1,
                new com.alechilles.alecstamework.companion.command.timed
                        .TimedSummonSessionId(
                        new java.util.UUID(0, 70)
                ),
                5_000L,
                null,
                policy(10_000),
                Set.of(5_000L),
                -3_000L,
                -5_000,
                -3_000
        );
        TimedSummonLease invalid = new TimedSummonLease(
                PROFILE_A,
                2,
                active.sessionId(),
                6_000L,
                null,
                active.policy(),
                Set.of(5_000L),
                -2_000L,
                -5_000,
                -2_000
        );
        var lifecycle = lifecycle(
                PROFILE_A,
                com.alechilles.alecstamework.companion.lifecycle
                        .LifecycleState.ACTIVE,
                SLOT_A,
                0
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TimedSummonLeaseMutationRequest(
                        active, invalid, lifecycle, -2_000
                )
        );
    }

    private OperationWorkflowResult submit(
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

    private TimedSummonLease lease() throws Exception {
        PersistenceReadResult.Found<TimedSummonLease> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.timedSummonReader().find(PROFILE_A)
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        return found.value();
    }

    private TimedSummonLease dormant(
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

    private TimedSummonPolicy policy(long duration) {
        return new TimedSummonPolicy(
                "role:timed",
                1L,
                duration,
                2_000,
                true,
                List.of(5_000L, 1_000L)
        );
    }
}
