package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Focused fixture shared by command operation integration scenarios. */
abstract class CommandRosterTestSupport {
    protected static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000111");
    protected static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000112");
    protected static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000111");
    protected static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "default");
    protected static final CommandRosterSlotId SLOT_A =
            slot("50000000-0000-0000-0000-000000000111");
    protected static final CommandRosterSlotId SLOT_B =
            slot("50000000-0000-0000-0000-000000000112");

    @TempDir
    Path tempDir;

    protected SqliteConnectionFactory connections;
    protected SqlitePersistenceKernel kernel;
    protected SqlitePublicPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000)
                .initialize();
        kernel = new SqlitePersistenceKernel(connections);
        adapter = new SqlitePublicPersistenceAdapter(
                PublicPersistenceFeatureRegistry.create(),
                kernel,
                PersistenceOperationAdmissionGate.allowAll(),
                () -> -5_000,
                (claim, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("refund")
                                .completed(),
                event -> {
                }
        );
    }

    @AfterEach
    void tearDown() {
        if (kernel != null) {
            kernel.shutdown(Duration.ofSeconds(5));
        }
    }

    protected OperationWorkflowResult addMembership(
            ProfileId profileId,
            CommandRosterSlotId slotId,
            long rosterRevision,
            int operation
    ) throws Exception {
        return adapter.commandRosterOperations().submit(
                operationId(operation),
                new IdempotencyKey("command:add:" + operation),
                membershipRequest(
                        CommandRosterMembershipRequest.Action.UPSERT,
                        profileId,
                        slotId,
                        rosterRevision,
                        null,
                        false
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    protected OperationWorkflowResult updateMembership(
            ProfileId profileId,
            CommandRosterSlotId slotId,
            long rosterRevision,
            long memberRevision,
            int operation
    ) throws Exception {
        return adapter.commandRosterOperations().submit(
                operationId(operation),
                new IdempotencyKey("command:update:" + operation),
                membershipRequest(
                        CommandRosterMembershipRequest.Action.UPSERT,
                        profileId,
                        slotId,
                        rosterRevision,
                        memberRevision,
                        true
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    protected OperationWorkflowResult removeMembership(
            ProfileId profileId,
            CommandRosterSlotId slotId,
            long rosterRevision,
            long memberRevision,
            int operation
    ) throws Exception {
        return adapter.commandRosterOperations().submit(
                operationId(operation),
                new IdempotencyKey("command:remove:" + operation),
                membershipRequest(
                        CommandRosterMembershipRequest.Action.REMOVE,
                        profileId,
                        slotId,
                        rosterRevision,
                        memberRevision,
                        true
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    protected CommandRosterMembershipRequest membershipRequest(
            CommandRosterMembershipRequest.Action action,
            ProfileId profileId,
            CommandRosterSlotId slotId,
            long rosterRevision,
            Long memberRevision,
            boolean activeForBulk
    ) {
        return new CommandRosterMembershipRequest(
                action,
                profileId,
                FAMILY,
                slotId,
                rosterRevision,
                memberRevision,
                0,
                "Mini",
                LifecycleRevision.INITIAL,
                "world-a",
                activeForBulk ? "favorites" : null,
                activeForBulk,
                null,
                -4_000
        );
    }

    protected SqliteDatabaseOperationCoordinator.Submission transition(
            OperationId operationId,
            CommandRosterSlotId slotId,
            long memberRevision,
            CompanionLifecycle before,
            CompanionLifecycle after,
            int activeLimit
    ) {
        return adapter.commandRosterTransitionOperations().submit(
                operationId,
                new IdempotencyKey("command:transition:" + operationId),
                transitionRequest(
                        slotId,
                        memberRevision,
                        before,
                        after,
                        activeLimit
                )
        );
    }

    protected CommandRosterTransitionRequest transitionRequest(
            CommandRosterSlotId slotId,
            long memberRevision,
            CompanionLifecycle before,
            CompanionLifecycle after,
            int activeLimit
    ) {
        return new CommandRosterTransitionRequest(
                FAMILY,
                slotId,
                memberRevision,
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        1,
                        List.of(policy(2, activeLimit)),
                        after.stateChangedAtMs()
                )
        );
    }

    protected void createProfile(
            ProfileId profileId,
            CommandRosterSlotId storedSlot,
            int operation
    ) throws Exception {
        CompanionLifecycle lifecycle = storedSlot == null
                ? lifecycle(profileId, LifecycleState.UNLOADED, null, 0)
                : lifecycle(
                        profileId,
                        LifecycleState.ROSTER_STORED,
                        storedSlot,
                        0
                );
        CompanionProfileMutation.Create create =
                new CompanionProfileMutation.Create(
                        new CompanionIdentity(
                                profileId,
                                "Companion",
                                "Mini",
                                null,
                                null,
                                "world-a",
                                -10_000,
                                -10_000,
                                -10_000,
                                0
                        ),
                        lifecycle,
                        List.of(),
                        -10_000
                );
        published(adapter.profileOperations().submit(
                operationId(operation),
                new IdempotencyKey("profile:" + operation),
                create
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    protected void classify(
            ProfileId profileId,
            int ownedLimit,
            int operation
    ) throws Exception {
        published(adapter.populationGroupOperations().submit(
                operationId(operation),
                new IdempotencyKey("group:" + operation),
                new PopulationGroupAssignmentRequest(
                        profileId,
                        0,
                        "Mini",
                        LifecycleRevision.INITIAL,
                        OWNER,
                        "world-a",
                        null,
                        1,
                        List.of(policy(ownedLimit, 1)),
                        -4_000
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }

    protected PopulationGroupPolicy policy(
            int ownedLimit,
            int activeLimit
    ) {
        return new PopulationGroupPolicy(
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                ownedLimit,
                activeLimit,
                1
        );
    }

    protected CompanionLifecycle lifecycle(
            ProfileId profileId,
            LifecycleState state,
            CommandRosterSlotId slotId,
            long revision
    ) {
        LifecycleLocation location = switch (state) {
            case ACTIVE -> LifecycleLocation.liveEntity(
                    "entity-" + profileId, "world-a"
            );
            case ROSTER_STORED -> LifecycleLocation.keyed(
                    LifecycleLocationKind.COMMAND_ROSTER,
                    slotId.toString()
            );
            case UNLOADED -> LifecycleLocation.none();
            default -> throw new IllegalArgumentException(
                    "Unsupported test lifecycle state"
            );
        };
        return new CompanionLifecycle(
                profileId,
                OWNER,
                state,
                location,
                new LifecycleRevision(revision),
                null,
                -5_000 + revision,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    protected CommandRosterMembership membership(ProfileId profileId)
            throws Exception {
        PersistenceReadResult.Found<CommandRosterMembership> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.commandRosterReader()
                                .findByProfile(profileId)
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        return found.value();
    }

    protected CompanionLifecycle lifecycleRead(ProfileId profileId)
            throws Exception {
        PersistenceReadResult.Found<List<CompanionLifecycle>> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        adapter.lifecycleReader()
                                .findAll()
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        return found.value().stream()
                .filter(value -> profileId.equals(value.profileId()))
                .findFirst()
                .orElseThrow();
    }

    protected OperationWorkflowResult await(
            SqliteDatabaseOperationCoordinator.Submission submission
    ) throws Exception {
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    protected PopulationGroupBucket bucket() {
        return new PopulationGroupBucket(
                OWNER,
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                null
        );
    }

    protected int reservationCount(OperationId operationId)
            throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqlitePopulationGroupStore(connection)
                    .findReservations(operationId).size();
        }
    }

    protected void published(OperationWorkflowResult result) {
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                () -> rootMessage(result.failure())
        );
    }

    protected PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("capture")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("capture-release")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("restore")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("coop-in")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("coop-out")
                                .completed(),
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("timed")
                                .completed()
        );
    }

    protected OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "40000000-0000-0000-0000-%012d", number
        ));
    }

    protected String rootMessage(Throwable failure) {
        ArrayList<String> messages = new ArrayList<>();
        while (failure != null) {
            if (failure.getMessage() != null) {
                messages.add(failure.getMessage());
            }
            failure = failure.getCause();
        }
        return String.join(":", messages);
    }

    private static CommandRosterSlotId slot(String value) {
        return CommandRosterSlotId.parse(value);
    }
}

