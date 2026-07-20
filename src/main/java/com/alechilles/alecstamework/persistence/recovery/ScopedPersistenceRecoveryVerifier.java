package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import javax.annotation.Nonnull;

/** Domain-owned proof contract; implementations may observe authority but never force a resolution. */
public interface ScopedPersistenceRecoveryVerifier {
    @Nonnull
    PersistenceDomain domain();

    @Nonnull
    String verifierId();

    /** Allows one domain to register separate proofs for distinct failure classes. */
    default boolean supports(@Nonnull PersistenceFailureClass failureClass) {
        return true;
    }

    @Nonnull
    ScopedRecoveryVerification verify(@Nonnull ScopedRecoveryContext context) throws Exception;
}
