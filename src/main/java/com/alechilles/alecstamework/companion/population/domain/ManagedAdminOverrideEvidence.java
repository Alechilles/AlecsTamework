package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds limit-free evidence that still retains every managed claim. */
final class ManagedAdminOverrideEvidence {
    private ManagedAdminOverrideEvidence() {
    }

    static boolean applies(PopulationAdmissionRequest admission) {
        return admission.operation() == PopulationAdmissionOperation.ADMIN_FORCE
                && admission.forcePolicy()
                == PopulationAdmissionForcePolicy.ADMIN_OVERRIDE;
    }

    static PopulationAdmissionProviderDecision decision(
            ManagedActivityConfigRegistry.Readiness readiness,
            ManagedActivityConfigRegistry.RoleResolution resolution
    ) {
        Set<PopulationDomainClaim> claims = resolution.profile().domains()
                .values().stream()
                .map(domain -> new PopulationDomainClaim(
                        domain.domainId(),
                        resolution.family().weight(),
                        domain.owned(),
                        domain.deployable()
                ))
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Integer> limits = resolution.profile().domains()
                .keySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        domainId -> domainId,
                        ignored -> 0
                ));
        return new PopulationAdmissionProviderDecision(
                PopulationAdmissionProviderStatus.ALLOW,
                "admin-override",
                claims,
                limits,
                0L,
                readiness.configRevision()
        );
    }
}
