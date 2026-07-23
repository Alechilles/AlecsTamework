package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen authority for one exact charged revival of a revivable companion. */
public record PaidRevivalRequest(
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedMembershipRevision,
        long expectedProfileRevision,
        @Nonnull PopulationGroupTransitionAdmissionRequest groupAdmission,
        @Nonnull CompanionSnapshot sourceSnapshot,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String targetWorldKey,
        @Nonnull String placementFingerprint,
        @Nullable String configId,
        @Nonnull String configRevision,
        @Nonnull List<RevivalCostItem> exactCost,
        @Nonnull List<RevivalInventoryReservation> reservations,
        @Nonnull String chargeReceiptKey,
        @Nonnull String spawnReceiptKey,
        @Nullable TimedSummonActivation timedActivation,
        long requestedAtMs
) {
    public PaidRevivalRequest {
        if (familyKey == null || slotId == null
                || expectedMembershipRevision <= 0
                || expectedProfileRevision < 0
                || groupAdmission == null || sourceSnapshot == null
                || targetAlias == null) {
            throw new IllegalArgumentException(
                    "Complete paid revival evidence is required"
            );
        }
        targetWorldKey = text(targetWorldKey, "Paid revival world");
        placementFingerprint = text(
                placementFingerprint, "Paid revival placement"
        );
        configId = normalize(configId);
        configRevision = text(
                configRevision, "Paid revival config revision"
        );
        chargeReceiptKey = text(
                chargeReceiptKey, "Paid revival charge receipt"
        );
        spawnReceiptKey = text(
                spawnReceiptKey, "Paid revival spawn receipt"
        );
        exactCost = validatedCost(exactCost);
        reservations = validatedReservations(exactCost, reservations);
        requireLifecycleAndSnapshot(
                familyKey,
                groupAdmission,
                sourceSnapshot,
                targetAlias,
                targetWorldKey,
                requestedAtMs
        );
        requireTimed(
                timedActivation,
                familyKey,
                slotId,
                expectedMembershipRevision,
                groupAdmission.before(),
                requestedAtMs
        );
    }

    /** Returns the post-fence active lifecycle committed after both receipts resolve. */
    @Nonnull
    public CompanionLifecycle finalLifecycle() {
        CompanionLifecycle target = groupAdmission.after();
        return new CompanionLifecycle(
                target.profileId(),
                target.ownerId(),
                target.state(),
                target.location(),
                groupAdmission.before().revision().next().next(),
                null,
                requestedAtMs,
                target.lastReconciledGeneration(),
                target.quarantineIncidentId(),
                target.ownerWorldKey()
        );
    }

    private static List<RevivalCostItem> validatedCost(
            List<RevivalCostItem> exactCost
    ) {
        if (exactCost == null) {
            throw new IllegalArgumentException(
                    "Paid revival cost is required"
            );
        }
        List<RevivalCostItem> frozen = List.copyOf(exactCost);
        HashSet<String> itemIds = new HashSet<>();
        for (RevivalCostItem item : frozen) {
            if (item == null || !itemIds.add(item.itemId())) {
                throw new IllegalArgumentException(
                        "Paid revival cost item IDs must be unique"
                );
            }
        }
        return frozen;
    }

    private static List<RevivalInventoryReservation> validatedReservations(
            List<RevivalCostItem> exactCost,
            List<RevivalInventoryReservation> reservations
    ) {
        if (reservations == null) {
            throw new IllegalArgumentException(
                    "Paid revival reservations are required"
            );
        }
        TreeSet<RevivalInventoryReservation> sorted =
                new TreeSet<>(reservations);
        if (sorted.size() != reservations.size()) {
            throw new IllegalArgumentException(
                    "Paid revival reservation ordinals must be unique"
            );
        }
        int[] totals = new int[exactCost.size()];
        int[] nextStack = new int[exactCost.size()];
        HashSet<String> sourceSlots = new HashSet<>();
        for (RevivalInventoryReservation reservation : sorted) {
            if (reservation == null
                    || reservation.costOrdinal() >= exactCost.size()
                    || reservation.stackOrdinal()
                    != nextStack[reservation.costOrdinal()]++
                    || !sourceSlots.add(
                    reservation.compartmentId() + "\u0000"
                            + reservation.slotIndex()
            )) {
                throw new IllegalArgumentException(
                        "Paid revival reservation plan is inconsistent"
                );
            }
            totals[reservation.costOrdinal()] = Math.addExact(
                    totals[reservation.costOrdinal()],
                    reservation.quantity()
            );
        }
        for (int ordinal = 0; ordinal < exactCost.size(); ordinal++) {
            if (totals[ordinal] != exactCost.get(ordinal).quantity()) {
                throw new IllegalArgumentException(
                        "Paid revival reservations must cover the exact cost"
                );
            }
        }
        return List.copyOf(sorted);
    }

    private static void requireLifecycleAndSnapshot(
            CommandFamilyKey familyKey,
            PopulationGroupTransitionAdmissionRequest admission,
            CompanionSnapshot snapshot,
            NpcAlias targetAlias,
            String worldKey,
            long requestedAtMs
    ) {
        CompanionLifecycle before = admission.before();
        CompanionLifecycle after = admission.after();
        if (before.state() != LifecycleState.DEAD_REVIVABLE
                || !before.location().equals(LifecycleLocation.none())
                || before.ownerId() == null
                || !familyKey.ownerId().equals(before.ownerId())
                || after.state() != LifecycleState.ACTIVE
                || !after.location().equals(LifecycleLocation.liveEntity(
                targetAlias.toString(), worldKey
        ))
                || !before.ownerId().equals(after.ownerId())
                || !worldKey.equals(after.ownerWorldKey())
                || requestedAtMs != admission.requestedAtMs()
                || requestedAtMs != after.stateChangedAtMs()
                || !snapshot.profileId().equals(before.profileId())
                || !snapshot.current()
                || !snapshot.kind().equals(
                DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind()
        )
                || snapshot.sourceLifecycleRevision()
                .compareTo(before.revision()) > 0) {
            throw new IllegalArgumentException(
                    "Paid revival source and target are inconsistent"
            );
        }
    }

    private static void requireTimed(
            TimedSummonActivation timed,
            CommandFamilyKey familyKey,
            CommandRosterSlotId slotId,
            long membershipRevision,
            CompanionLifecycle source,
            long requestedAtMs
    ) {
        if (timed == null) {
            return;
        }
        TimedSummonLease lease = timed.lease();
        Long expectedRemaining = lease.policy().unlimited()
                ? null
                : lease.policy().activeDurationMs();
        if (!timed.familyKey().equals(familyKey)
                || !timed.slotId().equals(slotId)
                || timed.expectedMembershipRevision()
                != membershipRevision
                || !lease.profileId().equals(source.profileId())
                || !lease.activeSession()
                || !java.util.Objects.equals(
                expectedRemaining, lease.remainingMs()
        )
                || lease.cooldownUntilMs() != null
                || !lease.emittedWarningThresholdsMs().equals(Set.of())
                || !java.util.Objects.equals(
                lease.checkpointedAtMs(), requestedAtMs
        )
                || lease.updatedAtMs() != requestedAtMs) {
            throw new IllegalArgumentException(
                    "Paid revival timed activation must start one full session"
            );
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
