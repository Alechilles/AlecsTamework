package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** JSON codec for optional frozen lifecycle admission evidence. */
public final class LifecycleAdmissionEvidenceJsonCodec {
    private LifecycleAdmissionEvidenceJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("status", evidence.status().name());
        if (evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return json;
        }
        json.add(
                "payload",
                JsonParser.parseString(
                        PopulationDomainAdmissionDefinition.INSTANCE.encode(
                                evidence.payload()
                        )
                ).getAsJsonObject()
        );
        PopulationAdmissionComposition composition = evidence.composition();
        if (composition != null) {
            JsonObject compositionJson = new JsonObject();
            if (composition.ownerPlan() != null) {
                compositionJson.add(
                        "ownerPlan",
                        OwnerPopulationAdmissionPlanJsonCodec.encode(
                                composition.ownerPlan()
                        )
                );
            }
            if (composition.groupRequest() != null) {
                compositionJson.add(
                        "groupRequest",
                        PopulationGroupTransitionAdmissionJsonCodec.encode(
                                composition.groupRequest()
                        )
                );
            }
            json.add("composition", compositionJson);
        }
        if (evidence.convergencePlan() != null) {
            json.add("convergencePlan", encodePlan(evidence.convergencePlan()));
        }
        return json;
    }

    @Nonnull
    public static LifecycleAdmissionEvidence decode(
            @Nonnull JsonObject json
    ) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence JSON is required"
            );
        }
        LifecycleAdmissionEvidence.Status status =
                LifecycleAdmissionEvidence.Status.valueOf(
                        json.get("status").getAsString()
                );
        if (status == LifecycleAdmissionEvidence.Status.UNMANAGED) {
            return LifecycleAdmissionEvidence.unmanaged();
        }
        if (status == LifecycleAdmissionEvidence.Status.NEUTRAL) {
            return LifecycleAdmissionEvidence.neutral();
        }
        JsonObject payloadJson = json.getAsJsonObject("payload");
        if (payloadJson == null) {
            throw new IllegalArgumentException(
                    "Managed lifecycle admission payload is required"
            );
        }
        PopulationAdmissionComposition composition = null;
        JsonObject compositionJson = json.has("composition")
                && !json.get("composition").isJsonNull()
                ? json.getAsJsonObject("composition")
                : null;
        if (compositionJson != null) {
            composition = new PopulationAdmissionComposition(
                    optionalOwnerPlan(compositionJson),
                    optionalGroupRequest(compositionJson)
            );
        }
        PopulationDomainConvergencePlan convergencePlan = json.has("convergencePlan")
                && !json.get("convergencePlan").isJsonNull()
                ? decodePlan(json.getAsJsonObject("convergencePlan"))
                : null;
        return LifecycleAdmissionEvidence.managed(
                PopulationDomainAdmissionDefinition.INSTANCE.decode(
                        payloadJson.toString()
                ),
                composition,
                convergencePlan
        );
    }

    private static JsonObject encodePlan(PopulationDomainConvergencePlan plan) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", plan.profileId().toString());
        json.addProperty("sourceLifecycleRevision", plan.sourceLifecycleRevision().value());
        nullable(json, "sourceOwner", plan.sourceOwner());
        nullable(json, "sourceWorldKey", plan.sourceWorldKey());
        json.addProperty("sourceState", plan.sourceState().name());
        nullable(json, "targetOwner", plan.targetOwner());
        nullable(json, "targetWorldKey", plan.targetWorldKey());
        json.addProperty("targetState", plan.targetState().name());
        JsonArray sourceRows = new JsonArray();
        for (PopulationDomainConvergencePlan.SourceRow row : plan.sourceRows()) {
            JsonObject encoded = new JsonObject();
            encoded.add("expected", encodeReservation(row.expected()));
            encoded.addProperty("residualOwnedDelta", row.residualOwnedDelta());
            encoded.addProperty("residualDeployableDelta", row.residualDeployableDelta());
            sourceRows.add(encoded);
        }
        json.add("sourceRows", sourceRows);
        JsonArray targets = new JsonArray();
        for (PopulationDomainReservation reservation : plan.targetReservations()) {
            targets.add(encodeReservation(reservation));
        }
        json.add("targetReservations", targets);
        return json;
    }

    private static PopulationDomainConvergencePlan decodePlan(JsonObject json) {
        ArrayList<PopulationDomainConvergencePlan.SourceRow> sourceRows = new ArrayList<>();
        JsonArray sourceArray = json.getAsJsonArray("sourceRows");
        if (sourceArray != null) {
            for (JsonElement value : sourceArray) {
                JsonObject row = value.getAsJsonObject();
                sourceRows.add(new PopulationDomainConvergencePlan.SourceRow(
                        decodeReservation(row.getAsJsonObject("expected")),
                        row.get("residualOwnedDelta").getAsInt(),
                        row.get("residualDeployableDelta").getAsInt()
                ));
            }
        }
        ArrayList<PopulationDomainReservation> targets = new ArrayList<>();
        JsonArray targetArray = json.getAsJsonArray("targetReservations");
        if (targetArray != null) {
            for (JsonElement value : targetArray) {
                targets.add(decodeReservation(value.getAsJsonObject()));
            }
        }
        return new PopulationDomainConvergencePlan(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(json.get("sourceLifecycleRevision").getAsLong()),
                owner(json, "sourceOwner"),
                text(json, "sourceWorldKey"),
                LifecycleState.valueOf(json.get("sourceState").getAsString()),
                owner(json, "targetOwner"),
                text(json, "targetWorldKey"),
                LifecycleState.valueOf(json.get("targetState").getAsString()),
                sourceRows,
                targets
        );
    }

    private static JsonObject encodeReservation(PopulationDomainReservation reservation) {
        JsonObject json = new JsonObject();
        json.addProperty("operationId", reservation.operationId().toString());
        json.addProperty("profileId", reservation.profileId().toString());
        if (reservation.expectedLifecycleRevision() == null) {
            json.add("expectedLifecycleRevision", null);
        } else {
            json.addProperty("expectedLifecycleRevision", reservation.expectedLifecycleRevision().value());
        }
        PopulationDomainBucket bucket = reservation.bucket();
        json.addProperty("ownerId", bucket.ownerId().toString());
        json.addProperty("domainId", bucket.domainId());
        json.addProperty("scope", bucket.scope().name());
        nullable(json, "ownerWorldKey", bucket.ownerWorldKey());
        json.addProperty("ownedDelta", reservation.ownedDelta());
        json.addProperty("deployableDelta", reservation.deployableDelta());
        json.addProperty("weight", reservation.weight());
        json.addProperty("snapshottedMaxOwned", reservation.snapshottedMaxOwned());
        json.addProperty("snapshottedMaxDeployable", reservation.snapshottedMaxDeployable());
        json.addProperty("providerSnapshotRevision", reservation.providerSnapshotRevision());
        json.addProperty("managedConfigRevision", reservation.managedConfigRevision());
        json.addProperty("policyRevision", reservation.policyRevision());
        json.addProperty("createdAtMs", reservation.createdAtMs());
        return json;
    }

    private static PopulationDomainReservation decodeReservation(JsonObject json) {
        JsonElement revision = json.get("expectedLifecycleRevision");
        return new PopulationDomainReservation(
                OperationId.parse(json.get("operationId").getAsString()),
                ProfileId.parse(json.get("profileId").getAsString()),
                revision == null || revision.isJsonNull()
                        ? null : new LifecycleRevision(revision.getAsLong()),
                new PopulationDomainBucket(
                        OwnerId.parse(json.get("ownerId").getAsString()),
                        json.get("domainId").getAsString(),
                        PopulationDomainScope.valueOf(json.get("scope").getAsString()),
                        text(json, "ownerWorldKey")
                ),
                json.get("ownedDelta").getAsInt(),
                json.get("deployableDelta").getAsInt(),
                json.get("weight").getAsInt(),
                json.get("snapshottedMaxOwned").getAsInt(),
                json.get("snapshottedMaxDeployable").getAsInt(),
                json.get("providerSnapshotRevision").getAsLong(),
                json.get("managedConfigRevision").getAsLong(),
                json.get("policyRevision").getAsLong(),
                json.get("createdAtMs").getAsLong()
        );
    }

    private static void nullable(JsonObject json, String key, Object value) {
        if (value == null) {
            json.add(key, null);
        } else {
            json.addProperty(key, value.toString());
        }
    }

    @Nullable
    private static OwnerId owner(JsonObject json, String key) {
        String value = text(json, key);
        return value == null ? null : OwnerId.parse(value);
    }

    @Nullable
    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Nullable
    private static com.alechilles.alecstamework.companion.population
            .OwnerPopulationAdmissionPlan optionalOwnerPlan(
            JsonObject composition
    ) {
        JsonElement value = composition.get("ownerPlan");
        return value == null || value.isJsonNull()
                ? null
                : OwnerPopulationAdmissionPlanJsonCodec.decode(
                        value.getAsJsonObject()
                );
    }

    @Nullable
    private static com.alechilles.alecstamework.companion.population.group
            .PopulationGroupTransitionAdmissionRequest optionalGroupRequest(
            JsonObject composition
    ) {
        JsonElement value = composition.get("groupRequest");
        return value == null || value.isJsonNull()
                ? null
                : PopulationGroupTransitionAdmissionJsonCodec.decode(
                        value.getAsJsonObject()
                );
    }
}
