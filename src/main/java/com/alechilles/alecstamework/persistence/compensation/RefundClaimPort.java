package com.alechilles.alecstamework.persistence.compensation;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Transaction-local authority for one-time item refund claims.
 *
 * <p>Implementations never perform inventory mutation. They only persist the deterministic claim
 * and the positive receipt returned by an idempotent live delivery boundary.</p>
 */
public interface RefundClaimPort {
    @Nonnull
    Optional<RefundClaim> findByOperation(@Nonnull OperationId operationId);

    @Nonnull
    Optional<RefundClaim> findByReceipt(@Nonnull String receiptKey);

    @Nonnull
    PersistenceMutationResult<RefundClaim> create(@Nonnull RefundClaim claim);

    @Nonnull
    PersistenceMutationResult<RefundClaim> complete(
            @Nonnull OperationId operationId,
            @Nonnull String receiptKey,
            @Nonnull String deliveryEvidence,
            long deliveredAtMs
    );
}
