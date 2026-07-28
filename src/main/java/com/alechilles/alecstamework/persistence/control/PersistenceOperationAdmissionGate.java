package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import javax.annotation.Nonnull;

/** The one admission seam consulted before a public operation is prepared. */
@FunctionalInterface
public interface PersistenceOperationAdmissionGate {
    /** Rejects the operation synchronously unless it may enter the writer queue. */
    void requireAdmission(
            @Nonnull OperationKind kind,
            @Nonnull String featureScope,
            @Nonnull List<OperationScope> participants
    );

    /** Unrestricted gate reserved for startup recovery and focused adapter tests. */
    static PersistenceOperationAdmissionGate allowAll() {
        return (kind, featureScope, participants) -> {
        };
    }
}
