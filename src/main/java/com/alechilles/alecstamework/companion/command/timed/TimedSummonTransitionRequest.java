package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import java.util.Set;
import javax.annotation.Nonnull;

/** Exact summon/store evidence for the sole timed external transition. */
public record TimedSummonTransitionRequest(
        @Nonnull Action action,
        @Nonnull CommandFamilyKey familyKey,
        @Nonnull CommandRosterSlotId slotId,
        long expectedMembershipRevision,
        @Nonnull TimedSummonLease beforeLease,
        @Nonnull TimedSummonLease afterLease,
        @Nonnull PopulationGroupTransitionAdmissionRequest groupAdmission,
        @Nonnull NpcAlias liveAlias,
        @Nonnull String worldKey,
        @Nonnull CompanionSnapshot snapshot,
        @Nonnull String receiptKey,
        long requestedAtMs
) {
    public static final SnapshotKind SNAPSHOT_KIND =
            new SnapshotKind("timed_summon");

    public TimedSummonTransitionRequest {
        if (action == null || familyKey == null || slotId == null
                || expectedMembershipRevision <= 0
                || beforeLease == null || afterLease == null
                || groupAdmission == null || liveAlias == null
                || snapshot == null) {
            throw new IllegalArgumentException(
                    "Complete timed summon transition is required"
            );
        }
        worldKey = text(worldKey, "Timed summon world");
        receiptKey = text(receiptKey, "Timed summon receipt");
        new TimedSummonLeaseChange(beforeLease, afterLease);
        CompanionLifecycle before = groupAdmission.before();
        CompanionLifecycle after = groupAdmission.after();
        if (!beforeLease.profileId().equals(afterLease.profileId())
                || !beforeLease.profileId().equals(before.profileId())
                || !before.profileId().equals(after.profileId())
                || !familyKey.ownerId().equals(before.ownerId())
                || !familyKey.ownerId().equals(after.ownerId())
                || requestedAtMs != groupAdmission.requestedAtMs()
                || requestedAtMs != after.stateChangedAtMs()
                || requestedAtMs != afterLease.updatedAtMs()
                || !snapshot.profileId().equals(before.profileId())
                || !SNAPSHOT_KIND.equals(snapshot.kind())
                || !snapshot.current()
                || !allowed(
                action,
                before,
                after,
                slotId,
                beforeLease,
                afterLease,
                liveAlias,
                worldKey,
                snapshot,
                requestedAtMs
        )) {
            throw new IllegalArgumentException(
                    "Timed transition evidence is inconsistent"
            );
        }
    }

    public boolean starting() {
        return action == Action.START;
    }

    /** Returns the canonical post-fence lifecycle committed by this operation. */
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

    private static boolean allowed(
            Action action,
            CompanionLifecycle before,
            CompanionLifecycle after,
            CommandRosterSlotId slotId,
            TimedSummonLease beforeLease,
            TimedSummonLease afterLease,
            NpcAlias liveAlias,
            String worldKey,
            CompanionSnapshot snapshot,
            long requestedAtMs
    ) {
        return switch (action) {
            case START -> validStart(
                    before,
                    after,
                    slotId,
                    beforeLease,
                    afterLease,
                    liveAlias,
                    worldKey,
                    snapshot,
                    requestedAtMs
            );
            case STORE -> validStore(
                    before,
                    after,
                    slotId,
                    beforeLease,
                    afterLease,
                    liveAlias,
                    worldKey,
                    snapshot,
                    requestedAtMs
            );
        };
    }

    private static boolean validStart(
            CompanionLifecycle before,
            CompanionLifecycle after,
            CommandRosterSlotId slotId,
            TimedSummonLease beforeLease,
            TimedSummonLease afterLease,
            NpcAlias liveAlias,
            String worldKey,
            CompanionSnapshot snapshot,
            long requestedAtMs
    ) {
        Long initialRemaining = beforeLease.policy().unlimited()
                ? null
                : beforeLease.policy().activeDurationMs();
        return before.state() == LifecycleState.ROSTER_STORED
                && storedSlot(before, slotId)
                && after.state() == LifecycleState.ACTIVE
                && after.location().kind()
                == LifecycleLocationKind.LIVE_ENTITY
                && liveAlias.toString().equals(after.location().key())
                && worldKey.equals(after.location().worldKey())
                && !beforeLease.activeSession()
                && !beforeLease.cooldownActive(requestedAtMs)
                && afterLease.activeSession()
                && beforeLease.policy().equals(afterLease.policy())
                && java.util.Objects.equals(
                initialRemaining, afterLease.remainingMs()
        )
                && afterLease.cooldownUntilMs() == null
                && afterLease.checkpointedAtMs() == requestedAtMs
                && afterLease.emittedWarningThresholdsMs().isEmpty()
                && snapshot.sourceLifecycleRevision().compareTo(
                before.revision()
        ) <= 0;
    }

    private static boolean validStore(
            CompanionLifecycle before,
            CompanionLifecycle after,
            CommandRosterSlotId slotId,
            TimedSummonLease beforeLease,
            TimedSummonLease afterLease,
            NpcAlias liveAlias,
            String worldKey,
            CompanionSnapshot snapshot,
            long requestedAtMs
    ) {
        long cooldownUntil = TimedSummonTime.saturatingAdd(
                requestedAtMs,
                beforeLease.policy().resummonCooldownMs()
        );
        boolean source = before.state() == LifecycleState.UNLOADED
                || before.state() == LifecycleState.ACTIVE
                && before.location().kind()
                == LifecycleLocationKind.LIVE_ENTITY
                && liveAlias.toString().equals(before.location().key())
                && worldKey.equals(before.location().worldKey());
        return source
                && after.state() == LifecycleState.ROSTER_STORED
                && storedSlot(after, slotId)
                && beforeLease.activeSession()
                && !afterLease.activeSession()
                && beforeLease.policy().equals(afterLease.policy())
                && afterLease.cooldownUntilMs() == cooldownUntil
                && afterLease.remainingMs() == null
                && afterLease.checkpointedAtMs() == null
                && afterLease.emittedWarningThresholdsMs()
                .equals(Set.of())
                && snapshot.sourceLifecycleRevision().equals(
                before.revision().next()
        );
    }

    private static boolean storedSlot(
            CompanionLifecycle lifecycle,
            CommandRosterSlotId slotId
    ) {
        return lifecycle.location().kind()
                == LifecycleLocationKind.COMMAND_ROSTER
                && slotId.toString().equals(lifecycle.location().key());
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    public enum Action {
        START,
        STORE
    }
}
