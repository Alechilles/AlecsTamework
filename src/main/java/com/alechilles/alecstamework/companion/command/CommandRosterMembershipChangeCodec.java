package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Self-contained roster membership change codec and outbox identity. */
public final class CommandRosterMembershipChangeCodec {
    /**
     * Version two is intentionally strict. Version one was never released and
     * lacked identity/lifecycle facts required for deterministic callbacks.
     */
    public static final int VERSION = 2;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("command_roster_membership_changed");

    private CommandRosterMembershipChangeCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull CommandRosterMembershipChangeEvidence evidence,
            long changedAtMs
    ) {
        if (operationId == null || evidence == null) {
            throw new IllegalArgumentException(
                    "Command roster event evidence is required"
            );
        }
        CommandRosterMutationOutcome mutation = evidence.mutation();
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                evidence.evidenceMembership().profileId().toString(),
                mutation.currentRosterRevision(),
                VERSION,
                encode(evidence),
                changedAtMs
        );
    }

    @Nonnull
    public static String encode(
            @Nonnull CommandRosterMembershipChangeEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Command roster change evidence is required"
            );
        }
        CommandRosterMutationOutcome outcome = evidence.mutation();
        JsonObject json = new JsonObject();
        json.add(
                "familyKey",
                CommandRosterMembershipJsonCodec.encodeFamily(
                        outcome.familyKey()
                )
        );
        json.addProperty(
                "previousRosterRevision",
                outcome.previousRosterRevision()
        );
        json.addProperty(
                "currentRosterRevision",
                outcome.currentRosterRevision()
        );
        json.add(
                "before",
                CommandRosterMembershipJsonCodec.encode(outcome.before())
        );
        json.add(
                "after",
                CommandRosterMembershipJsonCodec.encode(outcome.after())
        );
        json.addProperty("roleId", evidence.roleId());
        json.addProperty(
                "profileRevision", evidence.profileRevision()
        );
        json.addProperty(
                "lifecycleState", evidence.lifecycleState().name()
        );
        json.addProperty(
                "lifecycleRevision", evidence.lifecycleRevision()
        );
        json.addProperty("reason", evidence.reason().name());
        return json.toString();
    }

    @Nonnull
    public static CommandRosterMutationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        return decodeEvidence(payloadVersion, payloadJson).mutation();
    }

    @Nonnull
    public static CommandRosterMembershipChangeEvidence decodeEvidence(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported command roster change payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        CommandRosterMutationOutcome mutation =
                new CommandRosterMutationOutcome(
                CommandRosterMembershipJsonCodec.decodeFamily(
                        json.getAsJsonObject("familyKey")
                ),
                json.get("previousRosterRevision").getAsLong(),
                json.get("currentRosterRevision").getAsLong(),
                CommandRosterMembershipJsonCodec.decode(
                        json.get("before")
                ),
                CommandRosterMembershipJsonCodec.decode(
                        json.get("after")
                )
        );
        return new CommandRosterMembershipChangeEvidence(
                mutation,
                json.get("roleId").getAsString(),
                json.get("profileRevision").getAsLong(),
                com.alechilles.alecstamework.companion.lifecycle
                        .LifecycleState.valueOf(
                        json.get("lifecycleState").getAsString()
                ),
                json.get("lifecycleRevision").getAsLong(),
                CommandRosterMembershipChangeEvidence.Reason.valueOf(
                        json.get("reason").getAsString()
                )
        );
    }
}
