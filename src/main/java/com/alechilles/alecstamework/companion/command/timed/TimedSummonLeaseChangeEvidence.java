package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Replay-complete identity, roster, lifecycle, policy, and lease evidence.
 *
 * <p>The lease retains its exact policy snapshot, session, remaining-time,
 * cooldown, and warning limits. The joined canonical facts are copied only
 * into the durable event so public delivery never reads mutable state.</p>
 */
public record TimedSummonLeaseChangeEvidence(
        @Nonnull TimedSummonLeaseChange leaseChange,
        @Nonnull CommandRosterMembership membership,
        @Nonnull String roleId,
        long profileRevision,
        @Nullable LifecycleState previousLifecycleState,
        @Nonnull LifecycleState currentLifecycleState,
        @Nullable Long previousLifecycleRevision,
        long currentLifecycleRevision,
        @Nonnull Reason reason
) {
    public TimedSummonLeaseChangeEvidence {
        if (leaseChange == null || membership == null
                || currentLifecycleState == null || reason == null
                || profileRevision < 0 || currentLifecycleRevision < 0
                || (leaseChange.before() == null)
                != (previousLifecycleState == null)
                || (previousLifecycleState == null)
                != (previousLifecycleRevision == null)
                || previousLifecycleRevision != null
                && previousLifecycleRevision < 0
                || !leaseChange.after().profileId().equals(
                membership.profileId()
        )) {
            throw new IllegalArgumentException(
                    "Complete timed summon change evidence is required"
            );
        }
        roleId = text(roleId, "Timed summon role");
    }

    @Nonnull
    public static TimedSummonLeaseChangeEvidence from(
            @Nonnull TimedSummonLeaseChange leaseChange,
            @Nonnull CommandRosterMembership membership,
            @Nonnull CompanionIdentity identity,
            @Nullable CompanionLifecycle previousLifecycle,
            @Nonnull CompanionLifecycle currentLifecycle,
            @Nonnull Reason reason
    ) {
        if (leaseChange == null || membership == null || identity == null
                || currentLifecycle == null) {
            throw new IllegalArgumentException(
                    "Timed summon semantic source facts are required"
            );
        }
        var profileId = leaseChange.after().profileId();
        if (!profileId.equals(membership.profileId())
                || !profileId.equals(identity.profileId())
                || !profileId.equals(currentLifecycle.profileId())
                || previousLifecycle != null
                && !profileId.equals(previousLifecycle.profileId())
                || !membership.familyKey().ownerId().equals(
                currentLifecycle.ownerId()
        )
                || previousLifecycle != null
                && !membership.familyKey().ownerId().equals(
                previousLifecycle.ownerId()
        )) {
            throw new IllegalArgumentException(
                    "Timed summon semantic evidence must describe one profile"
            );
        }
        return new TimedSummonLeaseChangeEvidence(
                leaseChange,
                membership,
                identity.roleId(),
                identity.metadataRevision(),
                previousLifecycle == null
                        ? null
                        : previousLifecycle.state(),
                currentLifecycle.state(),
                previousLifecycle == null
                        ? null
                        : previousLifecycle.revision().value(),
                currentLifecycle.revision().value(),
                reason
        );
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    /** Stable post-commit cause exposed to timed-summon subscribers. */
    public enum Reason {
        REGISTERED,
        POLICY_REFRESHED,
        CHECKPOINTED,
        SUMMON_STARTED,
        STORED,
        TAME_AND_LINKED,
        PROVISIONING_ACTIVATED,
        PAID_REVIVED;

        @Nonnull
        public String publicValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
