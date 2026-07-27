package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionCaptureEvidenceView;
import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained capture proof retained inside one bounded bonded operation. */
public record BondedCompanionCaptureEvidence(
        @Nonnull UUID operationId,
        @Nonnull UUID attemptId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String familyId,
        @Nonnull UUID sourceNpcUuid,
        @Nonnull String profileId,
        @Nonnull String roleId,
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull String sourceItemId,
        @Nonnull String spawnerConfigId,
        long spawnerConfigRevision,
        @Nullable String capturePolicyConfigId,
        long capturePolicyConfigRevision,
        @Nonnull CaptureSourceConsumption sourceConsumption,
        @Nonnull CaptureSuccessDisposition successDisposition,
        @Nonnull CaptureAttemptOutcome outcome,
        @Nonnull String reason,
        @Nonnull String sourceWorldKey,
        long committedAtMs
) {
    public BondedCompanionCaptureEvidence {
        BondedCompanionCaptureEvidenceView validated =
                new BondedCompanionCaptureEvidenceView(
                        operationId, attemptId, ownerUuid, rosterId, familyId,
                        sourceNpcUuid, profileId, roleId, callerNamespace,
                        idempotencyKey, sourceItemId, spawnerConfigId,
                        spawnerConfigRevision, capturePolicyConfigId,
                        capturePolicyConfigRevision, sourceConsumption,
                        successDisposition, outcome, reason, sourceWorldKey,
                        committedAtMs
                );
        rosterId = validated.rosterId();
        familyId = validated.familyId();
        profileId = validated.profileId();
        roleId = validated.roleId();
        callerNamespace = validated.callerNamespace();
        idempotencyKey = validated.idempotencyKey();
        sourceItemId = validated.sourceItemId();
        spawnerConfigId = validated.spawnerConfigId();
        capturePolicyConfigId = validated.capturePolicyConfigId();
        reason = validated.reason();
        sourceWorldKey = validated.sourceWorldKey();
    }

    /** Returns the public immutable view without reading any current state. */
    @Nonnull
    public BondedCompanionCaptureEvidenceView toView() {
        return new BondedCompanionCaptureEvidenceView(
                operationId, attemptId, ownerUuid, rosterId, familyId,
                sourceNpcUuid, profileId, roleId, callerNamespace,
                idempotencyKey, sourceItemId, spawnerConfigId,
                spawnerConfigRevision, capturePolicyConfigId,
                capturePolicyConfigRevision, sourceConsumption,
                successDisposition, outcome, reason, sourceWorldKey,
                committedAtMs
        );
    }
}
