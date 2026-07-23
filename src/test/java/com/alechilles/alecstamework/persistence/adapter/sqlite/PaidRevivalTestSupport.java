package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.revival.RevivalInventoryReservation;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.sql.Connection;
import java.util.List;

/** Shared exact canonical setup for paid-revival adapter and crash tests. */
final class PaidRevivalTestSupport {
    static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000301"
    );
    static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000301"
    );
    static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000301"
    );
    static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "primary");
    static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "40000000-0000-0000-0000-000000000301"
            );
    static final SnapshotId SNAPSHOT = SnapshotId.parse(
            "50000000-0000-0000-0000-000000000301"
    );
    static final long REQUESTED_AT = -2_000;
    static final long CLOCK = -1_000;

    private PaidRevivalTestSupport() {
    }

    static void seed(SqliteConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            require(transaction.identities().createProfile(
                    new CompanionIdentity(
                            PROFILE,
                            "Revival",
                            "role",
                            null,
                            null,
                            "world",
                            -5_000,
                            -5_000,
                            -5_000,
                            0
                    )
            ));
            require(transaction.lifecycles().create(before()));
            require(transaction.snapshots().replaceCurrent(snapshot()));
            require(transaction.populationGroups().replaceAssignment(
                    null,
                    new PopulationGroupAssignment(
                            PROFILE,
                            "role",
                            List.of(new PopulationGroupMembership(
                                    "mod:companions",
                                    PopulationGroupScope.GLOBAL
                            )),
                            7,
                            0,
                            LifecycleRevision.INITIAL,
                            1,
                            -5_000
                    )
            ));
            require(transaction.commandRosters().upsert(
                    0,
                    null,
                    new CommandRosterMembershipDraft(
                            SLOT,
                            FAMILY,
                            PROFILE,
                            "companions",
                            true,
                            null,
                            -5_000
                    )
            ));
            connection.commit();
        }
    }

    static PaidRevivalRequest request() {
        return request(null);
    }

    static PaidRevivalRequest request(TimedSummonActivation timed) {
        return new PaidRevivalRequest(
                FAMILY,
                SLOT,
                1,
                0,
                new PopulationGroupTransitionAdmissionRequest(
                        before(),
                        after(),
                        1,
                        7,
                        List.of(policy()),
                        REQUESTED_AT
                ),
                snapshot(),
                ALIAS,
                "world",
                "placement-fingerprint",
                "revive-config",
                "config-revision",
                List.of(
                        new RevivalCostItem("life-essence", 3),
                        new RevivalCostItem("gold-bar", 2)
                ),
                List.of(
                        reservation(0, 0, "backpack", 1, 1),
                        reservation(0, 1, "storage", 2, 2),
                        reservation(1, 0, "hotbar", 3, 2)
                ),
                "charge-receipt",
                "spawn-receipt",
                timed,
                REQUESTED_AT
        );
    }

    static CompanionLifecycle before() {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.DEAD_REVIVABLE,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -3_000,
                ReconciliationGeneration.INITIAL,
                null,
                "world"
        );
    }

    private static CompanionLifecycle after() {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(ALIAS.toString(), "world"),
                new LifecycleRevision(1),
                null,
                REQUESTED_AT,
                ReconciliationGeneration.INITIAL,
                null,
                "world"
        );
    }

    static CompanionSnapshot snapshot() {
        String json = "{\"death\":true}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                new SnapshotKind("death"),
                1,
                json,
                Sha256Hash.ofUtf8(json),
                LifecycleRevision.INITIAL,
                true,
                -3_000
        );
    }

    private static PopulationGroupPolicy policy() {
        return new PopulationGroupPolicy(
                "mod:companions",
                PopulationGroupScope.GLOBAL,
                5,
                5,
                7
        );
    }

    private static RevivalInventoryReservation reservation(
            int costOrdinal,
            int stackOrdinal,
            String compartment,
            int slot,
            int quantity
    ) {
        return new RevivalInventoryReservation(
                costOrdinal,
                stackOrdinal,
                compartment,
                slot,
                quantity,
                "stack-" + costOrdinal + "-" + stackOrdinal,
                3
        );
    }

    private static void require(
            com.alechilles.alecstamework.persistence.kernel
                    .PersistenceMutationResult<?> result
    ) {
        if (!result.applied()) {
            throw new IllegalStateException(
                    "paid_revival_test_seed_" + result.status()
            );
        }
    }
}
