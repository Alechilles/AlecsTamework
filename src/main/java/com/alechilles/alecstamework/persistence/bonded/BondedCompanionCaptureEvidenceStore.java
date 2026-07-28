package com.alechilles.alecstamework.persistence.bonded;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Profile-lifetime capture-proof query and publication checkpoint port. */
public interface BondedCompanionCaptureEvidenceStore {
    @Nonnull
    Optional<BondedCompanionCaptureEvidence> findCaptureEvidence(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull UUID sourceNpcUuid
    );

    @Nonnull
    List<BondedCompanionCaptureEvidence> listUnpublishedCaptureEvidence(
            int limit
    );

    boolean markCaptureEvidencePublished(
            @Nonnull BondedCompanionCaptureEvidence evidence,
            long publishedAtMs
    );
}
