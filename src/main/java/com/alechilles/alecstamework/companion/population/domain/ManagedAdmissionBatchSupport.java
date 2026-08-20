package com.alechilles.alecstamework.companion.population.domain;

import java.util.List;
import java.util.UUID;

/** Scales one frozen admission payload for an internal aggregate request. */
final class ManagedAdmissionBatchSupport {
    private ManagedAdmissionBatchSupport() {
    }

    static ManagedAdmissionEvidenceAuthor.Authoring scale(
            ManagedAdmissionEvidenceAuthor.Authoring source,
            int count,
            List<UUID> children
    ) {
        List<PopulationDomainAdmissionOperation.DomainInput> domains =
                source.payload().domains().stream()
                        .map(input -> new PopulationDomainAdmissionOperation.DomainInput(
                                input.domainId(),
                                input.scope(),
                                input.worldKey(),
                                Math.multiplyExact(input.ownedDelta(), count),
                                Math.multiplyExact(input.deployableDelta(), count),
                                input.weight(),
                                input.maxOwned(),
                                input.maxDeployable(),
                                input.policyRevision()
                        )).toList();
        PopulationDomainAdmissionOperation.Payload payload = source.payload();
        return new ManagedAdmissionEvidenceAuthor.Authoring(
                new PopulationDomainAdmissionOperation.Payload(
                        payload.reservationId(), payload.profileId(), payload.ownerId(),
                        payload.expectedLifecycleRevision(), payload.ownerWorldKey(),
                        payload.sourceOwnerId(), payload.sourceWorldKey(),
                        payload.sourceLifecycle(), payload.targetLifecycle(),
                        payload.familyGroupId(), payload.providerId(),
                        payload.providerContractVersion(), payload.providerGenerationToken(),
                        payload.providerSnapshotRevision(), payload.managedConfigRevision(),
                        payload.expiresAtMs(), count, domains, children,
                        payload.createdAtMs()
                ),
                source.readiness(), source.providerReadiness(), source.decision()
        );
    }
}
