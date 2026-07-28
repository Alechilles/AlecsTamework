package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Canonical signed-world-time fixtures for timed live protocol tests. */
final class TimedSummonWorldTestFixture {
    static final long CHUNK = 77L;
    static final long REQUESTED_AT = -2_000L;
    static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000071"
    );
    static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000071"
    );
    static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000071"
    );
    static final CommandRosterSlotId SLOT = CommandRosterSlotId.parse(
            "40000000-0000-0000-0000-000000000071"
    );
    static final CommandFamilyKey FAMILY =
            new CommandFamilyKey(OWNER, "hydragon:horn");
    static final OperationId OPERATION = OperationId.parse(
            "50000000-0000-0000-0000-000000000071"
    );

    TimedSummonTransitionRequest startRequest() {
        CompanionLifecycle before = lifecycle(
                LifecycleState.ROSTER_STORED,
                LifecycleRevision.INITIAL,
                REQUESTED_AT - 10
        );
        CompanionLifecycle after = lifecycle(
                LifecycleState.ACTIVE,
                before.revision().next(),
                REQUESTED_AT
        );
        TimedSummonLease beforeLease = dormantLease(1, -3_000L);
        TimedSummonLease afterLease = new TimedSummonLease(
                PROFILE,
                2,
                new TimedSummonSessionId(
                        UUID.fromString(
                                "60000000-0000-0000-0000-000000000071"
                        )
                ),
                10_000L,
                null,
                policy(),
                Set.of(),
                REQUESTED_AT,
                -5_000L,
                REQUESTED_AT
        );
        return request(
                TimedSummonTransitionRequest.Action.START,
                before,
                after,
                beforeLease,
                afterLease,
                snapshot(
                        LifecycleRevision.INITIAL,
                        "{\"state\":\"stored\"}"
                )
        );
    }

    TimedSummonTransitionRequest storeRequest() {
        CompanionLifecycle before = lifecycle(
                LifecycleState.ACTIVE,
                LifecycleRevision.INITIAL,
                REQUESTED_AT - 10
        );
        CompanionLifecycle after = lifecycle(
                LifecycleState.ROSTER_STORED,
                before.revision().next(),
                REQUESTED_AT
        );
        TimedSummonLease beforeLease = new TimedSummonLease(
                PROFILE,
                2,
                new TimedSummonSessionId(
                        UUID.fromString(
                                "60000000-0000-0000-0000-000000000072"
                        )
                ),
                9_000L,
                null,
                policy(),
                Set.of(),
                REQUESTED_AT - 1_000L,
                -5_000L,
                REQUESTED_AT - 1_000L
        );
        TimedSummonLease afterLease = dormantLease(3, -500L);
        return request(
                TimedSummonTransitionRequest.Action.STORE,
                before,
                after,
                beforeLease,
                afterLease,
                snapshot(
                        before.revision().next(),
                        "{\"state\":\"returned\"}"
                )
        );
    }

    OperationEnvelope operation(
            TimedSummonTransitionRequest request,
            boolean completeScopes
    ) {
        return operation(
                request, completeScopes, OperationPhase.LIVE_APPLYING
        );
    }

    OperationEnvelope durableOperation(
            TimedSummonTransitionRequest request
    ) {
        return operation(request, true, OperationPhase.DURABLE);
    }

    private OperationEnvelope operation(
            TimedSummonTransitionRequest request,
            boolean completeScopes,
            OperationPhase phase
    ) {
        List<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.operation(OPERATION));
        scopes.add(OperationScope.profile(PROFILE));
        scopes.add(OperationScope.owner(OWNER));
        if (completeScopes) {
            scopes.add(OperationScope.commandFamily(FAMILY));
        }
        return new OperationEnvelope(
                OPERATION,
                new IdempotencyKey("timed-live:test"),
                TimedSummonTransitionDefinition.KIND,
                TimedSummonTransitionDefinition.INSTANCE.payloadVersion(),
                TimedSummonTransitionDefinition.INSTANCE.encode(request),
                phase,
                "timed_summon",
                request.groupAdmission().before().revision(),
                null,
                0,
                0,
                null,
                null,
                REQUESTED_AT,
                REQUESTED_AT,
                phase == OperationPhase.DURABLE
                        ? REQUESTED_AT
                        : null,
                null,
                null,
                scopes
        );
    }

    private TimedSummonTransitionRequest request(
            TimedSummonTransitionRequest.Action action,
            CompanionLifecycle before,
            CompanionLifecycle after,
            TimedSummonLease beforeLease,
            TimedSummonLease afterLease,
            CompanionSnapshot snapshot
    ) {
        return new TimedSummonTransitionRequest(
                action,
                FAMILY,
                SLOT,
                1,
                beforeLease,
                afterLease,
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        1,
                        List.of(new PopulationGroupPolicy(
                                "dragons",
                                PopulationGroupScope.GLOBAL,
                                2,
                                1,
                                1
                        )),
                        REQUESTED_AT
                ),
                ALIAS,
                "world-a",
                action == TimedSummonTransitionRequest.Action.START
                        ? new CompanionSpawnPlacement(
                                "world-a",
                                1,
                                64,
                                2,
                                0,
                                1,
                                0
                        )
                        : null,
                snapshot,
                "timed-receipt:test",
                REQUESTED_AT
        );
    }

    private CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleRevision revision,
            long changedAt
    ) {
        LifecycleLocation location = state == LifecycleState.ACTIVE
                ? LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                )
                : LifecycleLocation.keyed(
                        LifecycleLocationKind.COMMAND_ROSTER,
                        SLOT.toString()
                );
        return new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                location,
                revision,
                null,
                changedAt,
                com.alechilles.alecstamework.companion.lifecycle
                        .ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
    }

    private TimedSummonLease dormantLease(
            long revision,
            long cooldownUntil
    ) {
        return new TimedSummonLease(
                PROFILE,
                revision,
                null,
                null,
                cooldownUntil,
                policy(),
                Set.of(),
                null,
                -5_000L,
                REQUESTED_AT
        );
    }

    private TimedSummonPolicy policy() {
        return new TimedSummonPolicy(
                "role:dragon",
                1L,
                10_000L,
                1_500L,
                true,
                List.of(5_000L, 1_000L)
        );
    }

    private CompanionSnapshot snapshot(
            LifecycleRevision revision,
            String payload
    ) {
        return new CompanionSnapshot(
                SnapshotId.parse(
                        "70000000-0000-0000-0000-000000000071"
                ),
                PROFILE,
                TimedSummonTransitionRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                revision,
                true,
                REQUESTED_AT
        );
    }
}
