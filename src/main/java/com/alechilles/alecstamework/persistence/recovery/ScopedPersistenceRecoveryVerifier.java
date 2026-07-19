package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import javax.annotation.Nonnull;

/** Domain-owned proof contract; implementations may observe authority but never force a resolution. */
public interface ScopedPersistenceRecoveryVerifier {
    @Nonnull
    PersistenceDomain domain();

    @Nonnull
    String verifierId();

    @Nonnull
    ScopedRecoveryVerification verify(@Nonnull ScopedRecoveryContext context) throws Exception;
}
