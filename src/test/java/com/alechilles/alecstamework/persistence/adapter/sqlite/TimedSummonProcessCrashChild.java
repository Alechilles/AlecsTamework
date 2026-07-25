package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Forked JVM that halts at timed summon's durable commit boundaries. */
final class TimedSummonProcessCrashChild {
    static final int HALT_EXIT_CODE = 91;
    static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000071");
    static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000071");
    static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000071");
    static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "default");
    static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "50000000-0000-0000-0000-000000000071"
            );
    static final OperationId OPERATION =
            OperationId.parse("60000000-0000-0000-0000-000000000071");
    static final SnapshotId SNAPSHOT =
            SnapshotId.parse("70000000-0000-0000-0000-000000000071");

    private TimedSummonProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path haltMarker = Path.of(args[2]).toAbsolutePath().normalize();
        Path spawnReceipt = Path.of(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(connections, () -> -10_000)
                .initialize();
        seed(connections, boundary);
        AtomicInteger commits = new AtomicInteger();
        SqliteSingleWriter writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, operationId) -> {
                    if (!OPERATION.equals(operationId)
                            || checkpoint != PersistenceCheckpoint.BEFORE_COMMIT
                            && checkpoint
                            != PersistenceCheckpoint.COMMIT_RETURNED) {
                        return;
                    }
                    if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT) {
                        int commit = commits.incrementAndGet();
                        if (!boundary.committed() && commit == 3) {
                            halt(haltMarker, boundary);
                        }
                    } else if (boundary.committed()
                            && commits.get() == 3) {
                        halt(haltMarker, boundary);
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        var result = operations(writer, reads).submit(
                OPERATION,
                new IdempotencyKey("timed-process-crash"),
                request(boundary),
                (transition, operation) -> {
                    Files.writeString(
                            spawnReceipt,
                            boundary.storing() ? "store" : "spawn"
                    );
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        throw new IllegalStateException(
                "Timed summon crash boundary was not reached: "
                        + result.status() + ":"
                        + messages(result.failure())
        );
    }

    static SqliteTimedSummonTransitionOperations operations(
            SqliteSingleWriter writer,
            SqliteReadExecutor reads
    ) {
        SqliteUnitOfWorkRunner units =
                new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        TimedSummonTransitionDefinition.INSTANCE
                )),
                units
        );
        return new SqliteTimedSummonTransitionOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -400
                        ),
                        () -> -400
                ),
                () -> -400,
                List.of()
        );
    }

    static TimedSummonTransitionRequest request(Boundary boundary) {
        return boundary.storing() ? storeRequest() : startRequest();
    }

    private static TimedSummonTransitionRequest startRequest() {
        CompanionLifecycle before = lifecycle(
                LifecycleState.ROSTER_STORED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        SLOT.toString()
                ),
                LifecycleRevision.INITIAL,
                -10_000
        );
        CompanionLifecycle after = lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                ),
                new LifecycleRevision(1),
                -600
        );
        return new TimedSummonTransitionRequest(
                TimedSummonTransitionRequest.Action.START,
                FAMILY,
                SLOT,
                1,
                storedLease(),
                activeLease(),
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        1,
                        List.of(groupPolicy()),
                        -600
                ),
                ALIAS,
                "world-a",
                new CompanionSpawnPlacement(
                        "world-a",
                        -12.5,
                        -63.05,
                        -4.5,
                        -0.25f,
                        -1.5f,
                        -0.5f
                ),
                snapshot(true),
                "timed-process-receipt",
                -600
        );
    }

    private static TimedSummonTransitionRequest storeRequest() {
        CompanionLifecycle before = lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                ),
                LifecycleRevision.INITIAL,
                -10_000
        );
        CompanionLifecycle after = lifecycle(
                LifecycleState.ROSTER_STORED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        SLOT.toString()
                ),
                new LifecycleRevision(1),
                -600
        );
        return new TimedSummonTransitionRequest(
                TimedSummonTransitionRequest.Action.STORE,
                FAMILY,
                SLOT,
                1,
                activeLeaseAtRevisionOne(),
                storedLeaseAtRevisionTwo(),
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        1,
                        List.of(groupPolicy()),
                        -600
                ),
                ALIAS,
                "world-a",
                null,
                storeSnapshot(),
                "timed-store-process-receipt",
                -600
        );
    }

    private static void seed(
            SqliteConnectionFactory connections,
            Boundary boundary
    )
            throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Crash Companion",
                    "Mini",
                    null,
                    null,
                    "world-a",
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            transaction.lifecycles().create(
                    request(boundary).groupAdmission().before()
            );
            transaction.populationGroups().replaceAssignment(
                    null,
                    new PopulationGroupAssignment(
                            PROFILE,
                            "Mini",
                            List.of(new PopulationGroupMembership(
                                    "mod:mini",
                                    PopulationGroupScope.GLOBAL
                            )),
                            1,
                            0,
                            LifecycleRevision.INITIAL,
                            1,
                            -9_000
                    )
            );
            transaction.commandRosters().upsert(
                    0,
                    null,
                    new CommandRosterMembershipDraft(
                            SLOT,
                            FAMILY,
                            PROFILE,
                            null,
                            false,
                            null,
                            -9_000
                    )
            );
            if (boundary.storing()) {
                try (PreparedStatement statement =
                             connection.prepareStatement("""
                                     INSERT INTO companion_alias(
                                         npc_uuid, profile_id,
                                         alias_generation, alias_state,
                                         lease_operation_id,
                                         mapped_at_ms, retired_at_ms
                                     ) VALUES (?, ?, 0, 'CURRENT',
                                         NULL, ?, NULL)
                                     """)) {
                    statement.setString(1, ALIAS.toString());
                    statement.setString(2, PROFILE.toString());
                    statement.setLong(3, -9_000);
                    statement.executeUpdate();
                }
                transaction.timedSummons().replace(
                        null, activeLeaseAtRevisionOne()
                );
            } else {
                transaction.snapshots().replaceCurrent(snapshot(true));
                transaction.timedSummons().replace(null, storedLease());
            }
            connection.commit();
        }
    }

    private static CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleLocation location,
            LifecycleRevision revision,
            long changedAtMs
    ) {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                location,
                revision,
                null,
                changedAtMs,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private static TimedSummonLease storedLease() {
        return new TimedSummonLease(
                PROFILE,
                1,
                null,
                null,
                -700L,
                timedPolicy(),
                Set.of(),
                null,
                -10_000,
                -9_000
        );
    }

    private static TimedSummonLease activeLease() {
        return new TimedSummonLease(
                PROFILE,
                2,
                new TimedSummonSessionId(new UUID(0, 71)),
                10_000L,
                null,
                timedPolicy(),
                Set.of(),
                -600L,
                -10_000,
                -600
        );
    }

    private static TimedSummonLease activeLeaseAtRevisionOne() {
        return new TimedSummonLease(
                PROFILE,
                1,
                new TimedSummonSessionId(new UUID(0, 72)),
                4_000L,
                null,
                timedPolicy(),
                Set.of(5_000L),
                -700L,
                -10_000,
                -700
        );
    }

    private static TimedSummonLease storedLeaseAtRevisionTwo() {
        return new TimedSummonLease(
                PROFILE,
                2,
                null,
                null,
                900L,
                timedPolicy(),
                Set.of(),
                null,
                -10_000,
                -600
        );
    }

    private static TimedSummonPolicy timedPolicy() {
        return new TimedSummonPolicy(
                "role:timed",
                1L,
                10_000,
                1_500,
                true,
                List.of(5_000L, 1_000L)
        );
    }

    private static PopulationGroupPolicy groupPolicy() {
        return new PopulationGroupPolicy(
                "mod:mini",
                PopulationGroupScope.GLOBAL,
                2,
                1,
                1
        );
    }

    private static CompanionSnapshot snapshot(boolean current) {
        String payload = "{\"state\":\"stored\"}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                TimedSummonTransitionRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                current,
                -9_000
        );
    }

    private static CompanionSnapshot storeSnapshot() {
        String payload = "{\"state\":\"returned\"}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                TimedSummonTransitionRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                new LifecycleRevision(1),
                true,
                -600
        );
    }

    private static void halt(Path marker, Boundary boundary)
            throws Exception {
        Files.writeString(marker, boundary.name());
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        while (failure != null) {
            result.append(failure.getMessage()).append(':');
            failure = failure.getCause();
        }
        return result.toString();
    }

    enum Boundary {
        SUMMON_DURABLE_UNCOMMITTED(false, false),
        SUMMON_DURABLE_COMMITTED(false, true),
        STORE_DURABLE_UNCOMMITTED(true, false),
        STORE_DURABLE_COMMITTED(true, true);

        private final boolean storing;
        private final boolean committed;

        Boundary(boolean storing, boolean committed) {
            this.storing = storing;
            this.committed = committed;
        }

        boolean storing() {
            return storing;
        }

        boolean committed() {
            return committed;
        }
    }
}

