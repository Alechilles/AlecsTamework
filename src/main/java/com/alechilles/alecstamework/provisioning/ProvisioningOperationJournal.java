package com.alechilles.alecstamework.provisioning;

import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningRepository;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable, compare-and-transition journal used by the provisioning coordinator. */
public interface ProvisioningOperationJournal {
    @Nonnull
    CompletionStage<CompanionProvisioningRepository.MutationResult> create(
            @Nonnull CompanionProvisioningOperationRecord operation);

    @Nonnull
    CompletionStage<CompanionProvisioningRepository.MutationResult> advance(
            @Nonnull CompanionProvisioningRepository.AdvanceMutation mutation);

    @Nullable
    CompanionProvisioningOperationRecord find(@Nonnull String operationId) throws Exception;

    @Nullable
    CompanionProvisioningOperationRecord findByOrigin(
            @Nonnull String callerNamespace, @Nonnull String idempotencyKey) throws Exception;

    @Nullable
    CompanionProvisioningOperationRecord findByProfile(@Nonnull String profileId) throws Exception;

    @Nonnull
    List<CompanionProvisioningOperationRecord> loadRecoverable(int limit) throws Exception;
}
