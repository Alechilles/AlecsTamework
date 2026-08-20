package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact slot, lifecycle, and group evidence for one command storage/live transition. */
public record CommandRosterTransitionRequest(
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedMembershipRevision,
        @Nonnull PopulationGroupTransitionAdmissionRequest groupAdmission,
        @Nullable LifecycleAdmissionEvidence admissionEvidence
) {
    public CommandRosterTransitionRequest(
            @Nonnull CommandFamilyKey familyKey,
            @Nonnull CommandRosterSlotId slotId,
            long expectedMembershipRevision,
            @Nonnull PopulationGroupTransitionAdmissionRequest groupAdmission
    ) {
        this(familyKey, slotId, expectedMembershipRevision, groupAdmission, null);
    }

    public CommandRosterTransitionRequest {
        if (familyKey == null || slotId == null
                || expectedMembershipRevision <= 0
                || groupAdmission == null
                || !familyKey.ownerId().equals(
                groupAdmission.before().ownerId()
        )
                || !familyKey.ownerId().equals(
                groupAdmission.after().ownerId()
        )
                || !Objects.equals(
                groupAdmission.before().ownerWorldKey(),
                groupAdmission.after().ownerWorldKey()
        )
                || !groupAdmission.before()
                .lastReconciledGeneration().equals(
                        groupAdmission.after()
                                .lastReconciledGeneration()
                )
                || groupAdmission.after().stateChangedAtMs()
                != groupAdmission.requestedAtMs()
                || !allowed(
                groupAdmission.before(), groupAdmission.after(), slotId
        )) {
            throw new IllegalArgumentException(
                    "Valid command roster lifecycle transition is required"
            );
        }
    }

    @Nonnull
    public CommandRosterTransitionRequest withAdmissionEvidence(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence is required"
            );
        }
        if (admissionEvidence != null
                && !admissionEvidence.equals(evidence)) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence cannot be replaced"
            );
        }
        return new CommandRosterTransitionRequest(
                familyKey,
                slotId,
                expectedMembershipRevision,
                groupAdmission,
                evidence
        );
    }

    private static boolean allowed(
            CompanionLifecycle before,
            CompanionLifecycle after,
            CommandRosterSlotId slotId
    ) {
        if (before.activeOperationId() != null
                || after.activeOperationId() != null
                || before.quarantined() || after.quarantined()) {
            return false;
        }
        if (before.state() == LifecycleState.ROSTER_STORED
                && after.state() == LifecycleState.ACTIVE) {
            return slotMatches(before, slotId)
                    && after.location().kind()
                    == LifecycleLocationKind.LIVE_ENTITY;
        }
        return (before.state() == LifecycleState.ACTIVE
                || before.state() == LifecycleState.UNLOADED)
                && after.state() == LifecycleState.ROSTER_STORED
                && slotMatches(after, slotId);
    }

    private static boolean slotMatches(
            CompanionLifecycle lifecycle,
            CommandRosterSlotId slotId
    ) {
        return lifecycle.location().kind()
                == LifecycleLocationKind.COMMAND_ROSTER
                && slotId.toString().equals(lifecycle.location().key());
    }
}

