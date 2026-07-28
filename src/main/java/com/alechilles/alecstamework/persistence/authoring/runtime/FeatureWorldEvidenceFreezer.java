package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.persistence.authoring.ReplacementFeatureLiveEvidenceSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** World-thread implementation seam used by the production live evidence source. */
interface FeatureWorldEvidenceFreezer {
    @Nullable
    ReplacementFeatureLiveEvidenceSource.RosterAccess freezeRoster(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.RosterAccessIntent intent
    );

    @Nullable
    ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence freezeTimed(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent
    );

    @Nullable
    ReplacementFeatureLiveEvidenceSource.ProvisioningWorldEvidence
    freezeProvisioning(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.ProvisioningWorldIntent intent
    );

    @Nullable
    ReplacementFeatureLiveEvidenceSource.PaidInventoryEvidence freezePaid(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent intent
    );
}
