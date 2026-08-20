package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.persistence.maintenance.LatestWorkCoordinator;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceDrainResult;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceMetricsSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
    private final LatestWorkCoordinator<UUID, CompanionEntityCheckpointCapture>
            coordinator;

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
        this.coordinator = new LatestWorkCoordinator<>(
                4,
                (alias, capture) -> persistWithWarning(alias, capture)
        );
    }

    @Override
    @Nonnull
    public CompletionStage<Void> publish(CompanionEntityCheckpointCapture capture) {
        if (capture == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (suppressed.test(capture.alias())) {
            return CompletableFuture.completedFuture(null);
        }
        return coordinator.submit(capture.alias().value(), capture);
    }

    /** Waits until the newest accepted checkpoint for one alias is durable. */
    @Nonnull
    public CompletionStage<Void> flush(@Nonnull NpcAlias alias) {
        return coordinator.flush(
                Objects.requireNonNull(alias, "alias").value()
        );
    }

    /** Returns bounded checkpoint admission evidence. */
    @Nonnull
    public MaintenanceMetricsSnapshot metrics() {
        return coordinator.metrics();
    }

    /** Stops admission and drains retained checkpoint work by the deadline. */
    @Nonnull
    public MaintenanceDrainResult shutdown(@Nonnull Duration timeout) {
        return coordinator.shutdown(Objects.requireNonNull(timeout, "timeout"));
    }

    private CompletionStage<Void> persistWithWarning(
            UUID alias,
            CompanionEntityCheckpointCapture capture
    ) {
        CompletionStage<Void> persistence;
        try {
            persistence = persist(capture, 0);
        } catch (Throwable failure) {
            warnFailure(alias, failure);
            return CompletableFuture.failedFuture(failure);
        }
        if (persistence == null) {
            NullPointerException failure = new NullPointerException(
                    "Checkpoint persistence returned no completion"
            );
            warnFailure(alias, failure);
            return CompletableFuture.failedFuture(failure);
        }
        return persistence.whenComplete((ignored, failure) -> {
            if (failure != null) {
                warnFailure(alias, failure);
            }
        });
    }

    private CompletionStage<Void> persist(
            CompanionEntityCheckpointCapture capture,
            int returnedIdentityAttempt
    ) {
        return persistence.queries().findProfile(capture.alias())
                .thenCompose(read -> {
                    if (read instanceof PersistenceReadResult.Failed<
                            CompanionProfileReadModel> failed) {
                        return failedStorage(failed.failure());
                    }
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
                });
    }

    private CompletionStage<Void> persistReturned(
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
                    if (read instanceof PersistenceReadResult.Failed<
                            CompanionAlias> failed) {
                        return failedStorage(failed.failure());
                    }
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
                });
    }

    private CompletionStage<Void> submitAndPublish(
            CompanionEntityCheckpoint checkpoint
    ) {
        ProfileExtensionKey extensionKey = key(checkpoint);
        return currentCheckpoint(extensionKey).thenCompose(current -> {
            if (current != null && equivalent(current, checkpoint)) {
                return CompletableFuture.completedFuture(null);
            }
            return submit(checkpoint).thenRun(() -> published.accept(checkpoint));
        });
    }

    private CompletionStage<Void> submit(
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
        });
    }

    private CompletionStage<CompanionEntityCheckpoint> currentCheckpoint(
            ProfileExtensionKey key
    ) {
        var projected = persistence.queries().projectedExtension(key);
        if (projected.isPresent()) {
            return decode(projected.get().jsonPayload());
        }
        return persistence.queries().findExtension(key).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<
                    ProfileExtensionData> failed) {
                return failedStorage(failed.failure());
            }
            if (!(read instanceof PersistenceReadResult.Found<
                    ProfileExtensionData> found)) {
                return CompletableFuture.completedFuture(null);
            }
            return decode(found.value().jsonPayload());
        });
    }

    private CompletionStage<CompanionEntityCheckpoint> decode(String payload) {
        try {
            return CompletableFuture.completedFuture(codec.decode(payload));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static boolean equivalent(
            CompanionEntityCheckpoint current,
            CompanionEntityCheckpoint candidate
    ) {
        return current.profileId().equals(candidate.profileId())
                && current.alias().equals(candidate.alias())
                && current.sourceAlias().equals(candidate.sourceAlias())
                && current.aliasGeneration() == candidate.aliasGeneration()
                && current.ownerId().equals(candidate.ownerId())
                && current.lifecycleRevision().equals(
                        candidate.lifecycleRevision()
                )
                && current.reconciliationGeneration().equals(
                        candidate.reconciliationGeneration()
                )
                && current.worldKey().equals(candidate.worldKey())
                && Double.compare(current.x(), candidate.x()) == 0
                && Double.compare(current.y(), candidate.y()) == 0
                && Double.compare(current.z(), candidate.z()) == 0
                && current.holder().equals(candidate.holder())
                && equivalentBoundary(current.boundary(), candidate.boundary());
    }

    private static boolean equivalentBoundary(
            CompanionEntityCheckpoint.CaptureBoundary current,
            CompanionEntityCheckpoint.CaptureBoundary candidate
    ) {
        return candidate == CompanionEntityCheckpoint.CaptureBoundary.LOADED
                ? current == CompanionEntityCheckpoint.CaptureBoundary.LOADED
                || current == CompanionEntityCheckpoint.CaptureBoundary.UNLOAD
                : current == candidate;
    }

    private static <T> CompletionStage<T> failedStorage(
            StorageFailure failure
    ) {
        Throwable cause = failure.cause();
        return CompletableFuture.failedFuture(
                new IllegalStateException(failure.code(), cause)
        );
    }

    private void warnFailure(UUID alias, Throwable failure) {
        Throwable unwrapped = unwrap(failure);
        String message = unwrapped.getMessage();
        if (message == null || message.isBlank()) {
            message = unwrapped.getClass().getSimpleName();
        }
        warnings.accept(
                "Companion checkpoint persistence failed for npc="
                        + alias + ": " + message
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
