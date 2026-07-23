package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Payload, recipe, and typed live-disposition gates for paid revival. */
class PaidRevivalDefinitionTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000201"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000201"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000201"
    );
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "primary");
    private static final CommandRosterSlotId SLOT =
            CommandRosterSlotId.parse(
                    "40000000-0000-0000-0000-000000000201"
            );
    private static final long NOW = -2_000;

    @Test
    void exactMultiItemPayloadRoundTripsAndFinalizesOneFence() {
        PaidRevivalRequest request = request(
                List.of(
                        new RevivalCostItem("life-essence", 3),
                        new RevivalCostItem("gold-bar", 2)
                ),
                List.of(
                        reservation(1, 0, "hotbar", 2, 2),
                        reservation(0, 1, "storage", 4, 2),
                        reservation(0, 0, "backpack", 1, 1)
                )
        );

        assertEquals(
                request,
                PaidRevivalDefinition.INSTANCE.decode(
                        PaidRevivalDefinition.INSTANCE.encode(request)
                )
        );
        assertEquals(
                List.of(
                        reservation(0, 0, "backpack", 1, 1),
                        reservation(0, 1, "storage", 4, 2),
                        reservation(1, 0, "hotbar", 2, 2)
                ),
                request.reservations()
        );
        assertEquals(new LifecycleRevision(7),
                request.finalLifecycle().revision());
        assertEquals(LifecycleState.ACTIVE,
                request.finalLifecycle().state());
    }

    @Test
    void duplicateCostsAndIncompleteReservationRecipesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> request(
                List.of(
                        new RevivalCostItem("life-essence", 1),
                        new RevivalCostItem("life-essence", 2)
                ),
                List.of(
                        reservation(0, 0, "backpack", 1, 1),
                        reservation(1, 0, "hotbar", 2, 2)
                )
        ));
        assertThrows(IllegalArgumentException.class, () -> request(
                List.of(new RevivalCostItem("life-essence", 3)),
                List.of(reservation(0, 0, "backpack", 1, 2))
        ));
    }

    @Test
    void economicDispositionsUseOnlyTheSharedLiveVocabulary() {
        assertEquals(
                LiveOperationResult.Status.CONFIRMED,
                PaidRevivalLiveResult.confirmed("both_receipts")
                        .sharedResult().status()
        );
        assertEquals(
                LiveOperationResult.Status.COMPENSATE,
                PaidRevivalLiveResult.noCharge("no_receipts")
                        .sharedResult().status()
        );
        assertEquals(
                LiveOperationResult.Status.COMPENSATE,
                PaidRevivalLiveResult.refundRequired("charge_only")
                        .sharedResult().status()
        );
    }

    @Test
    void deathWorldMayDifferFromTheExplicitRevivalTargetWorld() {
        PaidRevivalRequest request = request(
                "world-before-death",
                "world-after-revival",
                List.of(new RevivalCostItem("life-essence", 1)),
                List.of(reservation(0, 0, "backpack", 1, 1))
        );

        assertEquals(
                "world-after-revival",
                request.finalLifecycle().ownerWorldKey()
        );
    }

    private PaidRevivalRequest request(
            List<RevivalCostItem> cost,
            List<RevivalInventoryReservation> reservations
    ) {
        return request("world", "world", cost, reservations);
    }

    private PaidRevivalRequest request(
            String sourceWorld,
            String targetWorld,
            List<RevivalCostItem> cost,
            List<RevivalInventoryReservation> reservations
    ) {
        CompanionLifecycle before = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.DEAD_REVIVABLE,
                LifecycleLocation.none(),
                new LifecycleRevision(5),
                null,
                -3_000,
                ReconciliationGeneration.INITIAL,
                null,
                sourceWorld
        );
        CompanionLifecycle after = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), targetWorld
                ),
                new LifecycleRevision(6),
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                targetWorld
        );
        PopulationGroupTransitionAdmissionRequest admission =
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        4,
                        List.of(new PopulationGroupPolicy(
                                "companions",
                                PopulationGroupScope.GLOBAL,
                                10,
                                3,
                                4
                        )),
                        NOW
                );
        String snapshotJson = "{\"death\":true}";
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse(
                        "50000000-0000-0000-0000-000000000201"
                ),
                PROFILE,
                new SnapshotKind("death"),
                1,
                snapshotJson,
                Sha256Hash.ofUtf8(snapshotJson),
                new LifecycleRevision(5),
                true,
                -3_000
        );
        return new PaidRevivalRequest(
                FAMILY,
                SLOT,
                2,
                7,
                admission,
                snapshot,
                ALIAS,
                targetWorld,
                "placement-fingerprint",
                "revive-config",
                "revision-hash",
                cost,
                reservations,
                "charge-receipt",
                "spawn-receipt",
                null,
                NOW
        );
    }

    private RevivalInventoryReservation reservation(
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
                "fingerprint-" + costOrdinal + "-" + stackOrdinal,
                9
        );
    }
}
