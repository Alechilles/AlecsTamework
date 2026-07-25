package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import javax.annotation.Nonnull;

/**
 * Replay-complete roster change facts for public callbacks and projections.
 *
 * <p>Identity and lifecycle facts are copied into this immutable event
 * evidence at the same durable commit as the roster mutation. They are not an
 * alternate authority and prevent outbox delivery from depending on read
 * order or later canonical state.</p>
 */
public record CommandRosterMembershipChangeEvidence(
        @Nonnull CommandRosterMutationOutcome mutation,
        @Nonnull String roleId,
        long profileRevision,
        @Nonnull LifecycleState lifecycleState,
        long lifecycleRevision,
        @Nonnull Reason reason
) {
    public CommandRosterMembershipChangeEvidence {
        if (mutation == null || lifecycleState == null || reason == null
                || profileRevision < 0 || lifecycleRevision < 0) {
            throw new IllegalArgumentException(
                    "Complete command roster change evidence is required"
            );
        }
        roleId = text(roleId, "Command roster role");
    }

    @Nonnull
    public static CommandRosterMembershipChangeEvidence from(
            @Nonnull CommandRosterMutationOutcome mutation,
            @Nonnull CompanionIdentity identity,
            @Nonnull CompanionLifecycle lifecycle,
            @Nonnull Reason reason
    ) {
        if (mutation == null || identity == null || lifecycle == null) {
            throw new IllegalArgumentException(
                    "Roster mutation, identity, and lifecycle are required"
            );
        }
        CommandRosterMembership membership = mutation.after() == null
                ? mutation.before()
                : mutation.after();
        if (!identity.profileId().equals(membership.profileId())
                || !lifecycle.profileId().equals(membership.profileId())
                || !mutation.familyKey().ownerId().equals(
                lifecycle.ownerId()
        )) {
            throw new IllegalArgumentException(
                    "Roster semantic evidence must describe one profile"
            );
        }
        return new CommandRosterMembershipChangeEvidence(
                mutation,
                identity.roleId(),
                identity.metadataRevision(),
                lifecycle.state(),
                lifecycle.revision().value(),
                reason
        );
    }

    @Nonnull
    public CommandRosterMembership evidenceMembership() {
        return mutation.after() == null
                ? mutation.before()
                : mutation.after();
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    /** Stable semantic cause captured at the durable authoring boundary. */
    public enum Reason {
        UPSERTED,
        REMOVED,
        TAME_AND_LINKED,
        PROVISIONED
    }
}
