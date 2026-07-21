package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Narrow journal port used by the vessel coordinator. Production wiring uses the SQLite adapter;
 * tests can provide a deterministic journal without opening a database.
 */
public interface BondedVesselJournal {
    @Nullable
    BondedVesselBindingRecord findBinding(@Nonnull String bindingId) throws Exception;

    @Nullable
    BondedVesselBindingRecord findBindingByProfile(@Nonnull String profileId) throws Exception;

    @Nullable
    BondedVesselOperationRecord findOperation(@Nonnull String operationId) throws Exception;

    @Nullable
    BondedVesselOperationRecord findOperationByOrigin(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    ) throws Exception;

    @Nonnull
    List<BondedVesselOperationRecord> loadRecoverable(int limit) throws Exception;

    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> prepare(
            @Nonnull BondedVesselOperationRecord operation
    );

    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> claim(
            @Nonnull String operationId,
            long nowMs
    );

    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> apply(
            @Nonnull BondedVesselRepository.AppliedTransition transition
    );

    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> commit(
            @Nonnull String operationId,
            long nowMs
    );

    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> cancel(
            @Nonnull String operationId,
            @Nonnull String reason,
            long nowMs
    );

    /** Proves that a prepared/applying operation did not reach authoritative apply. */
    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> denyBeforeApply(
            @Nonnull String operationId,
            @Nonnull String reason,
            @Nonnull BondedVesselRepository.ApplyAbsenceProof proof,
            long nowMs
    );

    @Nonnull
    CompletionStage<BondedVesselRepository.MutationResult> quarantine(
            @Nonnull String operationId,
            @Nonnull String reason,
            long nowMs
    );
}
