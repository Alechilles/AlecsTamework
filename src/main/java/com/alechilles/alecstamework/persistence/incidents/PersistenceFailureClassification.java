package com.alechilles.alecstamework.persistence.incidents;

import java.util.List;
import javax.annotation.Nonnull;

/** Central classifier result consumed by incident reporting and containment. */
public record PersistenceFailureClassification(@Nonnull PersistenceFailureClass failureClass,
                                               @Nonnull PersistenceDisposition disposition,
                                               @Nonnull List<PersistenceScope> scopes,
                                               boolean storageAuthorityLost) {
    public PersistenceFailureClassification {
        if (failureClass == null || disposition == null) throw new IllegalArgumentException("classification");
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
