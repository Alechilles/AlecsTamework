package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import javax.annotation.Nonnull;

/** Strict versioned outbox codec for public provisioned lifecycle semantics. */
public final class ProvisionedCompanionLifecycleEventCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType DEATH_EVENT_TYPE =
            new ProjectionEventType("provisioned_companion_death_recorded");
    public static final ProjectionEventType REVIVED_EVENT_TYPE =
            new ProjectionEventType("provisioned_companion_revived");

    private ProvisionedCompanionLifecycleEventCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft deathDraft(
            @Nonnull OperationId operationId,
            @Nonnull ProvisionedCompanionDeathOutcome outcome
    ) {
        require(operationId, "operationId");
        require(outcome, "outcome");
        return new ProjectionEventDraft(
                operationId,
                DEATH_EVENT_TYPE,
                deathAggregate(outcome.profileId()),
                outcome.newLifecycleRevision().value(),
                VERSION,
                encodeDeath(outcome),
                outcome.diedAtMs()
        );
    }

    @Nonnull
    public static ProjectionEventDraft revivalDraft(
            @Nonnull OperationId operationId,
            @Nonnull ProvisionedCompanionRevivalOutcome outcome
    ) {
        require(operationId, "operationId");
        require(outcome, "outcome");
        return new ProjectionEventDraft(
                operationId,
                REVIVED_EVENT_TYPE,
                revivalAggregate(outcome.profileId()),
                outcome.newLifecycleRevision().value(),
                VERSION,
                encodeRevival(outcome),
                outcome.revivedAtMs()
        );
    }

    @Nonnull
    public static String encodeDeath(
            @Nonnull ProvisionedCompanionDeathOutcome outcome
    ) {
        require(outcome, "outcome");
        JsonObject json = common(
                outcome.origin(),
                outcome.profileId(),
                outcome.ownerId(),
                outcome.roleId(),
                outcome.lifecycle(),
                outcome.projectionStatus(),
                outcome.oldLifecycleRevision(),
                outcome.newLifecycleRevision()
        );
        json.addProperty("lastAlias", outcome.lastAlias().toString());
        json.addProperty("diedAtMs", outcome.diedAtMs());
        return json.toString();
    }

    @Nonnull
    public static String encodeRevival(
            @Nonnull ProvisionedCompanionRevivalOutcome outcome
    ) {
        require(outcome, "outcome");
        JsonObject json = common(
                outcome.origin(),
                outcome.profileId(),
                outcome.ownerId(),
                outcome.roleId(),
                outcome.lifecycle(),
                outcome.projectionStatus(),
                outcome.oldLifecycleRevision(),
                outcome.newLifecycleRevision()
        );
        if (outcome.newAlias() == null) {
            json.add("newAlias", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty("newAlias", outcome.newAlias().toString());
        }
        json.addProperty("revivedAtMs", outcome.revivedAtMs());
        return json.toString();
    }

    @Nonnull
    public static ProvisionedCompanionDeathOutcome decodeDeath(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        JsonObject json = parse(payloadVersion, payloadJson);
        requireFields(json, "lastAlias", "diedAtMs");
        Common common = common(json);
        return new ProvisionedCompanionDeathOutcome(
                common.origin(),
                common.profileId(),
                common.ownerId(),
                common.roleId(),
                NpcAlias.parse(text(json, "lastAlias")),
                common.lifecycle(),
                common.projectionStatus(),
                common.oldRevision(),
                common.newRevision(),
                number(json, "diedAtMs")
        );
    }

    @Nonnull
    public static ProvisionedCompanionRevivalOutcome decodeRevival(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        JsonObject json = parse(payloadVersion, payloadJson);
        requireFields(json, "newAlias", "revivedAtMs");
        Common common = common(json);
        return new ProvisionedCompanionRevivalOutcome(
                common.origin(),
                common.profileId(),
                common.ownerId(),
                common.roleId(),
                nullableAlias(json, "newAlias"),
                common.lifecycle(),
                common.projectionStatus(),
                common.oldRevision(),
                common.newRevision(),
                number(json, "revivedAtMs")
        );
    }

    @Nonnull
    public static String deathAggregate(@Nonnull ProfileId profileId) {
        return "provisioned-death:" + require(profileId, "profileId");
    }

    @Nonnull
    public static String revivalAggregate(@Nonnull ProfileId profileId) {
        return "provisioned-revival:" + require(profileId, "profileId");
    }

    private static JsonObject common(
            ProvisioningOrigin origin,
            ProfileId profileId,
            OwnerId ownerId,
            String roleId,
            LifecycleState lifecycle,
            CompanionProvisioningProjectionStatus projectionStatus,
            LifecycleRevision oldRevision,
            LifecycleRevision newRevision
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("callerNamespace", origin.callerNamespace());
        json.addProperty("callerKey", origin.callerKey());
        json.addProperty("profileId", profileId.toString());
        json.addProperty("ownerUuid", ownerId.toString());
        json.addProperty("roleId", roleId);
        json.addProperty("lifecycle", lifecycle.name());
        json.addProperty("projectionStatus", projectionStatus.name());
        json.addProperty("oldLifecycleRevision", oldRevision.value());
        json.addProperty("newLifecycleRevision", newRevision.value());
        return json;
    }

    private static Common common(JsonObject json) {
        return new Common(
                new ProvisioningOrigin(
                        text(json, "callerNamespace"),
                        text(json, "callerKey")
                ),
                ProfileId.parse(text(json, "profileId")),
                OwnerId.parse(text(json, "ownerUuid")),
                text(json, "roleId"),
                LifecycleState.valueOf(text(json, "lifecycle")),
                CompanionProvisioningProjectionStatus.valueOf(
                        text(json, "projectionStatus")
                ),
                new LifecycleRevision(
                        number(json, "oldLifecycleRevision")
                ),
                new LifecycleRevision(
                        number(json, "newLifecycleRevision")
                )
        );
    }

    private static JsonObject parse(int version, String payloadJson) {
        if (version != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported provisioned lifecycle payload"
            );
        }
        try {
            return JsonParser.parseString(payloadJson).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Invalid provisioned lifecycle payload", failure
            );
        }
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid provisioned lifecycle field " + field
            );
        }
        return value.getAsString().trim();
    }

    private static long number(JsonObject json, String field) {
        JsonElement value = json.get(field);
        try {
            if (value == null || value.isJsonNull()
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException();
            }
            String encoded = value.getAsString();
            if (!encoded.matches("-?(0|[1-9][0-9]*)")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(encoded);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Invalid provisioned lifecycle field " + field,
                    failure
            );
        }
    }

    private static NpcAlias nullableAlias(
            JsonObject json,
            String field
    ) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull()
                ? null
                : NpcAlias.parse(text(json, field));
    }

    private static void requireFields(
            JsonObject json,
            String aliasField,
            String timeField
    ) {
        Set<String> expected = Set.of(
                "callerNamespace",
                "callerKey",
                "profileId",
                "ownerUuid",
                "roleId",
                "lifecycle",
                "projectionStatus",
                "oldLifecycleRevision",
                "newLifecycleRevision",
                aliasField,
                timeField
        );
        if (!json.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "Unexpected provisioned lifecycle payload fields"
            );
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private record Common(
            ProvisioningOrigin origin,
            ProfileId profileId,
            OwnerId ownerId,
            String roleId,
            LifecycleState lifecycle,
            CompanionProvisioningProjectionStatus projectionStatus,
            LifecycleRevision oldRevision,
            LifecycleRevision newRevision
    ) {
    }
}
