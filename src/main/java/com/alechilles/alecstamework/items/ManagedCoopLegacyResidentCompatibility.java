package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;

/**
 * Recognizes the one markerless deployed-resident shape created by the schema-v5 legacy migration.
 *
 * <p>These rows predate projection markers. They remain eligible only when immutable managed-coop
 * indexes are already trusted, the caller has found no conflicting operation, and the live UUID is
 * the exact unique deployed UUID retained by the migration row.</p>
 */
final class ManagedCoopLegacyResidentCompatibility {
    private static final String LEGACY_RESIDENT_PREFIX = "legacy:";

    private ManagedCoopLegacyResidentCompatibility() {
    }

    static boolean isExactMarkerlessDeployment(Observation observation,
                                                ResidentRecord resident) {
        return observation.marker() == null
                && resident.residentId().startsWith(LEGACY_RESIDENT_PREFIX)
                && resident.state() == ResidentState.DEPLOYED
                && resident.generation() == 0L
                && resident.sourceNpcUuid() == null
                && resident.deployedNpcUuid() != null
                && resident.deployedNpcUuid().equals(resident.residentUuid())
                && resident.deployedNpcUuid().equals(observation.npcUuid());
    }
}
