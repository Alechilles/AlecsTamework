package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePersistenceTransactionContext;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Typed detail that must commit atomically with an operation's durable preparation.
 *
 * <p>Implementations may use only the supplied transaction context. Verification must accept
 * later valid evolution of its evidence, such as a leased alias becoming current.</p>
 */
public interface PreparedOperationDetail {
    /** Writes idempotent preparation evidence inside the operation preparation transaction. */
    void prepare(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) throws Exception;

    /** Proves the exact operation owns its required preparation evidence. */
    boolean matches(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) throws Exception;

    /** Returns a no-detail contract for ordinary database-only operations. */
    @Nonnull
    static PreparedOperationDetail none() {
        return NoDetail.INSTANCE;
    }

    /** Composes ordered preparation participants into the same shared transaction. */
    @Nonnull
    static PreparedOperationDetail compose(
            @Nonnull PreparedOperationDetail... details
    ) {
        if (details == null || Arrays.stream(details).anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Prepared operation details cannot be null"
            );
        }
        List<PreparedOperationDetail> active = Arrays.stream(details)
                .filter(detail -> detail != NoDetail.INSTANCE)
                .toList();
        return switch (active.size()) {
            case 0 -> NoDetail.INSTANCE;
            case 1 -> active.getFirst();
            default -> new CompositeDetail(active);
        };
    }

    /** Ordered composite with no lifecycle or recovery state of its own. */
    record CompositeDetail(
            @Nonnull List<PreparedOperationDetail> details
    ) implements PreparedOperationDetail {
        public CompositeDetail {
            if (details == null || details.isEmpty()
                    || details.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Composite preparation details are required"
                );
            }
            details = List.copyOf(details);
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) throws Exception {
            for (PreparedOperationDetail detail : details) {
                if (!detail.matches(transaction, operation)) {
                    detail.prepare(transaction, operation);
                }
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) throws Exception {
            for (PreparedOperationDetail detail : details) {
                if (!detail.matches(transaction, operation)) {
                    return false;
                }
            }
            return true;
        }
    }

    enum NoDetail implements PreparedOperationDetail {
        INSTANCE;

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            return true;
        }
    }
}
