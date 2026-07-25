package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** JSON translation for capture-owned owner/group admission evidence. */
final class CapturePopulationEvidenceJsonCodec {
    private CapturePopulationEvidenceJsonCodec() {
    }

    static JsonObject encodeOwner(
            @Nonnull OwnerPopulationAdmissionPlan plan
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", plan.profileId().toString());
        nullable(
                json,
                "expectedLifecycleRevision",
                plan.expectedLifecycleRevision() == null
                        ? null
                        : plan.expectedLifecycleRevision().value()
        );
        JsonArray increases = new JsonArray();
        for (OwnerPopulationAdmissionPlan.LimitIncrease increase
                : plan.increases()) {
            JsonObject row = new JsonObject();
            row.addProperty("kind", increase.scope().kind().name());
            row.addProperty(
                    "ownerId", increase.scope().ownerId().toString()
            );
            nullable(
                    row,
                    "ownerWorldKey",
                    increase.scope().ownerWorldKey()
            );
            row.addProperty(
                    "capacityDelta", increase.capacityDelta()
            );
            row.addProperty(
                    "snapshottedLimit", increase.snapshottedLimit()
            );
            increases.add(row);
        }
        json.add("increases", increases);
        return json;
    }

    static OwnerPopulationAdmissionPlan decodeOwner(
            @Nonnull JsonObject json
    ) {
        ArrayList<OwnerPopulationAdmissionPlan.LimitIncrease> increases =
                new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("increases")) {
            JsonObject row = element.getAsJsonObject();
            OwnerId owner = OwnerId.parse(
                    row.get("ownerId").getAsString()
            );
            OwnerPopulationScope.Kind kind =
                    OwnerPopulationScope.Kind.valueOf(
                            row.get("kind").getAsString()
                    );
            String world = text(row, "ownerWorldKey");
            increases.add(
                    new OwnerPopulationAdmissionPlan.LimitIncrease(
                            kind == OwnerPopulationScope.Kind.GLOBAL
                                    ? OwnerPopulationScope.global(owner)
                                    : OwnerPopulationScope.perWorld(
                                    owner, world
                            ),
                            row.get("capacityDelta").getAsInt(),
                            row.get("snapshottedLimit").getAsInt()
                    )
            );
        }
        JsonElement revision = json.get(
                "expectedLifecycleRevision"
        );
        return new OwnerPopulationAdmissionPlan(
                ProfileId.parse(json.get("profileId").getAsString()),
                revision == null || revision.isJsonNull()
                        ? null
                        : new LifecycleRevision(revision.getAsLong()),
                increases
        );
    }

    static JsonObject encodeGroups(
            @Nonnull CapturePopulationGroupEvidence evidence
    ) {
        JsonObject json = new JsonObject();
        json.add(
                "expectedAssignment",
                evidence.expectedAssignment() == null
                        ? null
                        : PopulationGroupAssignmentJsonCodec.encode(
                                evidence.expectedAssignment()
                        )
        );
        json.add(
                "targetAssignment",
                PopulationGroupAssignmentJsonCodec.encode(
                        evidence.targetPlan().target()
                )
        );
        JsonArray reservations = new JsonArray();
        for (PopulationGroupReservation reservation
                : evidence.targetPlan().reservations()) {
            JsonObject row = new JsonObject();
            row.addProperty(
                    "operationId", reservation.operationId().toString()
            );
            row.addProperty(
                    "profileId", reservation.profileId().toString()
            );
            nullable(
                    row,
                    "expectedLifecycleRevision",
                    reservation.expectedLifecycleRevision() == null
                            ? null
                            : reservation.expectedLifecycleRevision()
                            .value()
            );
            row.addProperty(
                    "ownerId",
                    reservation.bucket().ownerId().toString()
            );
            row.addProperty(
                    "groupId", reservation.bucket().groupId()
            );
            row.addProperty(
                    "scope", reservation.bucket().scope().name()
            );
            nullable(
                    row,
                    "ownerWorldKey",
                    reservation.bucket().ownerWorldKey()
            );
            row.addProperty("ownedDelta", reservation.ownedDelta());
            row.addProperty("activeDelta", reservation.activeDelta());
            row.addProperty(
                    "snapshottedMaxOwned",
                    reservation.snapshottedMaxOwned()
            );
            row.addProperty(
                    "snapshottedMaxActive",
                    reservation.snapshottedMaxActive()
            );
            row.addProperty(
                    "policyRevision", reservation.policyRevision()
            );
            row.addProperty(
                    "createdAtMs", reservation.createdAtMs()
            );
            reservations.add(row);
        }
        json.add("reservations", reservations);
        return json;
    }

    static CapturePopulationGroupEvidence decodeGroups(
            @Nonnull JsonObject json
    ) {
        PopulationGroupAssignment expected =
                json.get("expectedAssignment") == null
                        || json.get("expectedAssignment").isJsonNull()
                        ? null
                        : PopulationGroupAssignmentJsonCodec.decode(
                                json.getAsJsonObject(
                                        "expectedAssignment"
                                )
                        );
        PopulationGroupAssignment target =
                PopulationGroupAssignmentJsonCodec.decode(
                        json.getAsJsonObject("targetAssignment")
                );
        ArrayList<PopulationGroupReservation> reservations =
                new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("reservations")) {
            JsonObject row = element.getAsJsonObject();
            JsonElement revision = row.get(
                    "expectedLifecycleRevision"
            );
            OwnerId owner = OwnerId.parse(
                    row.get("ownerId").getAsString()
            );
            PopulationGroupScope scope = PopulationGroupScope.valueOf(
                    row.get("scope").getAsString()
            );
            reservations.add(new PopulationGroupReservation(
                    OperationId.parse(
                            row.get("operationId").getAsString()
                    ),
                    ProfileId.parse(
                            row.get("profileId").getAsString()
                    ),
                    revision == null || revision.isJsonNull()
                            ? null
                            : new LifecycleRevision(
                                    revision.getAsLong()
                            ),
                    new PopulationGroupBucket(
                            owner,
                            row.get("groupId").getAsString(),
                            scope,
                            text(row, "ownerWorldKey")
                    ),
                    row.get("ownedDelta").getAsInt(),
                    row.get("activeDelta").getAsInt(),
                    row.get("snapshottedMaxOwned").getAsInt(),
                    row.get("snapshottedMaxActive").getAsInt(),
                    row.get("policyRevision").getAsLong(),
                    row.get("createdAtMs").getAsLong()
            ));
        }
        return new CapturePopulationGroupEvidence(
                expected,
                new PopulationGroupAssignmentPlan(
                        target, reservations
                )
        );
    }

    private static void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, null);
        } else if (value instanceof Number number) {
            json.addProperty(name, number);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}
