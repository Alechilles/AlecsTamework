package com.alechilles.alecstamework.companion.revival.runtime;

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
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.revival.RevivalInventoryReservation;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;

/** Deterministic paid-revival request and envelope fixtures. */
final class PaidRevivalWorldTestFixture {
    static final long TARGET_CHUNK = 41L;
    private static final long NOW = -2_000;
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000221"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000221"
    );
    private static final NpcAlias SOURCE_ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000220"
    );
    private static final NpcAlias TARGET_ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000221"
    );
    private static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "primary");
    private static final OperationId OPERATION = OperationId.parse(
            "60000000-0000-0000-0000-000000000221"
    );

    private PaidRevivalWorldTestFixture() {
    }

    static PaidRevivalRequest request(boolean emptyCost) {
        CompanionLifecycle before = lifecycle(
                LifecycleState.DEAD_REVIVABLE,
                LifecycleLocation.none(),
                5,
                "world-before"
        );
        CompanionLifecycle after = lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(), "world-target"
                ),
                6,
                "world-target"
        );
        List<RevivalCostItem> cost = emptyCost
                ? List.of()
                : List.of(new RevivalCostItem("life-essence", 2));
        List<RevivalInventoryReservation> reservations = emptyCost
                ? List.of()
                : List.of(new RevivalInventoryReservation(
                        0, 0, "backpack", 1, 2,
                        "source-fingerprint", 9
                ));
        return new PaidRevivalRequest(
                FAMILY,
                CommandRosterSlotId.parse(
                        "40000000-0000-0000-0000-000000000221"
                ),
                2,
                7,
                new PopulationGroupTransitionAdmissionRequest(
                        before, after, 1, 1, List.of(), NOW
                ),
                snapshot(),
                projection(),
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world-target", 1, 2, 3, 0, 0, 0
                ),
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

    static OperationEnvelope operation(PaidRevivalRequest request) {
        return new OperationEnvelope(
                OPERATION,
                new IdempotencyKey("paid-revival-test"),
                PaidRevivalDefinition.KIND,
                PaidRevivalDefinition.INSTANCE.payloadVersion(),
                PaidRevivalDefinition.INSTANCE.encode(request),
                OperationPhase.LIVE_APPLYING,
                "paid-revival",
                request.groupAdmission().before().revision(),
                null,
                0,
                1,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                null,
                List.of(
                        OperationScope.operation(OPERATION),
                        OperationScope.profile(PROFILE),
                        OperationScope.owner(OWNER),
                        OperationScope.commandFamily(FAMILY)
                )
        );
    }

    private static CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleLocation location,
            long revision,
            String ownerWorld
    ) {
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                location,
                new LifecycleRevision(revision),
                null,
                state == LifecycleState.ACTIVE ? NOW : -3_000,
                ReconciliationGeneration.INITIAL,
                null,
                ownerWorld
        );
    }

    private static CompanionSnapshot snapshot() {
        String json = "{\"death\":true}";
        return new CompanionSnapshot(
                SnapshotId.parse(
                        "50000000-0000-0000-0000-000000000221"
                ),
                PROFILE,
                new SnapshotKind("death"),
                1,
                json,
                Sha256Hash.ofUtf8(json),
                new LifecycleRevision(5),
                true,
                -3_000
        );
    }

    private static RestorationProjection projection() {
        String json = "{\"state\":\"complete\"}";
        return new RestorationProjection(
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        json,
                        Sha256Hash.ofUtf8(json)
                )
        );
    }
}
