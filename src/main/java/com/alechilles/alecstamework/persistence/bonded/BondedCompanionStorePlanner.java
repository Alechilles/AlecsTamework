package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionStorePlanner;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds the sole complete, revision-fenced plan for mutating a bonded
 * profile from live projection evidence.
 */
public final class BondedCompanionStorePlanner implements
        BondedCompanionProjectionStorePlanner {
    private final BondedCompanionStore store;
    private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    public BondedCompanionStorePlanner(
            @Nonnull BondedCompanionStore store,
            @Nonnull BondedCompanionRosterRegistry rosters
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
    }

    @Override
    @Nonnull
    public PlanningResult plan(@Nonnull PlanningRequest request) {
        Objects.requireNonNull(request, "request");
        BondedCompanionRecord.Profile profile = profile(request);
        if (profile == null) {
            return rejected(Status.PROFILE_NOT_FOUND);
        }
        BondedCompanionRosterRegistry.Snapshot generation = rosters.snapshot();
        Status prerequisite = prerequisite(request, profile, generation);
        if (prerequisite != null) {
            return rejected(prerequisite);
        }
        BondedCompanionSnapshot durable = decode(profile);
        if (durable == null || !matches(profile, durable)) {
            return rejected(Status.SNAPSHOT_INVALID);
        }
        BondedCompanionSnapshot captured = request.capturedSnapshot();
        if (captured == null && request.cause() == Cause.EXPLICIT) {
            return rejected(Status.SNAPSHOT_INVALID);
        }
        if (captured != null && !matches(profile, captured)) {
            return rejected(Status.SNAPSHOT_IDENTITY_MISMATCH);
        }
        return planSnapshot(
                request, profile, durable, captured, generation);
    }

    @Nullable
    private BondedCompanionRecord.Profile profile(PlanningRequest request) {
        return store.findProfile(
                request.lease().ownerUuid(), request.lease().rosterId(),
                request.lease().profileId()
        ).orElse(null);
    }

    @Nullable
    private Status prerequisite(
            PlanningRequest request,
            BondedCompanionRecord.Profile profile,
            BondedCompanionRosterRegistry.Snapshot generation
    ) {
        if (profile.state() != BondedCompanionState.ACTIVE) {
            return Status.INVALID_STATE;
        }
        if (request.expectedRevision() != null
                && profile.revision() != request.expectedRevision()) {
            return Status.REVISION_CONFLICT;
        }
        if (!hasExactLease(request.lease())) {
            return Status.LEASE_MISMATCH;
        }
        if (request.cause() == Cause.EXPLICIT
                && (request.lease().phase()
                != BondedCompanionProjectionValidator.LeasePhase.LIVE
                || !dismissAllowed(profile, generation))) {
            return Status.POLICY_DENIED;
        }
        if (request.cause() == Cause.CONFIRMED_DEATH
                && request.lease().phase()
                != BondedCompanionProjectionValidator.LeasePhase.LIVE) {
            return Status.LEASE_MISMATCH;
        }
        return null;
    }

    private boolean hasExactLease(
            BondedCompanionProjectionValidator.LeaseExpectation expected
    ) {
        for (BondedCompanionRecord.Lease lease : store.findActiveLeases(
                expected.ownerUuid(), expected.rosterId())) {
            if (lease.profileId().equals(expected.profileId())
                    && lease.leaseToken().equals(expected.leaseToken())
                    && lease.liveNpcUuid().equals(expected.liveNpcUuid())
                    && lease.worldKey().equals(expected.worldKey())
                    && lease.projectionState().name().equals(
                            expected.phase().name())) {
                return true;
            }
        }
        return false;
    }

    private boolean dismissAllowed(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRosterRegistry.Snapshot generation
    ) {
        BondedCompanionRosterRegistry.RosterDefinition family =
                family(profile, generation);
        return family != null
                && family.allowedRoles().contains(profile.roleId())
                && family.features().dismiss();
    }

    @Nonnull
    private PlanningResult planSnapshot(
            PlanningRequest request,
            BondedCompanionRecord.Profile profile,
            BondedCompanionSnapshot durable,
            @Nullable BondedCompanionSnapshot captured,
            BondedCompanionRosterRegistry.Snapshot generation
    ) {
        BondedCompanionSnapshot merged = captured == null
                ? durable : durable.mergeForStore(captured);
        Long cooldown = cooldown(request, profile, generation);
        if (cooldown == null) {
            return rejected(Status.TIME_INVALID);
        }
        return PlanningResult.planned(new StorePlan(
                profile.revision(), merged, cooldown
        ));
    }

    @Nullable
    private Long cooldown(
            PlanningRequest request,
            BondedCompanionRecord.Profile profile,
            BondedCompanionRosterRegistry.Snapshot generation
    ) {
        if (request.cause() == Cause.CONFIRMED_DEATH) {
            return 0L;
        }
        BondedCompanionRosterRegistry.RosterDefinition family =
                family(profile, generation);
        if (family == null) {
            return request.cause() == Cause.RECONCILIATION ? 0L : null;
        }
        try {
            long seconds = family.summonCooldownSeconds();
            if (seconds == 0L) return 0L;
            long result = Math.addExact(
                    request.nowMs(), Math.multiplyExact(seconds, 1_000L)
            );
            if (result == 0L) throw new ArithmeticException("zero sentinel");
            return result;
        } catch (ArithmeticException invalidTime) {
            return request.cause() == Cause.RECONCILIATION ? 0L : null;
        }
    }

    @Nullable
    private BondedCompanionRosterRegistry.RosterDefinition family(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRosterRegistry.Snapshot generation
    ) {
        return generation.resolve(profile.rosterId(), profile.familyId())
                .orElse(null);
    }

    @Nullable
    private BondedCompanionSnapshot decode(
            BondedCompanionRecord.Profile profile
    ) {
        var decoded = snapshots.decode(new String(
                profile.snapshot().bytes(), StandardCharsets.UTF_8
        ));
        return decoded.status() == BondedCompanionSnapshotCodec.Status.FOUND
                ? decoded.snapshot() : null;
    }

    private boolean matches(
            BondedCompanionRecord.Profile profile,
            BondedCompanionSnapshot snapshot
    ) {
        var state = snapshot.fullState();
        return state.owner() != null
                && profile.ownerUuid().equals(state.owner().getOwnerId())
                && profile.roleId().equals(state.roleId());
    }

    private PlanningResult rejected(Status status) {
        return PlanningResult.rejected(status);
    }
}
