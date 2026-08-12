package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/** Persists exact entity checkpoints through canonical extension operations. */
public final class ReplacementCompanionEntityCheckpointSink
        implements CompanionEntityCheckpointSink {
    public static final String NAMESPACE =
            "Alechilles:Tamework:EntityCheckpoint";
    private static final String KEY_PREFIX = "alias:";
    private static final int RETURNED_IDENTITY_RETRIES = 120;

    private final PersistenceDomainFacades persistence;
    private final Consumer<String> warnings;
    private final CompanionEntityCheckpointCodec codec =
            new CompanionEntityCheckpointCodec();
    private final CompanionEntityCheckpointAuthor author =
            new CompanionEntityCheckpointAuthor(codec);
    private final ReturnedOriginalCheckpointAuthor returnedAuthor =
            new ReturnedOriginalCheckpointAuthor(codec);
    private final LoadedNpcIdentityIndex identities;
    private final Consumer<CompanionEntityCheckpoint> published;
    private final Predicate<NpcAlias> suppressed;
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> chains =
            new ConcurrentHashMap<>();

    public ReplacementCompanionEntityCheckpointSink(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull Consumer<String> warnings
    ) {
        this(
                persistence, warnings, null,
                ignored -> { }, ignored -> false
        );
    }

    public ReplacementCompanionEntityCheckpointSink(
            @Nonnull PersistenceDomainFacades persistence,
            @Nonnull Consumer<String> warnings,
            LoadedNpcIdentityIndex identities,
            @Nonnull Consumer<CompanionEntityCheckpoint> published,
            @Nonnull Predicate<NpcAlias> suppressed
    ) {
        this.persistence = Objects.requireNonNull(
                persistence, "persistence"
        );
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.identities = identities;
        this.published = Objects.requireNonNull(published, "published");
        this.suppressed = Objects.requireNonNull(suppressed, "suppressed");
    }

    @Override
    public void publish(CompanionEntityCheckpointCapture capture) {
        if (capture == null) {
            return;
        }
        if (suppressed.test(capture.alias())) {
            return;
        }
        UUID alias = capture.alias().value();
        chains.compute(alias, (ignored, previous) -> {
            CompletableFuture<Void> base = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((value, failure) -> null);
            CompletableFuture<Void> next = base.thenCompose(
                    unused -> persist(capture, 0)
            );
            next.whenComplete((value, failure) -> {
                chains.remove(alias, next);
                if (failure != null) {
                    warnings.accept(
                            "Companion checkpoint persistence failed for npc="
                                    + alias + ": "
                                    + failure.getClass().getSimpleName()
                    );
                }
            });
            return next;
        });
    }

    private CompletableFuture<Void> persist(
            CompanionEntityCheckpointCapture capture,
            int returnedIdentityAttempt
    ) {
        return persistence.queries().findProfile(capture.alias())
                .thenCompose(read -> {
                    if (!(read instanceof PersistenceReadResult.Found<
                            CompanionProfileReadModel> found)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    CompanionEntityCheckpoint checkpoint = author.author(
                            found.value(), capture
                    );
                    if (checkpoint != null) {
                        return submitAndPublish(checkpoint);
                    }
                    return persistReturned(
                            found.value(), capture, returnedIdentityAttempt
                    );
                }).toCompletableFuture();
    }

    private CompletableFuture<Void> persistReturned(
            CompanionProfileReadModel profile,
            CompanionEntityCheckpointCapture capture,
            int returnedIdentityAttempt
    ) {
        if (identities == null || profile.currentAlias() == null) {
            return CompletableFuture.completedFuture(null);
        }
        LoadedNpcIdentityIndex.ProbeStatus currentStatus = identities.probe(
                profile.currentAlias().alias().value()
        ).status();
        if (currentStatus == LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN) {
            if (returnedIdentityAttempt >= RETURNED_IDENTITY_RETRIES) {
                warnings.accept(
                        "Returned companion reconciliation timed out waiting "
                                + "for complete loaded identity evidence: npc="
                                + capture.alias()
                );
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.runAsync(
                    () -> { },
                    CompletableFuture.delayedExecutor(
                            500, TimeUnit.MILLISECONDS
                    )
            ).thenCompose(ignored -> persist(
                    capture, returnedIdentityAttempt + 1
            ));
        }
        boolean currentSafeToReplace = currentStatus
                == LoadedNpcIdentityIndex.ProbeStatus.ABSENT
                || currentStatus
                == LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION;
        if (!currentSafeToReplace) {
            return CompletableFuture.completedFuture(null);
        }
        return persistence.queries().findAlias(capture.alias())
                .thenCompose(read -> {
                    if (!(read instanceof PersistenceReadResult.Found<
                            CompanionAlias> found)) {
                        return CompletableFuture.completedFuture(null);
                    }
                    CompanionEntityCheckpoint checkpoint =
                            returnedAuthor.author(
                                    profile,
                                    found.value(),
                                    capture,
                                    true
                            );
                    return checkpoint == null
                            ? CompletableFuture.completedFuture(null)
                            : submitAndPublish(checkpoint);
                }).toCompletableFuture();
    }

    private CompletableFuture<Void> submitAndPublish(
            CompanionEntityCheckpoint checkpoint
    ) {
        return submit(checkpoint).thenRun(() -> published.accept(checkpoint));
    }

    private CompletableFuture<Void> submit(
            CompanionEntityCheckpoint checkpoint
    ) {
        String payload = codec.encode(checkpoint);
        String material = "companion-entity-checkpoint:v1:"
                + checkpoint.profileId() + ':'
                + checkpoint.alias() + ':'
                + checkpoint.payloadHash();
        ProfileExtensionMutation mutation = new ProfileExtensionMutation(
                key(checkpoint),
                ProfileExtensionMutationAction.PUT,
                null,
                payload,
                checkpoint.capturedAtMs()
        );
        PublicOperationSubmission submission =
                persistence.operations().mutateExtension(
                        new OperationId(UUID.nameUUIDFromBytes(
                                material.getBytes(StandardCharsets.UTF_8)
                        )),
                        new IdempotencyKey(
                                "companion-entity-checkpoint:v1:"
                                        + Sha256Hash.ofUtf8(material)
                        ),
                        mutation
                );
        if (!submission.accepted()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "checkpoint_submission_"
                                    + submission.admission().name()
                    )
            );
        }
        return submission.completion().thenAccept(result -> {
            if (result.status()
                    != OperationWorkflowResult.Status.PUBLISHED) {
                throw new IllegalStateException(
                        "checkpoint_" + result.status().name(),
                        result.failure()
                );
            }
        }).toCompletableFuture();
    }

    /** Returns the stable extension key for one alias checkpoint. */
    @Nonnull
    public static ProfileExtensionKey key(
            @Nonnull CompanionEntityCheckpoint checkpoint
    ) {
        return key(checkpoint.profileId(), checkpoint.alias());
    }

    /** Returns the stable extension key before a checkpoint is decoded. */
    @Nonnull
    public static ProfileExtensionKey key(
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias alias
    ) {
        return new ProfileExtensionKey(
                Objects.requireNonNull(profileId, "profileId"),
                NAMESPACE,
                KEY_PREFIX + Objects.requireNonNull(alias, "alias")
        );
    }
}
