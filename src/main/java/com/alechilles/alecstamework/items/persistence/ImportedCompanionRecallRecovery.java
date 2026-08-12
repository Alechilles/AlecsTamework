package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Recovers an exact imported companion after Recall fails.
 *
 * <p>A Recall timeout or a missing live UUID is not proof of permanent
 * removal. This service therefore accepts only the importer's complete,
 * single-use recovery artifact. Ordinary companions remain unchanged until
 * exact source or checkpoint evidence is available.</p>
 */
public final class ImportedCompanionRecallRecovery
        implements ImportedRecallRecoverySink {
    private static final String IDEMPOTENCY_PREFIX =
            "imported-recall-recovery-v1:";

    private final PersistenceDomainFacades persistence;
    private final HytaleLogger logger;

    public ImportedCompanionRecallRecovery(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull HytaleLogger logger
    ) {
        this.persistence = Objects.requireNonNull(
                persistence,
                "persistence"
        );
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletionStage<RecoveryOutcome> recover(RecallFailure failure) {
        CompletionStage<RecoveryOutcome> stage;
        try {
            stage = tryRecover(failure);
        } catch (RuntimeException rejected) {
            warn(failure, rejected);
            return CompletableFuture.failedFuture(rejected);
        }
        return stage.whenComplete((outcome, problem) -> {
            if (problem != null) {
                warn(failure, problem);
            } else if (outcome == RecoveryOutcome.RECOVERED) {
                logger.at(Level.INFO).log(
                        "Recovered companion %s to Lost after its "
                                + "explicit recall exhausted relocation.",
                        failure.npcUuid()
                );
            }
        });
    }

    CompletionStage<RecoveryOutcome> tryRecover(RecallFailure failure) {
        Objects.requireNonNull(failure, "failure");
        NpcAlias alias = new NpcAlias(failure.npcUuid());
        return persistence.queries().findProfile(alias).thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found)) {
                return CompletableFuture.completedFuture(
                        RecoveryOutcome.NONE
                );
            }
            return recoverFoundProfile(
                    found.value(), failure, this::submitImported
            );
        });
    }

    static CompletionStage<RecoveryOutcome> recoverFoundProfile(
            CompanionProfileReadModel profile,
            RecallFailure failure,
            ImportedRecoverySubmitter submitter
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(submitter, "submitter");
        CompanionProfileMutation.RecoverImportedMissing recovery =
                new ImportedRecallRecoveryAuthor().author(profile, failure);
        if (recovery == null) {
            return CompletableFuture.completedFuture(RecoveryOutcome.NONE);
        }
        return submitter.submit(recovery).thenApply(
                ignored -> RecoveryOutcome.RECOVERED
        );
    }

    private CompletionStage<Boolean> submitImported(
            CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        OperationIdentity identity = operationIdentity(recovery);
        PublicOperationSubmission submission =
                persistence.operations().mutateProfile(
                        identity.operationId(),
                        identity.idempotencyKey(),
                        recovery
                );
        return completion(submission, "imported_recall_recovery");
    }

    private CompletionStage<Boolean> completion(
            PublicOperationSubmission submission,
            String code
    ) {
        if (!submission.accepted()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            code + "_submission_"
                                    + submission.admission().name()
                                    .toLowerCase()
                    )
            );
        }
        return submission.completion().thenApply(result -> {
            if (result.status()
                    != OperationWorkflowResult.Status.PUBLISHED) {
                throw new IllegalStateException(
                        code + "_"
                                + result.status().name().toLowerCase(),
                        result.failure()
                );
            }
            return true;
        });
    }

    private OperationIdentity operationIdentity(
            CompanionProfileMutation.RecoverImportedMissing recovery
    ) {
        String payload = CompanionProfileMutationDefinition.INSTANCE.encode(
                recovery
        );
        String material = IDEMPOTENCY_PREFIX + payload;
        return new OperationIdentity(
                new OperationId(UUID.nameUUIDFromBytes(
                        material.getBytes(StandardCharsets.UTF_8)
                )),
                new IdempotencyKey(
                        IDEMPOTENCY_PREFIX + Sha256Hash.ofUtf8(material)
                )
        );
    }

    private void warn(RecallFailure failure, Throwable problem) {
        logger.at(Level.WARNING).withCause(problem).log(
                "Companion recovery failed after recall for npc=%s",
                failure.npcUuid()
        );
    }

    private record OperationIdentity(
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
    }

    @FunctionalInterface
    interface ImportedRecoverySubmitter {
        CompletionStage<Boolean> submit(
                CompanionProfileMutation.RecoverImportedMissing recovery
        );
    }
}
