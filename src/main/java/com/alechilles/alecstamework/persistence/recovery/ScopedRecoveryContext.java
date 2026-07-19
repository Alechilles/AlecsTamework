package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import java.util.List;
import javax.annotation.Nonnull;

/** Immutable incident and fence snapshot supplied to one domain verifier. */
public record ScopedRecoveryContext(@Nonnull PersistenceIncident incident,
                                    @Nonnull List<PersistenceQuarantineRecord> quarantines,
                                    @Nonnull ScopedRecoveryTrigger trigger) {
    public ScopedRecoveryContext {
        if (incident == null || trigger == null) throw new IllegalArgumentException("incident/trigger");
        quarantines = quarantines == null ? List.of() : List.copyOf(quarantines);
    }
}
