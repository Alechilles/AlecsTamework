package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Bounded replacement incident evidence for sanitized public diagnostics.
 *
 * <p>Raw scope keys remain inside Tamework. Public adapters must hash them
 * before returning a view to another mod.</p>
 */
public record PublicPersistenceIncidentEvidence(
        @Nonnull IncidentRecord incident,
        @Nonnull List<ScopeQuarantine> quarantines,
        @Nonnull Optional<OperationEnvelope> operation
) {
    public PublicPersistenceIncidentEvidence {
        if (incident == null || quarantines == null || operation == null
                || quarantines.stream().anyMatch(row ->
                !incident.incidentId().equals(row.incidentId()))) {
            throw new IllegalArgumentException(
                    "Consistent public incident evidence is required"
            );
        }
        quarantines = List.copyOf(quarantines);
    }
}
