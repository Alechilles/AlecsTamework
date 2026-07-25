package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipJsonCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Self-contained outbox evidence for one canonical timed lease change. */
public final class TimedSummonLeaseChangeCodec {
    /**
     * Version two is intentionally strict. Unreleased version one contained
     * only lease rows and could not drive public callbacks without joins.
     */
    public static final int VERSION = 2;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("timed_summon_lease_changed");

    private TimedSummonLeaseChangeCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull TimedSummonLeaseChangeEvidence evidence
    ) {
        if (operationId == null || evidence == null) {
            throw new IllegalArgumentException(
                    "Timed summon lease event evidence is required"
            );
        }
        TimedSummonLeaseChange change = evidence.leaseChange();
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                change.after().profileId().toString(),
                change.after().leaseRevision(),
                VERSION,
                encode(evidence),
                change.after().updatedAtMs()
        );
    }

    @Nonnull
    public static String encode(
            @Nonnull TimedSummonLeaseChangeEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Timed summon change evidence is required"
            );
        }
        TimedSummonLeaseChange change = evidence.leaseChange();
        JsonObject json = new JsonObject();
        if (change.before() == null) {
            json.add("before", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.add(
                    "before",
                    TimedSummonLeaseJsonCodec.encode(change.before())
            );
        }
        json.add(
                "after",
                TimedSummonLeaseJsonCodec.encode(change.after())
        );
        json.add(
                "membership",
                CommandRosterMembershipJsonCodec.encode(
                        evidence.membership()
                )
        );
        json.addProperty("roleId", evidence.roleId());
        json.addProperty(
                "profileRevision", evidence.profileRevision()
        );
        nullable(
                json,
                "previousLifecycleState",
                evidence.previousLifecycleState()
        );
        json.addProperty(
                "currentLifecycleState",
                evidence.currentLifecycleState().name()
        );
        nullable(
                json,
                "previousLifecycleRevision",
                evidence.previousLifecycleRevision()
        );
        json.addProperty(
                "currentLifecycleRevision",
                evidence.currentLifecycleRevision()
        );
        json.addProperty("reason", evidence.reason().name());
        return json.toString();
    }

    @Nonnull
    public static TimedSummonLeaseChange decode(
            int payloadVersion,
            @Nonnull String payload
    ) {
        return decodeEvidence(payloadVersion, payload).leaseChange();
    }

    @Nonnull
    public static TimedSummonLeaseChange decode(@Nonnull String payload) {
        return decode(VERSION, payload);
    }

    @Nonnull
    public static TimedSummonLeaseChangeEvidence decodeEvidence(
            int payloadVersion,
            @Nonnull String payload
    ) {
        if (payloadVersion != VERSION || payload == null) {
            throw new IllegalArgumentException(
                    "Unsupported timed summon change payload"
            );
        }
        JsonObject json = JsonParser.parseString(payload)
                .getAsJsonObject();
        TimedSummonLeaseChange change = new TimedSummonLeaseChange(
                json.get("before").isJsonNull()
                        ? null
                        : TimedSummonLeaseJsonCodec.decode(
                                json.getAsJsonObject("before")
                        ),
                TimedSummonLeaseJsonCodec.decode(
                        json.getAsJsonObject("after")
                )
        );
        return new TimedSummonLeaseChangeEvidence(
                change,
                CommandRosterMembershipJsonCodec.decode(
                        json.get("membership")
                ),
                json.get("roleId").getAsString(),
                json.get("profileRevision").getAsLong(),
                enumOrNull(
                        json,
                        "previousLifecycleState",
                        com.alechilles.alecstamework.companion.lifecycle
                                .LifecycleState.class
                ),
                com.alechilles.alecstamework.companion.lifecycle
                        .LifecycleState.valueOf(
                        json.get("currentLifecycleState").getAsString()
                ),
                longOrNull(json, "previousLifecycleRevision"),
                json.get("currentLifecycleRevision").getAsLong(),
                TimedSummonLeaseChangeEvidence.Reason.valueOf(
                        json.get("reason").getAsString()
                )
        );
    }

    private static void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, com.google.gson.JsonNull.INSTANCE);
        } else if (value instanceof Number number) {
            json.addProperty(name, number);
        } else if (value instanceof Enum<?> enumeration) {
            json.addProperty(name, enumeration.name());
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private static Long longOrNull(JsonObject json, String name) {
        var value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsLong();
    }

    private static <T extends Enum<T>> T enumOrNull(
            JsonObject json,
            String name,
            Class<T> type
    ) {
        var value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : Enum.valueOf(type, value.getAsString());
    }
}
