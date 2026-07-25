package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared membership, transition, admission, projection, and recovery tests. */
class SqliteCommandRosterOperationsTest
        extends CommandRosterTestSupport {
    @Test
    void membershipAddUpdateAndRemoveUseOneSharedProtocol()
            throws Exception {
        createProfile(PROFILE_A, null, 1);

        published(addMembership(PROFILE_A, SLOT_A, 0, 10));
        published(updateMembership(PROFILE_A, SLOT_A, 1, 1, 11));
        CommandRosterMembership updated = membership(PROFILE_A);
        assertEquals(2, updated.membershipRevision());
        assertEquals("favorites", updated.groupId());
        assertTrue(updated.activeForBulkCommands());
        assertTrue(adapter.commandRosterIndex().actionSnapshot()
                .get(PROFILE_A).membership().activeForBulkCommands());

        published(removeMembership(PROFILE_A, SLOT_A, 2, 2, 12));
        assertInstanceOf(
                PersistenceReadResult.Absent.class,
                adapter.commandRosterReader().findByProfile(PROFILE_A)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS)
        );
        assertTrue(adapter.commandRosterIndex().actionSnapshot().isEmpty());
    }

    @Test
    void staleProfileOrLifecycleEvidenceCannotMutateMembership()
            throws Exception {
        createProfile(PROFILE_A, null, 2);
        CommandRosterMembershipRequest stale = new CommandRosterMembershipRequest(
                CommandRosterMembershipRequest.Action.UPSERT,
                PROFILE_A,
                FAMILY,
                SLOT_A,
                0,
                null,
                1,
                "Mini",
                LifecycleRevision.INITIAL,
                "world-a",
                null,
                false,
                null,
                -4_000
        );

        OperationWorkflowResult result =
                adapter.commandRosterOperations().submit(
                        operationId(20),
                        new IdempotencyKey("command:stale"),
                        stale
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertTrue(rootMessage(result.failure()).contains(
                "command_roster_source_mismatch"
        ));
    }

    @Test
    void storedLiveStoredTransitionSharesGroupReservationAndProjections()
            throws Exception {
        createProfile(PROFILE_A, SLOT_A, 3);
        classify(PROFILE_A, 2, 30);
        published(addMembership(PROFILE_A, SLOT_A, 0, 31));

        CompanionLifecycle stored = lifecycle(
                PROFILE_A, LifecycleState.ROSTER_STORED, SLOT_A, 0
        );
        CompanionLifecycle active = lifecycle(
                PROFILE_A, LifecycleState.ACTIVE, SLOT_A, 1
        );
        OperationId activateId = operationId(32);
        published(await(transition(
                activateId, SLOT_A, 1, stored, active, 2
        )));

        assertEquals(0, reservationCount(activateId));
        assertEquals(LifecycleState.ACTIVE,
                lifecycleRead(PROFILE_A).state());
        assertEquals(
                new PopulationGroupCounts(1, 1, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
        assertEquals(LifecycleState.ACTIVE,
                adapter.commandRosterIndex().actionSnapshot()
                        .get(PROFILE_A).lifecycle().state());

        CompanionLifecycle restored = lifecycle(
                PROFILE_A, LifecycleState.ROSTER_STORED, SLOT_A, 2
        );
        OperationId storeId = operationId(33);
        published(await(transition(
                storeId, SLOT_A, 1, active, restored, 2
        )));
        assertEquals(0, reservationCount(storeId));
        assertEquals(
                new PopulationGroupCounts(1, 0, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
        assertTrue(adapter.commandRosterIndex()
                .laggingProfiles().isEmpty());
    }

    @Test
    void concurrentStoredProfilesCannotOverAdmitOneActiveSlot()
            throws Exception {
        createProfile(PROFILE_A, SLOT_A, 4);
        createProfile(PROFILE_B, SLOT_B, 5);
        classify(PROFILE_A, 2, 40);
        classify(PROFILE_B, 2, 41);
        published(addMembership(PROFILE_A, SLOT_A, 0, 42));
        published(addMembership(PROFILE_B, SLOT_B, 1, 43));

        CompletableFuture<OperationWorkflowResult> first = transition(
                operationId(44),
                SLOT_A,
                1,
                lifecycle(
                        PROFILE_A,
                        LifecycleState.ROSTER_STORED,
                        SLOT_A,
                        0
                ),
                lifecycle(
                        PROFILE_A,
                        LifecycleState.ACTIVE,
                        SLOT_A,
                        1
                ),
                1
        ).completion().toCompletableFuture();
        CompletableFuture<OperationWorkflowResult> second = transition(
                operationId(45),
                SLOT_B,
                1,
                lifecycle(
                        PROFILE_B,
                        LifecycleState.ROSTER_STORED,
                        SLOT_B,
                        0
                ),
                lifecycle(
                        PROFILE_B,
                        LifecycleState.ACTIVE,
                        SLOT_B,
                        1
                ),
                1
        ).completion().toCompletableFuture();
        CompletableFuture.allOf(first, second)
                .get(10, TimeUnit.SECONDS);

        List<OperationWorkflowResult.Status> statuses =
                List.of(first.join().status(), second.join().status());
        assertEquals(1, statuses.stream().filter(status ->
                status == OperationWorkflowResult.Status.PUBLISHED).count());
        assertEquals(1, statuses.stream().filter(status ->
                status == OperationWorkflowResult.Status.PREPARE_FAILED)
                .count());
        OperationWorkflowResult denied =
                first.join().status()
                        == OperationWorkflowResult.Status.PREPARE_FAILED
                        ? first.join() : second.join();
        String denial = rootMessage(denied.failure());
        assertTrue(
                denial.contains("operation_command_family_busy")
                        || denial.contains(
                        "population_group_active_capacity_reached"
                ),
                denial
        );
        assertEquals(
                new PopulationGroupCounts(2, 1, 0, 0),
                adapter.populationGroupIndex().counts(bucket())
        );
    }

    @Test
    void startupRecoveryReentersTypedMembershipOperation()
            throws Exception {
        createProfile(PROFILE_A, null, 6);
        CommandRosterMembershipRequest request =
                membershipRequest(
                        CommandRosterMembershipRequest.Action.UPSERT,
                        PROFILE_A,
                        SLOT_A,
                        0,
                        null,
                        false
                );
        PersistenceTransactionResult<?> prepared =
                adapter.publicOperations().engine().prepare(
                        CommandRosterMembershipDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId(50),
                                new IdempotencyKey("command:recover"),
                                request,
                                SqliteCommandRosterMembershipOperations
                                        .FEATURE_SCOPE,
                                LifecycleRevision.INITIAL,
                                List.of(
                                        OperationScope.profile(PROFILE_A),
                                        OperationScope.owner(OWNER),
                                        OperationScope.commandFamily(FAMILY)
                                ),
                                request.requestedAtMs()
                        )
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                prepared
        );

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "command-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(PROFILE_A, membership(PROFILE_A).profileId());
    }

    @Test
    void startupRecoveryRetainsTransitionAdmissionEvidence()
            throws Exception {
        createProfile(PROFILE_A, SLOT_A, 7);
        classify(PROFILE_A, 2, 60);
        published(addMembership(PROFILE_A, SLOT_A, 0, 61));
        CompanionLifecycle stored = lifecycle(
                PROFILE_A,
                LifecycleState.ROSTER_STORED,
                SLOT_A,
                0
        );
        CompanionLifecycle active = lifecycle(
                PROFILE_A,
                LifecycleState.ACTIVE,
                SLOT_A,
                1
        );
        CommandRosterTransitionRequest request = transitionRequest(
                SLOT_A, 1, stored, active, 1
        );
        OperationId operationId = operationId(62);

        PersistenceTransactionResult<?> prepared =
                adapter.publicOperations().engine().prepare(
                        CommandRosterTransitionDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                new IdempotencyKey(
                                        "command:transition:recover"
                                ),
                                request,
                                SqliteCommandRosterTransitionOperations
                                        .FEATURE_SCOPE,
                                LifecycleRevision.INITIAL,
                                List.of(
                                        OperationScope.profile(PROFILE_A),
                                        OperationScope.owner(OWNER),
                                        OperationScope.commandFamily(FAMILY)
                                ),
                                active.stateChangedAtMs()
                        ),
                        SqliteCommandRosterTransitionOperations
                                .preparationDetail(request)
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                prepared
        );
        assertEquals(1, reservationCount(operationId));

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "command-transition-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(0, reservationCount(operationId));
        assertEquals(LifecycleState.ACTIVE,
                lifecycleRead(PROFILE_A).state());
    }
}
