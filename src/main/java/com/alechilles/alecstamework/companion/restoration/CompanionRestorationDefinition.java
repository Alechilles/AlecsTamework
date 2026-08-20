package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.LifecycleAdmissionEvidenceJsonCodec;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacementJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Typed operation definition for death and lost restoration. */
public final class CompanionRestorationDefinition
        implements OperationDefinition<CompanionRestorationRequest> {
    public static final CompanionRestorationDefinition INSTANCE =
            new CompanionRestorationDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_restoration");

    private CompanionRestorationDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 3;
    }

    @Override
    public Class<CompanionRestorationRequest> payloadType() {
        return CompanionRestorationRequest.class;
    }

    @Override
    public String encode(CompanionRestorationRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        json.addProperty("sourceState", payload.sourceState().name());
        json.add(
                "sourceSnapshot",
                CompanionSnapshotJsonCodec.encode(payload.sourceSnapshot())
        );
        if (payload.restoresLive()) {
            json.add(
                    "projection",
                    RestorationProjectionJsonCodec.encode(payload.projection())
            );
            json.addProperty(
                    "targetAlias", payload.targetAlias().toString()
            );
            json.add(
                    "placement",
                    CompanionSpawnPlacementJsonCodec.encode(
                            payload.placement()
                    )
            );
            json.addProperty(
                    "spawnReceiptKey", payload.spawnReceiptKey()
            );
        } else {
            json.addProperty(
                    "targetState", payload.targetState().name()
            );
        }
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        if (payload.admissionEvidence() != null) {
            json.add(
                    "admissionEvidence",
                    LifecycleAdmissionEvidenceJsonCodec.encode(
                            payload.admissionEvidence()
                    )
            );
        }
        return json.toString();
    }

    @Override
    public CompanionRestorationRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        LifecycleState targetState = json.has("targetState")
                ? LifecycleState.valueOf(
                json.get("targetState").getAsString()
        )
                : LifecycleState.ACTIVE;
        if (targetState == LifecycleState.PROVISIONED_DORMANT) {
            CompanionRestorationRequest dormant =
                    CompanionRestorationRequest.reviveProvisionedDormant(
                    ProfileId.parse(json.get("profileId").getAsString()),
                    new LifecycleRevision(
                            json.get("expectedLifecycleRevision").getAsLong()
                    ),
                    CompanionSnapshotJsonCodec.decode(
                            json.getAsJsonObject("sourceSnapshot")
                    ),
                    json.get("requestedAtMs").getAsLong()
            );
            return json.has("admissionEvidence")
                    && !json.get("admissionEvidence").isJsonNull()
                    ? dormant.withAdmissionEvidence(
                            LifecycleAdmissionEvidenceJsonCodec.decode(
                                    json.getAsJsonObject("admissionEvidence")
                            )
                    )
                    : dormant;
        }
        CompanionRestorationRequest decoded = new CompanionRestorationRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                LifecycleState.valueOf(json.get("sourceState").getAsString()),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("sourceSnapshot")
                ),
                RestorationProjectionJsonCodec.decode(
                        json.getAsJsonObject("projection")
                ),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                CompanionSpawnPlacementJsonCodec.decode(
                        json.getAsJsonObject("placement")
                ),
                json.get("spawnReceiptKey").getAsString(),
                json.get("requestedAtMs").getAsLong()
        );
        return json.has("admissionEvidence")
                && !json.get("admissionEvidence").isJsonNull()
                ? decoded.withAdmissionEvidence(
                        LifecycleAdmissionEvidenceJsonCodec.decode(
                                json.getAsJsonObject("admissionEvidence")
                        )
                )
                : decoded;
    }
}
