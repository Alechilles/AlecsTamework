package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Payload, recipe, projection, and typed disposition gates for paid revival. */
class PaidRevivalDefinitionTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000201"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000201"
    );
    private static final NpcAlias SOURCE_ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000200"
    );
    private static final NpcAlias TARGET_ALIAS = NpcAlias.parse(
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
    void exactMultiItemPayloadAndProjectionRoundTrip() throws Exception {
        PaidRevivalRequest request = request(
                projection(SOURCE_ALIAS),
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

        assertEquals(4, PaidRevivalDefinition.INSTANCE.payloadVersion());
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
        assertEquals(NOW, request.requestedAtMs());
        assertEquals("world-target", request.refundRecipientWorldKey());
        assertEquals(new LifecycleRevision(7),
                request.finalLifecycle().revision());
        assertEquals(LifecycleState.ACTIVE,
                request.finalLifecycle().state());
    }

    @Test
    void recipeMustBeUniqueContiguousAndExact() {
        assertThrows(IllegalArgumentException.class, () -> request(
                projection(SOURCE_ALIAS),
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
                projection(SOURCE_ALIAS),
                List.of(new RevivalCostItem("life-essence", 3)),
                List.of(reservation(0, 0, "backpack", 1, 2))
        ));
        assertThrows(IllegalArgumentException.class, () -> request(
                projection(SOURCE_ALIAS),
                List.of(new RevivalCostItem("life-essence", 2)),
                List.of(
                        reservation(0, 0, "backpack", 1, 1),
                        reservation(0, 2, "storage", 2, 1)
                )
        ));
        assertThrows(IllegalArgumentException.class, () -> request(
                projection(SOURCE_ALIAS),
                List.of(
                        new RevivalCostItem("life-essence", 1),
                        new RevivalCostItem("gold-bar", 1)
                ),
                List.of(
                        reservation(0, 0, "backpack", 1, 1),
                        reservation(1, 0, "backpack", 1, 1)
                )
        ));
    }

    @Test
    void modernProjectionMustRetainDistinctSourceAlias() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        projection(TARGET_ALIAS),
                        List.of(),
                        List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        new RestorationProjection(
                                SOURCE_ALIAS,
                                encoded(
                                        new SnapshotKind("legacy"),
                                        CompanionFullStateProjection.VERSION
                                )
                        ),
                        List.of(),
                        List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        new RestorationProjection(
                                SOURCE_ALIAS,
                                encoded(
                                        CompanionFullStateProjection.KIND,
                                        CompanionFullStateProjection.VERSION + 1
                                )
                        ),
                        List.of(),
                        List.of()
                )
        );
    }

    @Test
    void emptyCostIsValidButCannotRequireARefund() {
        PaidRevivalRequest free = request(
                projection(SOURCE_ALIAS),
                List.of(),
                List.of()
        );

        assertEquals(List.of(), free.exactCost());
        assertEquals(List.of(), free.reservations());
        assertThrows(
                IllegalArgumentException.class,
                () -> PaidRevivalLiveResult.refundRequired(
                        free, "charge_only"
                )
        );
    }

    @Test
    void everyEconomicDispositionMapsToSharedProtocol() {
        PaidRevivalRequest charged = request(
                projection(SOURCE_ALIAS),
                List.of(new RevivalCostItem("life-essence", 1)),
                List.of(reservation(0, 0, "backpack", 1, 1))
        );
        RuntimeException failure = new RuntimeException("failure");

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
                PaidRevivalLiveResult.refundRequired(
                        charged, "charge_only"
                ).sharedResult().status()
        );
        assertEquals(
                LiveOperationResult.Status.RETRYABLE,
                PaidRevivalLiveResult.retryable("safe_retry", failure)
                        .sharedResult().status()
        );
        assertEquals(
                LiveOperationResult.Status.UNKNOWN,
                PaidRevivalLiveResult.unknown("ambiguous", failure)
                        .sharedResult().status()
        );
    }

    @Test
    void successfulEventRoundTripsExactEconomicEvidence() {
        PaidRevivalOutcome outcome = new PaidRevivalOutcome(
                "hydragon",
                "miniwyvern-revive-7",
                OWNER,
                FAMILY.familyId(),
                SLOT,
                PROFILE,
                snapshot().snapshotId(),
                TARGET_ALIAS,
                "world-target",
                new LifecycleRevision(7),
                "revive-config",
                "revision-hash",
                List.of(
                        new RevivalCostItem("life-essence", 3),
                        new RevivalCostItem("gold-bar", 2)
                ),
                "charge-receipt",
                "spawn-receipt",
                null,
                NOW
        );

        assertEquals(
                outcome,
                PaidRevivalEventCodec.decode(
                        PaidRevivalEventCodec.VERSION,
                        PaidRevivalEventCodec.encode(outcome)
                )
        );
        assertEquals(
                PaidRevivalEventCodec.EVENT_TYPE,
                PaidRevivalEventCodec.draft(
                        OperationId.parse(
                                "60000000-0000-0000-0000-000000000201"
                        ),
                        outcome
                ).eventType()
        );
    }

    @Test
    void optionalTimedSessionRoundTripsAsOneFullActivation() throws Exception {
        TimedSummonPolicy policy = new TimedSummonPolicy(
                "timed-config",
                4L,
                60_000,
                15_000,
                true,
                List.of(30_000L)
        );
        TimedSummonLease lease = new TimedSummonLease(
                PROFILE,
                1,
                TimedSummonSessionId.parse(
                        "70000000-0000-0000-0000-000000000201"
                ),
                60_000L,
                null,
                policy,
                Set.of(),
                NOW,
                NOW,
                NOW
        );
        PaidRevivalRequest request = request(
                projection(SOURCE_ALIAS),
                List.of(),
                List.of(),
                new TimedSummonActivation(FAMILY, SLOT, 2, lease)
        );

        assertEquals(
                request,
                PaidRevivalDefinition.INSTANCE.decode(
                        PaidRevivalDefinition.INSTANCE.encode(request)
                )
        );
    }

    private PaidRevivalRequest request(
            RestorationProjection projection,
            List<RevivalCostItem> cost,
            List<RevivalInventoryReservation> reservations
    ) {
        return request(projection, cost, reservations, null);
    }

    private PaidRevivalRequest request(
            RestorationProjection projection,
            List<RevivalCostItem> cost,
            List<RevivalInventoryReservation> reservations,
            TimedSummonActivation timedActivation
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
                "world-before-death"
        );
        CompanionLifecycle after = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(), "world-target"
                ),
                new LifecycleRevision(6),
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                "world-target"
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
        return new PaidRevivalRequest(
                "hydragon",
                "miniwyvern-revive-7",
                FAMILY,
                SLOT,
                2,
                7,
                admission,
                snapshot(),
                projection,
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world-target", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "revive-config",
                "revision-hash",
                cost,
                reservations,
                "charge-receipt",
                "spawn-receipt",
                timedActivation,
                NOW
        );
    }

    private CompanionSnapshot snapshot() {
        String snapshotJson = "{\"death\":true}";
        return new CompanionSnapshot(
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
    }

    private RestorationProjection projection(NpcAlias sourceAlias) {
        return new RestorationProjection(
                sourceAlias,
                encoded(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                )
        );
    }

    private SnapshotCodecRegistry.EncodedSnapshot encoded(
            SnapshotKind kind,
            int version
    ) {
        String json = "{\"state\":\"complete\"}";
        return new SnapshotCodecRegistry.EncodedSnapshot(
                kind,
                version,
                json,
                Sha256Hash.ofUtf8(json)
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
