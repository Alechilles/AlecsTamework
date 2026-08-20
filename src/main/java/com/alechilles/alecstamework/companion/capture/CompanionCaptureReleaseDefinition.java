package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.population.domain.LifecycleAdmissionEvidenceJsonCodec;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityJsonCodec;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacementJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/** Typed operation definition for receipt-first captured-artifact release. */
public final class CompanionCaptureReleaseDefinition
        implements OperationDefinition<CompanionCaptureReleaseRequest> {
    public static final CompanionCaptureReleaseDefinition INSTANCE =
            new CompanionCaptureReleaseDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_capture_release");

    private CompanionCaptureReleaseDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 1;
    }

    @Override
    public Class<CompanionCaptureReleaseRequest> payloadType() {
        return CompanionCaptureReleaseRequest.class;
    }

    @Override
    public String encode(CompanionCaptureReleaseRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        json.add(
                "sourceSnapshot",
                CompanionSnapshotJsonCodec.encode(payload.sourceSnapshot())
        );
        json.addProperty("sourceAlias", payload.sourceAlias().toString());
        json.add("projection", encodeProjection(payload.projection()));
        json.add("source", encodeSource(payload.source()));
        json.addProperty("targetAlias", payload.targetAlias().toString());
        if (payload.ownerAssignment() != null) {
            json.addProperty(
                    "ownerAssignment",
                    payload.ownerAssignment().toString()
            );
        }
        json.add(
                "placement",
                CompanionSpawnPlacementJsonCodec.encode(payload.placement())
        );
        json.addProperty(
                "inventoryReceiptKey",
                payload.inventoryReceiptKey()
        );
        json.addProperty("spawnReceiptKey", payload.spawnReceiptKey());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        if (payload.legacyRecovery() != null) {
            json.add(
                    "legacyRecovery",
                    encodeLegacyRecovery(payload.legacyRecovery())
            );
        }
        if (payload.modernRecovery() != null) {
            json.add(
                    "modernRecovery",
                    encodeModernRecovery(payload.modernRecovery())
            );
        }
        if (payload.orphanRecovery() != null) {
            json.add(
                    "orphanRecovery",
                    encodeOrphanRecovery(payload.orphanRecovery())
            );
        }
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
    public CompanionCaptureReleaseRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        CompanionCaptureReleaseRequest decoded = new CompanionCaptureReleaseRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("sourceSnapshot")
                ),
                NpcAlias.parse(json.get("sourceAlias").getAsString()),
                decodeProjection(json.getAsJsonObject("projection")),
                decodeSource(json.getAsJsonObject("source")),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.has("ownerAssignment")
                        && !json.get("ownerAssignment").isJsonNull()
                        ? OwnerId.parse(
                                json.get("ownerAssignment").getAsString()
                        )
                        : null,
                CompanionSpawnPlacementJsonCodec.decode(
                        json.getAsJsonObject("placement")
                ),
                json.get("inventoryReceiptKey").getAsString(),
                json.get("spawnReceiptKey").getAsString(),
                json.get("requestedAtMs").getAsLong(),
                json.has("legacyRecovery")
                        && !json.get("legacyRecovery").isJsonNull()
                        ? decodeLegacyRecovery(
                                json.getAsJsonObject("legacyRecovery")
                        )
                        : null,
                json.has("modernRecovery")
                        && !json.get("modernRecovery").isJsonNull()
                        ? decodeModernRecovery(
                                json.getAsJsonObject("modernRecovery")
                        )
                        : null,
                json.has("orphanRecovery")
                        && !json.get("orphanRecovery").isJsonNull()
                        ? decodeOrphanRecovery(
                                json.getAsJsonObject("orphanRecovery")
                        )
                        : null
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

    private JsonObject encodeOrphanRecovery(
            CaptureReleaseOrphanRecoveryEvidence evidence
    ) {
        JsonObject json = new JsonObject();
        json.add(
                "initialIdentity",
                CompanionIdentityJsonCodec.encode(evidence.initialIdentity())
        );
        if (evidence.initialOwner() != null) {
            json.addProperty(
                    "initialOwner", evidence.initialOwner().toString()
            );
        }
        return json;
    }

    private CaptureReleaseOrphanRecoveryEvidence decodeOrphanRecovery(
            JsonObject json
    ) {
        return new CaptureReleaseOrphanRecoveryEvidence(
                CompanionIdentityJsonCodec.decode(
                        json.getAsJsonObject("initialIdentity")
                ),
                json.has("initialOwner")
                        && !json.get("initialOwner").isJsonNull()
                        ? OwnerId.parse(json.get("initialOwner").getAsString())
                        : null
        );
    }

    private JsonObject encodeLegacyRecovery(
            CaptureReleaseLegacyRecoveryEvidence evidence
    ) {
        JsonObject json = new JsonObject();
        json.add(
                "historicalSnapshot",
                CompanionSnapshotJsonCodec.encode(
                        evidence.historicalSnapshot()
                )
        );
        json.addProperty(
                "reconciliationGeneration",
                evidence.reconciliationGeneration().value()
        );
        json.addProperty(
                "sourceAliasGeneration",
                evidence.sourceAliasGeneration()
        );
        json.addProperty(
                "sourceAliasMappedAtMs",
                evidence.sourceAliasMappedAtMs()
        );
        return json;
    }

    private CaptureReleaseLegacyRecoveryEvidence decodeLegacyRecovery(
            JsonObject json
    ) {
        return new CaptureReleaseLegacyRecoveryEvidence(
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("historicalSnapshot")
                ),
                new com.alechilles.alecstamework.companion.lifecycle
                        .ReconciliationGeneration(
                        json.get("reconciliationGeneration").getAsLong()
                ),
                json.get("sourceAliasGeneration").getAsLong(),
                json.get("sourceAliasMappedAtMs").getAsLong()
        );
    }

    private JsonObject encodeModernRecovery(
            CaptureReleaseModernRecoveryEvidence evidence
    ) {
        JsonObject json = new JsonObject();
        json.add(
                "supersededSnapshot",
                CompanionSnapshotJsonCodec.encode(
                        evidence.supersededSnapshot()
                )
        );
        json.addProperty(
                "canonicalSourceAlias",
                evidence.canonicalSourceAlias().toString()
        );
        json.addProperty(
                "reconciliationGeneration",
                evidence.reconciliationGeneration().value()
        );
        json.addProperty(
                "canonicalAliasGeneration",
                evidence.canonicalAliasGeneration()
        );
        json.addProperty(
                "canonicalAliasMappedAtMs",
                evidence.canonicalAliasMappedAtMs()
        );
        return json;
    }

    private CaptureReleaseModernRecoveryEvidence decodeModernRecovery(
            JsonObject json
    ) {
        return new CaptureReleaseModernRecoveryEvidence(
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("supersededSnapshot")
                ),
                NpcAlias.parse(
                        json.get("canonicalSourceAlias").getAsString()
                ),
                new com.alechilles.alecstamework.companion.lifecycle
                        .ReconciliationGeneration(
                        json.get("reconciliationGeneration").getAsLong()
                ),
                json.get("canonicalAliasGeneration").getAsLong(),
                json.get("canonicalAliasMappedAtMs").getAsLong()
        );
    }

    private JsonObject encodeSource(CaptureReleaseSourceEvidence source) {
        JsonObject json = new JsonObject();
        json.addProperty("actorUuid", source.actorUuid().toString());
        json.addProperty("worldKey", source.worldKey());
        json.addProperty("slot", source.slot());
        json.add(
                "sourceArtifact",
                CapturedArtifactJsonCodec.encode(source.sourceArtifact())
        );
        json.add(
                "receiptArtifact",
                CapturedArtifactJsonCodec.encode(source.receiptArtifact())
        );
        return json;
    }

    private JsonObject encodeProjection(
            SnapshotCodecRegistry.EncodedSnapshot projection
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", projection.kind().toString());
        json.addProperty("payloadVersion", projection.payloadVersion());
        json.addProperty("payloadJson", projection.payloadJson());
        json.addProperty("payloadHash", projection.payloadHash().toString());
        return json;
    }

    private SnapshotCodecRegistry.EncodedSnapshot decodeProjection(
            JsonObject json
    ) {
        return new SnapshotCodecRegistry.EncodedSnapshot(
                new SnapshotKind(json.get("kind").getAsString()),
                json.get("payloadVersion").getAsInt(),
                json.get("payloadJson").getAsString(),
                Sha256Hash.parse(json.get("payloadHash").getAsString())
        );
    }

    private CaptureReleaseSourceEvidence decodeSource(JsonObject json) {
        return new CaptureReleaseSourceEvidence(
                UUID.fromString(json.get("actorUuid").getAsString()),
                json.get("worldKey").getAsString(),
                json.get("slot").getAsInt(),
                CapturedArtifactJsonCodec.decode(
                        json.getAsJsonObject("sourceArtifact")
                ),
                CapturedArtifactJsonCodec.decode(
                        json.getAsJsonObject("receiptArtifact")
                )
        );
    }
}
