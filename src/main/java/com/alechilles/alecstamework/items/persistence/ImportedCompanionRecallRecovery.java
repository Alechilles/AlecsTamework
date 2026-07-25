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
 * Converts the exact public released-coop backup after an explicit recall fails.
 *
 * <p>Normal missing or unloaded companions are ignored. Eligibility requires
 * the importer-only snapshot kind plus matching canonical owner, role, alias,
 * lifecycle revision, payload hash, and a successfully decoded full state.</p>
 */
public final class ImportedCompanionRecallRecovery
        implements ImportedRecallRecoverySink {
    private static final String IDEMPOTENCY_PREFIX =
            "imported-recall-recovery-v1:";

    private final PersistenceDomainFacades persistence;
    private final HytaleLogger logger;
    private final ImportedRecallRecoveryAuthor author =
            new ImportedRecallRecoveryAuthor();

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
    public void recover(RecallFailure failure) {
        CompletionStage<Boolean> stage;
        try {
            stage = tryRecover(failure);
        } catch (RuntimeException rejected) {
            warn(failure, rejected);
            return;
        }
        stage.whenComplete((recovered, problem) -> {
            if (problem != null) {
                warn(failure, problem);
            } else if (Boolean.TRUE.equals(recovered)) {
                logger.at(Level.INFO).log(
                        "Recovered imported companion %s to Lost after its "
                                + "explicit recall exhausted relocation.",
                        failure.npcUuid()
                );
            }
        });
    }

    CompletionStage<Boolean> tryRecover(RecallFailure failure) {
        Objects.requireNonNull(failure, "failure");
        NpcAlias alias = new NpcAlias(failure.npcUuid());
        return persistence.queries().findProfile(alias).thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> found)) {
                return CompletableFuture.completedFuture(false);
            }
            CompanionProfileMutation.RecoverImportedMissing recovery =
                    author.author(found.value(), failure);
            if (recovery == null) {
                return CompletableFuture.completedFuture(false);
            }
            OperationIdentity identity = operationIdentity(recovery);
            PublicOperationSubmission submission =
                    persistence.operations().mutateProfile(
                            identity.operationId(),
                            identity.idempotencyKey(),
                            recovery
                    );
            if (!submission.accepted()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "imported_recall_recovery_submission_"
                                        + submission.admission().name()
                                        .toLowerCase()
                        )
                );
            }
            return submission.completion().thenApply(result -> {
                if (result.status()
                        != OperationWorkflowResult.Status.PUBLISHED) {
                    throw new IllegalStateException(
                            "imported_recall_recovery_"
                                    + result.status().name().toLowerCase(),
                            result.failure()
                    );
                }
                return true;
            });
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
                "Imported companion recovery failed after recall for npc=%s",
                failure.npcUuid()
        );
    }

    private record OperationIdentity(
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
    }
}
