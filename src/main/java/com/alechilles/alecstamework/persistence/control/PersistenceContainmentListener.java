package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import javax.annotation.Nonnull;

/** Post-commit notification that exact durable scopes are quarantined. */
@FunctionalInterface
public interface PersistenceContainmentListener {
    PersistenceContainmentListener NO_OP = (scopes, reasonCode) -> {
    };

    /**
     * Updates live admission only after durable containment or exact readback
     * proves the same result.
     */
    void contained(
            @Nonnull List<OperationScope> scopes,
            @Nonnull String reasonCode
    );
}
