package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePopulationDomainParticipant;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.ArrayList;
import java.util.List;

/** Builds the exact operation scopes and domain participant for one admission. */
final class PopulationDomainAdmissionParticipants {
    private PopulationDomainAdmissionParticipants() {
    }

    static SqlitePopulationDomainParticipant participant(
            OperationId operationId,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        return new SqlitePopulationDomainParticipant(
                payload.reservations(operationId)
        );
    }

    static List<OperationScope> scopes(
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        ArrayList<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.profile(payload.profileId()));
        if (payload.ownerId() != null) {
            scopes.add(OperationScope.owner(payload.ownerId()));
        }
        return List.copyOf(scopes);
    }
}
