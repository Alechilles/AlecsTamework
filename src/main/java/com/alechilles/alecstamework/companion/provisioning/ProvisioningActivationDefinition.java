package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivationJsonCodec;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacementJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.EncodedSnapshotJsonCodec;
import com.alechilles.alecstamework.companion.population.domain.LifecycleAdmissionEvidenceJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-three shared live operation definition for initial activation. */
public final class ProvisioningActivationDefinition
        implements OperationDefinition<ProvisioningActivationRequest> {
    public static final ProvisioningActivationDefinition INSTANCE =
            new ProvisioningActivationDefinition();
    public static final OperationKind KIND =
            new OperationKind("provisioning_activation");

    private ProvisioningActivationDefinition() {
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
    public Class<ProvisioningActivationRequest> payloadType() {
        return ProvisioningActivationRequest.class;
    }

    @Override
    public String encode(ProvisioningActivationRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty(
                "callerNamespace",
                payload.origin().callerNamespace()
        );
        json.addProperty("callerKey", payload.origin().callerKey());
        json.add(
                "groupAdmission",
                PopulationGroupTransitionAdmissionJsonCodec.encode(
                        payload.groupAdmission()
                )
        );
        json.addProperty(
                "targetAlias", payload.targetAlias().toString()
        );
        json.addProperty("expectedRoleId", payload.expectedRoleId());
        json.add(
                "fullState",
                EncodedSnapshotJsonCodec.encode(payload.fullState())
        );
        json.add(
                "placement",
                CompanionSpawnPlacementJsonCodec.encode(payload.placement())
        );
        json.addProperty("spawnReceiptKey", payload.spawnReceiptKey());
        json.add(
                "timedActivation",
                payload.timedActivation() == null
                        ? null
                        : TimedSummonActivationJsonCodec.encode(
                                payload.timedActivation()
                        )
        );
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
    public ProvisioningActivationRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        JsonElement timed = json.get("timedActivation");
        ProvisioningActivationRequest decoded = new ProvisioningActivationRequest(
                new ProvisioningOrigin(
                        json.get("callerNamespace").getAsString(),
                        json.get("callerKey").getAsString()
                ),
                PopulationGroupTransitionAdmissionJsonCodec.decode(
                        json.getAsJsonObject("groupAdmission")
                ),
                NpcAlias.parse(
                        json.get("targetAlias").getAsString()
                ),
                json.get("expectedRoleId").getAsString(),
                EncodedSnapshotJsonCodec.decode(
                        json.getAsJsonObject("fullState")
                ),
                CompanionSpawnPlacementJsonCodec.decode(
                        json.getAsJsonObject("placement")
                ),
                json.get("spawnReceiptKey").getAsString(),
                timed == null || timed.isJsonNull()
                        ? null
                        : TimedSummonActivationJsonCodec.decode(
                                timed.getAsJsonObject()
                        ),
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
