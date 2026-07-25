package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Immutable command for one exact terminal resolved capture attempt. */
public record CompanionCaptureRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nullable OwnerId resultingOwnerId,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String targetWorldKey,
        @Nonnull CaptureTerminalPlan terminal,
        @Nonnull CaptureSourceEvidence source,
        long requestedAtMs
) {
    public static final SnapshotKind SNAPSHOT_KIND = new SnapshotKind("capture");
    /** Version 1 is the released public minimal payload; replacement full-state capture is v2. */
    public static final int SNAPSHOT_VERSION = 2;

    public CompanionCaptureRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || targetAlias == null || terminal == null
                || source == null) {
            throw new IllegalArgumentException("Complete companion capture request is required");
        }
        targetWorldKey = requireText(targetWorldKey, "Capture target world");
        if (!targetWorldKey.equals(source.worldKey())) {
            throw new IllegalArgumentException(
                    "Capture source and target must share one world boundary"
            );
        }
        if (!terminal.resolution().attemptId().toString().equals(
                source.receiptKey()
        )) {
            throw new IllegalArgumentException(
                    "Capture source receipt must equal the terminal attempt ID"
            );
        }
        if (terminal instanceof CaptureTerminalPlan.CapturedItem captured) {
            requireCapturedEvidence(
                    profileId,
                    expectedLifecycleRevision,
                    source,
                    captured.evidence()
            );
        } else if (terminal
                instanceof CaptureTerminalPlan.TameAndCommandLink tame) {
            if (resultingOwnerId == null
                    || !resultingOwnerId.value().equals(
                    source.actorUuid()
            )) {
                throw new IllegalArgumentException(
                        "Tame/link capture owner must be the source actor"
                );
            }
            requireTameAndLinkEvidence(
                    profileId,
                    expectedLifecycleRevision,
                    resultingOwnerId,
                    targetAlias,
                    targetWorldKey,
                    tame.evidence(),
                    requestedAtMs
            );
        } else if (resultingOwnerId != null) {
            throw new IllegalArgumentException(
                    "Failed capture cannot change the resulting owner"
            );
        }
    }

    /** Source-compatible constructor for successful ordinary captures. */
    public CompanionCaptureRequest(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            OwnerId resultingOwnerId,
            NpcAlias targetAlias,
            String targetWorldKey,
            CompanionSnapshot snapshot,
            CapturedArtifact artifact,
            CaptureSourceEvidence source,
            long requestedAtMs
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                resultingOwnerId,
                targetAlias,
                targetWorldKey,
                new CaptureTerminalPlan.CapturedItem(
                        legacyResolution(
                                targetAlias, snapshot, artifact, source
                        ),
                        new CompanionSnapshotEvidence(snapshot, artifact)
                ),
                source,
                requestedAtMs
        );
    }

    public CompanionSnapshot snapshot() {
        return capturedEvidence().snapshot();
    }

    public CapturedArtifact artifact() {
        return capturedEvidence().artifact();
    }

    public CaptureAttemptResolution resolution() {
        return terminal.resolution();
    }

    public boolean capturedItem() {
        return terminal instanceof CaptureTerminalPlan.CapturedItem;
    }

    public boolean failedAttempt() {
        return terminal instanceof CaptureTerminalPlan.FailedAttempt;
    }

    public boolean tameAndCommandLink() {
        return terminal
                instanceof CaptureTerminalPlan.TameAndCommandLink;
    }

    public CaptureTameAndLinkEvidence tameAndLinkEvidence() {
        if (terminal
                instanceof CaptureTerminalPlan.TameAndCommandLink tame) {
            return tame.evidence();
        }
        throw new IllegalStateException(
                "Capture terminal result does not contain tame/link evidence"
        );
    }

    private static void requireCapturedEvidence(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CaptureSourceEvidence source,
            CompanionSnapshotEvidence evidence
    ) {
        CompanionSnapshot snapshot = evidence.snapshot();
        CapturedArtifact artifact = evidence.artifact();
        if (!profileId.equals(snapshot.profileId())
                || !SNAPSHOT_KIND.equals(snapshot.kind())
                || snapshot.payloadVersion() != SNAPSHOT_VERSION
                || !snapshot.current()
                || !snapshot.sourceLifecycleRevision().equals(
                        expectedLifecycleRevision.next()
                )) {
            throw new IllegalArgumentException(
                    "Capture snapshot must describe the post-prepare lifecycle fence"
            );
        }
        if (!snapshot.snapshotId().toString().equals(source.receiptKey())) {
            throw new IllegalArgumentException(
                    "Capture source receipt must equal the capture snapshot ID"
            );
        }
        requireArtifactReceipt(artifact, source.receiptKey());
    }

    private static void requireTameAndLinkEvidence(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            OwnerId resultingOwnerId,
            NpcAlias targetAlias,
            String targetWorldKey,
            CaptureTameAndLinkEvidence evidence,
            long requestedAtMs
    ) {
        if (resultingOwnerId == null
                || !profileId.equals(
                evidence.expectedIdentity().profileId()
        )
                || !expectedLifecycleRevision.equals(
                evidence.expectedLifecycle().revision()
        )
                || !resultingOwnerId.equals(
                evidence.finalLifecycle().ownerId()
        )
                || !resultingOwnerId.equals(
                evidence.live().targetOwnerId()
        )
                || !targetAlias.toString().equals(
                evidence.expectedLifecycle().location().key()
        )
                || !targetWorldKey.equals(
                evidence.expectedLifecycle().location().worldKey()
        )
                || evidence.finalLifecycle().stateChangedAtMs()
                != requestedAtMs
                || evidence.ownerPopulation().increases().stream()
                .anyMatch(increase -> !resultingOwnerId.equals(
                        increase.scope().ownerId()
                ))) {
            throw new IllegalArgumentException(
                    "Tame/link capture request evidence is inconsistent"
            );
        }
    }

    private CompanionSnapshotEvidence capturedEvidence() {
        if (terminal instanceof CaptureTerminalPlan.CapturedItem captured) {
            return captured.evidence();
        }
        throw new IllegalStateException(
                "Capture terminal result does not contain an artifact"
        );
    }

    private static CaptureAttemptResolution legacyResolution(
            NpcAlias targetAlias,
            CompanionSnapshot snapshot,
            CapturedArtifact artifact,
            CaptureSourceEvidence source
    ) {
        if (targetAlias == null || snapshot == null || artifact == null
                || source == null) {
            throw new IllegalArgumentException(
                    "Complete legacy captured-item evidence is required"
            );
        }
        return new CaptureAttemptResolution(
                java.util.UUID.fromString(source.receiptKey()),
                artifactRole(artifact, targetAlias),
                new CaptureAttemptFormula(
                        "tamework:legacy-captured-item",
                        0,
                        CaptureChanceMode.GUARANTEED,
                        0,
                        1.0D,
                        0.0D,
                        0.0D,
                        1.0D,
                        null,
                        0,
                        0,
                        0.0D,
                        1.0D,
                        0.0D,
                        null,
                        Sha256Hash.ofUtf8("[]"),
                        0
                ),
                CaptureSourceConsumption.SUCCESS_ONLY,
                CaptureSuccessDisposition.CAPTURED_ITEM,
                CaptureAttemptResolution.Outcome.SUCCESS,
                "capture-guaranteed-item",
                1.0D,
                true,
                0.0D,
                null,
                null
        );
    }

    private static String artifactRole(
            CapturedArtifact artifact,
            NpcAlias fallback
    ) {
        BsonValue role = BsonDocument.parse(
                artifact.metadataExtendedJson()
        ).get(TameworkMetadataKeys.CAPTURE_ROLE_ID);
        return role != null && role.isString()
                && !role.asString().getValue().isBlank()
                ? role.asString().getValue()
                : "legacy:" + fallback;
    }

    private static void requireArtifactReceipt(
            CapturedArtifact artifact,
            String expectedReceipt
    ) {
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        BsonValue receipt = metadata.get(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
        );
        if (receipt == null || !receipt.isString()
                || !expectedReceipt.equals(receipt.asString().getValue())) {
            throw new IllegalArgumentException(
                    "Captured artifact receipt must equal the capture source receipt"
            );
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
