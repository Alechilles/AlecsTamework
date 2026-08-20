package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.facade.ReplacementCommandFamilyRosterApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementCommandTimedSummoningApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPaidCommandRevivalApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPersistenceDiagnosticsApi;
import javax.annotation.Nullable;

/**
 * Optional live/config collaborators required to expose restored public
 * feature APIs. Null dependencies fail closed and are never advertised.
 */
public record ReplacementFeatureApiDependencies(
        @Nullable PopulationGroupConfigRegistry populationGroups,
        @Nullable ReplacementCommandFamilyRosterApi.MutationAuthor
                commandRosters,
        @Nullable ReplacementCommandTimedSummoningApi.TransitionAuthor
                timedSummoning,
        @Nullable ReplacementCompanionProvisioningApi.MutationAuthor
                provisioning,
        @Nullable ReplacementPaidCommandRevivalApi.RequestAuthor paidRevival,
        @Nullable ReplacementPersistenceDiagnosticsApi.AvailabilityProbe
                availability,
        @Nullable ReplacementPersistenceDiagnosticsApi.IncidentLookup
                incidents,
        boolean captureResolvedEventsReady,
        boolean captureTameAndLinkReady,
        @Nullable BondedCompanionApi bondedCompanions,
        @Nullable ManagedActivityConfigRegistry managedActivities,
        @Nullable AdmissionProviderRegistry admissionProviders
) {
    /** Source-compatible constructor for callers without a bonded facade. */
    public ReplacementFeatureApiDependencies(
            @Nullable PopulationGroupConfigRegistry populationGroups,
            @Nullable ReplacementCommandFamilyRosterApi.MutationAuthor
                    commandRosters,
            @Nullable ReplacementCommandTimedSummoningApi.TransitionAuthor
                    timedSummoning,
            @Nullable ReplacementCompanionProvisioningApi.MutationAuthor
                    provisioning,
            @Nullable ReplacementPaidCommandRevivalApi.RequestAuthor paidRevival,
            @Nullable ReplacementPersistenceDiagnosticsApi.AvailabilityProbe
                    availability,
            @Nullable ReplacementPersistenceDiagnosticsApi.IncidentLookup
                    incidents,
            boolean captureResolvedEventsReady,
            boolean captureTameAndLinkReady
    ) {
        this(
                populationGroups,
                commandRosters,
                timedSummoning,
                provisioning,
                paidRevival,
                availability,
                incidents,
                captureResolvedEventsReady,
                captureTameAndLinkReady,
                null,
                null,
                null
        );
    }

    public static ReplacementFeatureApiDependencies none() {
        return new ReplacementFeatureApiDependencies(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }
}
