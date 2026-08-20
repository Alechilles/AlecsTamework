package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        return LifecycleAdmissionEvidence.managed(
                PopulationDomainAdmissionDefinition.INSTANCE.decode(
                        payloadJson.toString()
                ),
                composition
        );
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
