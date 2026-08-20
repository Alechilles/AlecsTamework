package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import java.util.Objects;

/** Compares frozen admission evidence while ignoring only token timestamps. */
final class PopulationAdmissionPayloadMatcher {
    private PopulationAdmissionPayloadMatcher() {
    }

    static boolean sameExceptTimes(
            PopulationDomainAdmissionOperation.Payload stored,
            PopulationDomainAdmissionOperation.Payload requested
    ) {
        return stored.reservationId().equals(requested.reservationId())
                && stored.profileId().equals(requested.profileId())
                && Objects.equals(stored.ownerId(), requested.ownerId())
                && Objects.equals(stored.sourceOwnerId(), requested.sourceOwnerId())
                && Objects.equals(stored.sourceWorldKey(), requested.sourceWorldKey())
                && Objects.equals(stored.sourceLifecycle(), requested.sourceLifecycle())
                && stored.targetLifecycle() == requested.targetLifecycle()
                && stored.familyGroupId().equals(requested.familyGroupId())
                && Objects.equals(
                        stored.expectedLifecycleRevision(),
                        requested.expectedLifecycleRevision()
                )
                && Objects.equals(stored.ownerWorldKey(), requested.ownerWorldKey())
                && stored.providerId().equals(requested.providerId())
                && stored.providerContractVersion() == requested.providerContractVersion()
                && stored.providerGenerationToken().equals(requested.providerGenerationToken())
                && stored.providerSnapshotRevision() == requested.providerSnapshotRevision()
                && stored.managedConfigRevision() == requested.managedConfigRevision()
                && stored.requestedCount() == requested.requestedCount()
                && stored.domains().equals(requested.domains())
                && stored.provisionalChildIds().equals(requested.provisionalChildIds());
    }
}
