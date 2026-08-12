package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
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
 * Makes an exact missing unloaded companion restorable after Recall fails.
 *
 * <p>An importer-authored complete backup remains the preferred source. When
 * none exists, the fallback writes a complete Lost snapshot from fenced
 * durable profile facts. The dormant operation retires the old alias before
 * normal restoration creates a distinct replacement alias.</p>
 */
public final class ImportedCompanionRecallRecovery
        implements ImportedRecallRecoverySink {
    private static final String IDEMPOTENCY_PREFIX =
            "imported-recall-recovery-v1:";

    private final PersistenceDomainFacades persistence;
    private final HytaleLogger logger;
    private final ImportedRecallRecoveryAuthor author =
            new ImportedRecallRecoveryAuthor();
    private final MissingUnloadedRecallRecoveryAuthor missingAuthor =
            new MissingUnloadedRecallRecoveryAuthor();
    private final MissingActiveRecallReconciliationAuthor activeAuthor =
            new MissingActiveRecallReconciliationAuthor();

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
            } else if (outcome == RecoveryOutcome.RETRY_REQUIRED) {
                logger.at(Level.INFO).log(
                        "Reconciled stale active companion %s to Unloaded; "
                                + "starting a second recall window.",
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
            CompanionProfileMutation.RecoverImportedMissing recovery =
                    author.author(found.value(), failure);
            if (recovery != null) {
                return submitImported(recovery).thenApply(
                        ignored -> RecoveryOutcome.RECOVERED
                );
            }
            CompanionDormantTransitionRequest missing = missingAuthor.author(
                    found.value(), failure
            );
            if (missing != null) {
                return submitMissing(missing).thenApply(
                        ignored -> RecoveryOutcome.RECOVERED
                );
            }
            CompanionProfileMutation.ReconcileMissingActive active =
                    activeAuthor.author(found.value(), failure);
            return active == null
                    ? CompletableFuture.completedFuture(RecoveryOutcome.NONE)
                    : submitActive(active).thenApply(
                            ignored -> RecoveryOutcome.RETRY_REQUIRED
                    );
        });
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

    private CompletionStage<Boolean> submitMissing(
            CompanionDormantTransitionRequest recovery
    ) {
        String payload = CompanionDormantTransitionDefinition.INSTANCE.encode(
                recovery
        );
        String material = "missing-unloaded-recall-operation:v1:" + payload;
        OperationIdentity identity = new OperationIdentity(
                new OperationId(UUID.nameUUIDFromBytes(
                        material.getBytes(StandardCharsets.UTF_8)
                )),
                new IdempotencyKey(
                        "missing-unloaded-recall-operation:v1:"
                                + Sha256Hash.ofUtf8(material)
                )
        );
        PublicOperationSubmission submission =
                persistence.operations().makeDormant(
                        identity.operationId(),
                        identity.idempotencyKey(),
                        recovery
                );
        return completion(submission, "missing_unloaded_recall_recovery");
    }

    private CompletionStage<Boolean> submitActive(
            CompanionProfileMutation.ReconcileMissingActive recovery
    ) {
        String payload = CompanionProfileMutationDefinition.INSTANCE.encode(
                recovery
        );
        String material = "missing-active-recall-operation:v1:" + payload;
        OperationIdentity identity = new OperationIdentity(
                new OperationId(UUID.nameUUIDFromBytes(
                        material.getBytes(StandardCharsets.UTF_8)
                )),
                new IdempotencyKey(
                        "missing-active-recall-operation:v1:"
                                + Sha256Hash.ofUtf8(material)
                )
        );
        PublicOperationSubmission submission =
                persistence.operations().mutateProfile(
                        identity.operationId(),
                        identity.idempotencyKey(),
                        recovery
                );
        return completion(submission, "missing_active_recall_reconciliation");
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
}
