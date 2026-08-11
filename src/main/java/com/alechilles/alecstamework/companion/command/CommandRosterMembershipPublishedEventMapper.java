package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipChangedEvent;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps replay-complete roster outbox evidence to the public API without joins. */
public final class CommandRosterMembershipPublishedEventMapper {
    private CommandRosterMembershipPublishedEventMapper() {
    }

    @Nonnull
    public static CommandFamilyRosterMembershipChangedEvent map(
            @Nonnull ProjectionEvent event,
            long emittedAtMs
    ) {
        if (event == null || !CommandRosterMembershipChangeCodec.EVENT_TYPE
                .equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "Command roster projection event is required"
            );
        }
        CommandRosterMembershipChangeEvidence evidence =
                CommandRosterMembershipChangeCodec.decodeEvidence(
                        event.payloadVersion(), event.payloadJson()
                );
        requireMatchingEnvelope(event, evidence);
        CommandRosterMutationOutcome mutation = evidence.mutation();
        return new CommandFamilyRosterMembershipChangedEvent(
                event.operationId().value(),
                mutation.familyKey().ownerId().value(),
                mutation.familyKey().familyId(),
                evidence.evidenceMembership().profileId().toString(),
                view(mutation.before(), evidence),
                view(mutation.after(), evidence),
                mutation.previousRosterRevision(),
                mutation.currentRosterRevision(),
                event.createdAtMs(),
                emittedAtMs
        );
    }

    private static void requireMatchingEnvelope(
            ProjectionEvent event,
            CommandRosterMembershipChangeEvidence evidence
    ) {
        CommandRosterMutationOutcome mutation = evidence.mutation();
        if (!event.aggregateId().equals(
                evidence.evidenceMembership().profileId().toString()
        )
                || event.aggregateRevision()
                != mutation.currentRosterRevision()) {
            throw new IllegalArgumentException(
                    "Command roster projection envelope does not match payload"
            );
        }
    }

    @Nullable
    private static CommandFamilyRosterMembershipView view(
            @Nullable CommandRosterMembership membership,
            CommandRosterMembershipChangeEvidence evidence
    ) {
        if (membership == null) {
            return null;
        }
        CommandRosterHome home = membership.home();
        return new CommandFamilyRosterMembershipView(
                membership.familyKey().ownerId().value(),
                membership.familyKey().familyId(),
                membership.profileId().toString(),
                evidence.roleId(),
                evidence.profileRevision(),
                state(evidence.lifecycleState()),
                membership.groupId(),
                membership.activeForBulkCommands(),
                home == null
                        ? null
                        : new Vector3View(home.x(), home.y(), home.z()),
                membership.updatedAtMs()
        );
    }

    private static CommandFamilyRosterMemberState state(
            LifecycleState state
    ) {
        return switch (state) {
            case ACTIVE -> CommandFamilyRosterMemberState.ACTIVE;
            case UNLOADED, CAPTURED, COOP, RELEASED ->
                    CommandFamilyRosterMemberState.UNLOADED;
            case UNRESOLVED -> CommandFamilyRosterMemberState.UNAVAILABLE;
            case ROSTER_STORED, PROVISIONED_DORMANT ->
                    CommandFamilyRosterMemberState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandFamilyRosterMemberState.DEAD_REVIVABLE;
            case LOST -> CommandFamilyRosterMemberState.LOST;
        };
    }
}
