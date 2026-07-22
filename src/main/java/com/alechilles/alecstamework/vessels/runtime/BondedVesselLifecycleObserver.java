package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselBindingInvalidatedEvent;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselStateChangedEvent;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.config.assets.TwSpawnerVesselConfigResolver;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.vessels.BondedVesselEventSink;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts command-link-independent canonical death/lost observations into generation-fenced
 * vessel operations. The binding transition commits even when the item is offline or missing;
 * exact item rewriting is best-effort evidence repair and never gates lifecycle authority.
 */
public final class BondedVesselLifecycleObserver {
    private static final String CALLER = "tamework:bonded-lifecycle";
    private final BondedVesselRepository repository;
    private final ConfigResolver configs;
    private final BondedVesselEvidenceAuthority evidence;
    private final BondedVesselEventSink events;
    private final Executor executor;
    private final LongSupplier clock;
    private final BondedVesselItemFingerprintCodec fingerprints =
            new BondedVesselItemFingerprintCodec();
    private final Gson gson = new Gson();
    private final Set<String> emitted = ConcurrentHashMap.newKeySet();

    public BondedVesselLifecycleObserver(
            @Nonnull BondedVesselRepository repository,
            @Nonnull TwSpawnerVesselConfigResolver configs,
            @Nonnull BondedVesselEvidenceAuthority evidence,
            @Nullable BondedVesselEventSink events,
            @Nonnull Executor executor,
            @Nonnull LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        TwSpawnerVesselConfigResolver requiredConfigs = Objects.requireNonNull(configs, "configs");
        this.configs = requiredConfigs::resolve;
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.events = events == null ? BondedVesselEventSink.NO_OP : events;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BondedVesselLifecycleObserver(
            @Nonnull BondedVesselRepository repository,
            @Nonnull ConfigResolver configs,
            @Nonnull BondedVesselEvidenceAuthority evidence,
            @Nullable BondedVesselEventSink events,
            @Nonnull Executor executor,
            @Nonnull LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.events = events == null ? BondedVesselEventSink.NO_OP : events;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    public CompletionStage<Result> observe(@Nonnull Observation observation) {
        Objects.requireNonNull(observation, "observation");
        return CompletableFuture.supplyAsync(() -> load(observation), executor)
                .thenCompose(loaded -> continueLoaded(observation, loaded))
                .exceptionally(failure -> Result.indeterminate(
                        "bonded-lifecycle-observer-failed", observation.profileId()));
    }

    /**
     * Repairs item projections that an atomic population transition committed while the owner
     * was offline. This is intentionally owner-scoped so exact inventory evidence is available.
     */
    @Nonnull
    public CompletionStage<RecoveryReport> recoverPendingForOwner(@Nonnull UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return repository.loadNonReleasedBindings().stream()
                        .filter(binding -> ownerUuid.equals(binding.ownerUuid()))
                        .filter(binding -> binding.itemProjectionStatus()
                                == BondedVesselBindingRecord.ItemProjectionStatus.REISSUE_PENDING)
                        .filter(binding -> binding.lifecycleState()
                                == BondedVesselBindingRecord.LifecycleState.STORED
                                || binding.lifecycleState()
                                == BondedVesselBindingRecord.LifecycleState.DEAD
                                || binding.lifecycleState()
                                == BondedVesselBindingRecord.LifecycleState.LOST)
                        .limit(128L)
                        .toList();
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "Pending bonded-vessel item projection lookup failed", failure);
            }
        }, executor).thenCompose(bindings -> recoverNext(bindings, 0,
                new RecoveryReport(bindings.size(), 0, 0)))
                .exceptionally(failure -> new RecoveryReport(0, 0, 1));
    }

    private CompletionStage<RecoveryReport> recoverNext(
            List<BondedVesselBindingRecord> bindings,
            int index,
            RecoveryReport report) {
        if (index >= bindings.size()) return CompletableFuture.completedFuture(report);
        BondedVesselBindingRecord binding = bindings.get(index);
        BondedVesselState target = BondedVesselState.valueOf(binding.lifecycleState().name());
        UUID removedNpcUuid = UUID.nameUUIDFromBytes(("bonded-projection-recovery:"
                + binding.bindingId() + ":" + binding.generation())
                .getBytes(StandardCharsets.UTF_8));
        Observation observation = new Observation(
                binding.profileId(), removedNpcUuid, binding.expectedProfileRevision(), target,
                "bonded-item-projection-recovered-on-owner-join", "owner-join-recovery");
        return finishCommittedProjection(observation, binding)
                .handle((result, failure) -> failure == null && result != null
                        && (result.status() == Status.COMMITTED
                        || result.status() == Status.IDEMPOTENT)
                        ? report.completedOne() : report.failedOne())
                .thenCompose(next -> recoverNext(bindings, index + 1, next));
    }

    private Loaded load(Observation observation) {
        try {
            String key = idempotencyKey(observation);
            return new Loaded(repository.findBindingByProfile(observation.profileId()),
                    repository.findOperationByCallerKey(CALLER, key));
        } catch (Exception failure) {
            throw new IllegalStateException("Bonded lifecycle lookup failed", failure);
        }
    }

    private CompletionStage<Result> continueLoaded(Observation observation, Loaded loaded) {
        if (loaded.existing() != null) {
            return continueExisting(observation, loaded.existing(), true);
        }
        BondedVesselBindingRecord binding = loaded.binding();
        if (binding == null) return CompletableFuture.completedFuture(Result.skipped(
                "bonded-binding-not-found", observation.profileId()));
        BondedVesselBindingRecord.LifecycleState target = targetLifecycle(observation.target());
        if (binding.lifecycleState() == target
                && binding.expectedProfileRevision() >= observation.committedProfileRevision()) {
            if (binding.itemProjectionStatus()
                    == BondedVesselBindingRecord.ItemProjectionStatus.REISSUE_PENDING) {
                return finishCommittedProjection(observation, binding);
            }
            return CompletableFuture.completedFuture(Result.idempotent(
                    "bonded-lifecycle-already-observed", observation.profileId()));
        }
        if (binding.lifecycleState() != BondedVesselBindingRecord.LifecycleState.ACTIVE
                || !Objects.equals(binding.activeNpcUuid(), observation.removedNpcUuid())) {
            return CompletableFuture.completedFuture(Result.skipped(
                    "bonded-lifecycle-source-no-longer-active", observation.profileId()));
        }
        SpawnerVesselConfigView config = configs.resolve(
                binding.configId(), binding.configRevision()).orElse(null);
        if (config == null) return CompletableFuture.completedFuture(Result.indeterminate(
                "bonded-lifecycle-config-revision-unavailable", observation.profileId()));
        BondedVesselOperationRecord operation = operation(observation, binding, config);
        return submit(repository.prepareTransitionAsync(operation))
                .thenCompose(prepared -> {
                    if (!accepted(prepared, BondedVesselRepository.Status.PREPARED)) {
                        return CompletableFuture.completedFuture(
                                fromMutation(prepared, observation.profileId()));
                    }
                    return claimAndApply(observation, binding, operation);
                });
    }

    private CompletionStage<Result> claimAndApply(
            Observation observation,
            BondedVesselBindingRecord binding,
            BondedVesselOperationRecord operation) {
        return submit(repository.claimForApplyAsync(operation.operationId(), now()))
                .thenCompose(claimed -> {
                    if (claimed == null) return CompletableFuture.completedFuture(Result.indeterminate(
                            "bonded-lifecycle-claim-unconfirmed", observation.profileId()));
                    if (claimed.operation() != null
                            && claimed.operation().state()
                            == BondedVesselOperationRecord.State.APPLIED) {
                        return finishApplied(observation, claimed.operation(), false);
                    }
                    if (!accepted(claimed, BondedVesselRepository.Status.APPLYING)) {
                        return CompletableFuture.completedFuture(
                                fromMutation(claimed, observation.profileId()));
                    }
                    BondedVesselRepository.AppliedTransition applied =
                            new BondedVesselRepository.AppliedTransition(
                                    operation.operationId(), observation.committedProfileRevision(),
                                    null, null, binding.itemEvidenceJson(), observation.reason(), now());
                    return submit(repository.applyAsync(applied)).thenCompose(mutation -> {
                        if (!accepted(mutation, BondedVesselRepository.Status.APPLIED)) {
                            return CompletableFuture.completedFuture(
                                    fromMutation(mutation, observation.profileId()));
                        }
                        return finishApplied(observation, mutation.operation(), false);
                    });
                });
    }

    private CompletionStage<Result> continueExisting(
            Observation observation, BondedVesselOperationRecord operation, boolean recovered) {
        if (!matches(observation, operation)) return CompletableFuture.completedFuture(
                Result.skipped("bonded-lifecycle-idempotency-conflict", observation.profileId()));
        return switch (operation.state()) {
            case COMMITTED -> {
                emit(operation, recovered);
                yield CompletableFuture.completedFuture(Result.idempotent(
                        "bonded-lifecycle-already-committed", observation.profileId()));
            }
            case APPLIED -> finishApplied(observation, operation, recovered);
            case PREPARED, APPLYING -> CompletableFuture.completedFuture(Result.indeterminate(
                    "bonded-lifecycle-operation-in-flight", observation.profileId()));
            case QUARANTINED -> CompletableFuture.completedFuture(Result.quarantined(
                    reason(operation, "bonded-lifecycle-quarantined"), observation.profileId()));
            default -> CompletableFuture.completedFuture(Result.skipped(
                    reason(operation, "bonded-lifecycle-operation-closed"), observation.profileId()));
        };
    }

    private CompletionStage<Result> finishApplied(
            Observation observation,
            BondedVesselOperationRecord operation,
            boolean recovered) {
        BondedVesselTransitionContext context = sourceContext(operation);
        CompletionStage<BondedVesselEvidenceAuthority.SourceFinalization> stage;
        try {
            stage = context == null ? null : evidence.finalizeSource(operation, context);
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        CompletionStage<BondedVesselEvidenceAuthority.SourceFinalization> safe = stage == null
                ? CompletableFuture.completedFuture(null) : stage;
        return safe.thenCompose(finalization -> {
            boolean present = finalization != null
                    && (finalization.status()
                    == BondedVesselEvidenceAuthority.FinalizationStatus.FINALIZED
                    || finalization.status()
                    == BondedVesselEvidenceAuthority.FinalizationStatus.ALREADY_FINALIZED);
            String itemEvidence = present ? finalization.itemEvidenceJson() : null;
            String itemReason = present ? finalization.reason()
                    : finalization == null ? "bonded-lifecycle-item-evidence-missing"
                    : finalization.reason();
            return submit(repository.finalizeAppliedItemProjectionAsync(
                    operation.operationId(), present
                            ? BondedVesselBindingRecord.ItemProjectionStatus.PRESENT
                            : BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                    itemEvidence, itemReason, now()));
        }).thenCompose(projected -> {
            if (!accepted(projected, BondedVesselRepository.Status.APPLIED)) {
                return CompletableFuture.completedFuture(fromMutation(
                        projected, observation.profileId()));
            }
            return submit(repository.commitAsync(operation.operationId(), now()))
                    .thenApply(committed -> {
                        if (!accepted(committed, BondedVesselRepository.Status.COMMITTED)) {
                            return fromMutation(committed, observation.profileId());
                        }
                        BondedVesselOperationRecord durable = committed.operation() == null
                                ? operation : committed.operation();
                        emit(durable, recovered);
                        return Result.committed(
                                "bonded-lifecycle-committed", observation.profileId());
                    });
        });
    }

    /** Finishes the item half of a population transaction that already advanced lifecycle. */
    private CompletionStage<Result> finishCommittedProjection(
            Observation observation,
            BondedVesselBindingRecord binding) {
        SpawnerVesselConfigView config = configs.resolve(
                binding.configId(), binding.configRevision()).orElse(null);
        if (config == null) return CompletableFuture.completedFuture(Result.indeterminate(
                "bonded-lifecycle-config-revision-unavailable", observation.profileId()));
        BondedVesselOperationRecord projection = projectionOperation(observation, binding, config);
        BondedVesselTransitionContext context = sourceContext(binding);
        CompletionStage<BondedVesselEvidenceAuthority.SourceFinalization> stage;
        try {
            stage = context == null ? null : evidence.finalizeSource(projection, context);
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        CompletionStage<BondedVesselEvidenceAuthority.SourceFinalization> safe = stage == null
                ? CompletableFuture.completedFuture(null) : stage;
        return safe.thenCompose(finalization -> {
            boolean present = finalization != null
                    && (finalization.status()
                    == BondedVesselEvidenceAuthority.FinalizationStatus.FINALIZED
                    || finalization.status()
                    == BondedVesselEvidenceAuthority.FinalizationStatus.ALREADY_FINALIZED);
            String itemEvidence = present ? finalization.itemEvidenceJson() : null;
            String reason = present ? finalization.reason()
                    : finalization == null ? "bonded-lifecycle-item-evidence-missing"
                    : finalization.reason();
            return submit(repository.finalizeCommittedItemProjectionAsync(
                    binding.bindingId(), binding.generation(), binding.lifecycleState(),
                    present ? BondedVesselBindingRecord.ItemProjectionStatus.PRESENT
                            : BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                    itemEvidence, reason, now()));
        }).thenApply(projected -> {
            if (!accepted(projected, BondedVesselRepository.Status.APPLIED)) {
                return fromMutation(projected, observation.profileId());
            }
            return Result.committed("bonded-lifecycle-item-projection-finalized",
                    observation.profileId());
        });
    }

    @Nonnull
    private BondedVesselOperationRecord operation(
            Observation observation,
            BondedVesselBindingRecord binding,
            SpawnerVesselConfigView config) {
        long now = now();
        BondedVesselState target = observation.target();
        String targetItem = targetItem(config, target);
        long candidate = Math.addExact(binding.generation(), 1L);
        String replacement = fingerprints.fingerprint(
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        targetItem, UUID.fromString(binding.bindingId()), binding.profileId(),
                        candidate, binding.configId(), target));
        BondedVesselTransitionContext source = sourceContext(binding);
        return new BondedVesselOperationRecord(
                operationId(observation).toString(), CALLER, idempotencyKey(observation),
                observation.removedNpcUuid().toString(), binding.bindingId(), binding.profileId(),
                action(target),
                BondedVesselOperationRecord.State.PREPARED, binding.generation(), candidate,
                binding.expectedProfileRevision(), binding.configId(), binding.configRevision(),
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                targetLifecycle(target), binding.itemProjectionStatus(),
                BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                binding.cooldownUntilMs(), binding.cooldownUntilMs(), binding.lastItemId(),
                targetItem, source == null ? currentFingerprint(binding)
                        : source.sourceItemFingerprint(), replacement,
                source == null ? missingSourceContext(binding) : sourceContextJson(source),
                gson.toJson(Map.of("schema", 1, "transition", target.name(),
                        "configId", binding.configId(), "configRevision", binding.configRevision(),
                        "offlineItemAllowed", true)), observation.populationOperationId(),
                observation.removedNpcUuid(), observation.reason(),
                "LIFECYCLE_ITEM_FINALIZATION_PENDING", 0L, now, now, 0L, 0L);
    }

    private BondedVesselOperationRecord projectionOperation(
            Observation observation,
            BondedVesselBindingRecord binding,
            SpawnerVesselConfigView config) {
        BondedVesselTransitionContext source = sourceContext(binding);
        long candidate = binding.generation();
        String targetItem = targetItem(config, observation.target());
        String replacement = fingerprints.fingerprint(
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        targetItem, UUID.fromString(binding.bindingId()), binding.profileId(),
                        candidate, binding.configId(), observation.target()));
        long prior = Math.max(1L, candidate - 1L);
        long now = now();
        return new BondedVesselOperationRecord(
                operationId(observation).toString(), CALLER, idempotencyKey(observation),
                observation.removedNpcUuid().toString(), binding.bindingId(), binding.profileId(),
                action(observation.target()), BondedVesselOperationRecord.State.COMMITTED,
                prior, candidate, binding.expectedProfileRevision(), binding.configId(),
                binding.configRevision(), BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.LifecycleState.ACTIVE, binding.lifecycleState(),
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                binding.cooldownUntilMs(), binding.cooldownUntilMs(),
                source == null ? Objects.requireNonNullElse(binding.lastItemId(), targetItem)
                        : source.sourceItemId(),
                targetItem, source == null ? "missing-source-fingerprint"
                        : source.sourceItemFingerprint(), replacement,
                source == null ? missingSourceContext(binding) : sourceContextJson(source),
                gson.toJson(Map.of("schema", 1, "projectionOnly", true,
                        "transition", observation.target().name())),
                observation.populationOperationId(), observation.removedNpcUuid(),
                observation.reason(), "COMMITTED_ITEM_PROJECTION_PENDING",
                0L, now, now, now, now);
    }

    @Nullable
    private BondedVesselTransitionContext sourceContext(BondedVesselBindingRecord binding) {
        if (binding.lastItemId() == null || binding.itemEvidenceJson() == null) return null;
        try {
            BondedVesselSourceItemEvidence location = gson.fromJson(
                    binding.itemEvidenceJson(), BondedVesselSourceItemEvidence.class);
            if (location == null) return null;
            return new BondedVesselTransitionContext(
                    location.itemId(), location.holderEvidenceId(), location.containerPath(),
                    location.inventorySlot(), location.inventoryRevision(),
                    location.itemFingerprint(),
                    binding.activeNpcUuid(), null);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Nullable
    private BondedVesselTransitionContext sourceContext(BondedVesselOperationRecord operation) {
        if (operation.sourceContextJson() == null) return null;
        try {
            @SuppressWarnings("unchecked") Map<String, Object> values = gson.fromJson(
                    operation.sourceContextJson(), Map.class);
            return new BondedVesselTransitionContext(
                    (String) values.get("sourceItemId"),
                    (String) values.get("sourceHolderEvidenceId"),
                    (String) values.get("sourceContainerPath"),
                    ((Number) values.get("sourceInventorySlot")).intValue(),
                    ((Number) values.get("sourceInventoryRevision")).longValue(),
                    (String) values.get("sourceItemFingerprint"),
                    operation.actualNpcUuid(), null);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private String currentFingerprint(BondedVesselBindingRecord binding) {
        return fingerprints.fingerprint(new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                Objects.requireNonNull(binding.lastItemId(), "lastItemId"),
                UUID.fromString(binding.bindingId()), binding.profileId(), binding.generation(),
                binding.configId(), BondedVesselState.valueOf(binding.lifecycleState().name())));
    }

    private String sourceContextJson(BondedVesselTransitionContext context) {
        return gson.toJson(Map.of(
                "sourceItemId", context.sourceItemId(),
                "sourceHolderEvidenceId", context.sourceHolderEvidenceId(),
                "sourceContainerPath", context.sourceContainerPath(),
                "sourceInventorySlot", context.sourceInventorySlot(),
                "sourceInventoryRevision", context.sourceInventoryRevision(),
                "sourceItemFingerprint", context.sourceItemFingerprint()));
    }

    private String missingSourceContext(BondedVesselBindingRecord binding) {
        return gson.toJson(Map.of(
                "sourceItemId", Objects.requireNonNullElse(binding.lastItemId(), "missing-vessel"),
                "sourceHolderEvidenceId", "offline-or-missing",
                "sourceContainerPath", "unknown",
                "sourceInventorySlot", 0,
                "sourceInventoryRevision", binding.generation(),
                "sourceItemFingerprint", currentFingerprint(binding)));
    }

    private void emit(BondedVesselOperationRecord operation, boolean recovered) {
        if (!emitted.add(operation.operationId())) return;
        try {
            BondedVesselBindingRecord binding = repository.findBinding(operation.bindingId());
            if (binding == null) return;
            events.emit(new BondedVesselStateChangedEvent(
                    UUID.fromString(operation.operationId()), UUID.fromString(operation.bindingId()),
                    operation.profileId(), binding.ownerUuid(),
                    operation.configId(), operation.priorGeneration(), operation.candidateGeneration(),
                    BondedVesselState.ACTIVE,
                    BondedVesselState.valueOf(operation.targetLifecycleState().name()),
                    binding.expectedProfileRevision(), operation.targetCooldownUntilMs(),
                    reason(operation, "bonded-lifecycle-committed"), recovered,
                    operation.appliedAtMs(), now()));
            if (binding.itemProjectionStatus()
                    == BondedVesselBindingRecord.ItemProjectionStatus.MISSING
                    || binding.itemProjectionStatus()
                    == BondedVesselBindingRecord.ItemProjectionStatus.AMBIGUOUS) {
                events.emit(new BondedVesselBindingInvalidatedEvent(
                        UUID.fromString(operation.operationId()),
                        UUID.fromString(operation.bindingId()), operation.profileId(),
                        binding.ownerUuid(), operation.configId(), operation.priorGeneration(),
                        operation.candidateGeneration(),
                        BondedVesselState.valueOf(operation.targetLifecycleState().name()),
                        BondedVesselProjectionStatus.valueOf(
                                binding.itemProjectionStatus().name()),
                        reason(operation, "bonded-lifecycle-item-projection-invalidated"),
                        recovered, operation.appliedAtMs(), now()));
            }
        } catch (Exception | LinkageError ignored) {
            // Listener/read failure cannot change a committed lifecycle transition.
        }
    }

    private CompletionStage<BondedVesselRepository.MutationResult> submit(
            PersistenceWriteQueue.WriteSubmission<BondedVesselRepository.MutationResult> submission) {
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return submission.completion().thenApply(outcome -> outcome != null
                && outcome.isCommitted() ? outcome.value() : null);
    }

    private static boolean accepted(BondedVesselRepository.MutationResult result,
                                    BondedVesselRepository.Status expected) {
        return result != null && (result.status() == expected
                || result.status() == BondedVesselRepository.Status.IDEMPOTENT);
    }

    private static Result fromMutation(
            @Nullable BondedVesselRepository.MutationResult mutation, String profileId) {
        if (mutation == null) return Result.indeterminate(
                "bonded-lifecycle-write-unconfirmed", profileId);
        if (mutation.status() == BondedVesselRepository.Status.QUARANTINED) {
            return Result.quarantined(mutation.reason(), profileId);
        }
        if (mutation.status() == BondedVesselRepository.Status.CONFLICT
                || mutation.status() == BondedVesselRepository.Status.DENIED
                || mutation.status() == BondedVesselRepository.Status.INVALID_STATE) {
            return Result.skipped(mutation.reason(), profileId);
        }
        return Result.indeterminate(Objects.requireNonNullElse(
                mutation.reason(), "bonded-lifecycle-write-indeterminate"), profileId);
    }

    private static boolean matches(Observation observation, BondedVesselOperationRecord operation) {
        BondedVesselOperationRecord.Action action = action(observation.target());
        return operation.callerNamespace().equals(CALLER)
                && operation.idempotencyKey().equals(idempotencyKey(observation))
                && operation.profileId().equals(observation.profileId())
                && operation.action() == action;
    }

    private static String idempotencyKey(Observation observation) {
        return observation.target().name().toLowerCase(java.util.Locale.ROOT) + ":"
                + observation.profileId() + ":" + observation.committedProfileRevision();
    }

    private static UUID operationId(Observation observation) {
        return UUID.nameUUIDFromBytes((CALLER + ":" + idempotencyKey(observation))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static BondedVesselBindingRecord.LifecycleState targetLifecycle(
            BondedVesselState state) {
        return switch (state) {
            case STORED -> BondedVesselBindingRecord.LifecycleState.STORED;
            case DEAD -> BondedVesselBindingRecord.LifecycleState.DEAD;
            case LOST -> BondedVesselBindingRecord.LifecycleState.LOST;
            default -> throw new IllegalArgumentException(
                    "Unsupported observed bonded lifecycle " + state);
        };
    }

    private static BondedVesselOperationRecord.Action action(BondedVesselState state) {
        return switch (state) {
            case STORED -> BondedVesselOperationRecord.Action.STORE;
            case DEAD -> BondedVesselOperationRecord.Action.MARK_DEAD;
            case LOST -> BondedVesselOperationRecord.Action.MARK_LOST;
            default -> throw new IllegalArgumentException(
                    "Unsupported observed bonded lifecycle " + state);
        };
    }

    private static String targetItem(SpawnerVesselConfigView config, BondedVesselState state) {
        return switch (state) {
            case STORED -> config.storedItemId();
            case DEAD -> config.deadItemId();
            case LOST -> config.lostItemId();
            default -> throw new IllegalArgumentException(
                    "Unsupported observed bonded lifecycle " + state);
        };
    }

    private static String reason(BondedVesselOperationRecord operation, String fallback) {
        return operation.reasonCode() == null ? fallback : operation.reasonCode();
    }

    private long now() { return Math.max(0L, clock.getAsLong()); }

    private record Loaded(@Nullable BondedVesselBindingRecord binding,
                          @Nullable BondedVesselOperationRecord existing) { }

    @FunctionalInterface
    public interface ConfigResolver {
        @Nonnull java.util.Optional<SpawnerVesselConfigView> resolve(
                @Nonnull String configId, long configRevision);
    }

    public record Observation(
            @Nonnull String profileId,
            @Nonnull UUID removedNpcUuid,
            long committedProfileRevision,
            @Nonnull BondedVesselState target,
            @Nonnull String reason,
            @Nullable String populationOperationId) {
        public Observation {
            profileId = requireText(profileId, "profileId");
            removedNpcUuid = Objects.requireNonNull(removedNpcUuid, "removedNpcUuid");
            target = Objects.requireNonNull(target, "target");
            reason = requireText(reason, "reason");
            populationOperationId = populationOperationId == null
                    || populationOperationId.isBlank() ? null : populationOperationId.trim();
            if (committedProfileRevision < 0L) throw new IllegalArgumentException(
                    "committedProfileRevision cannot be negative");
            if (target != BondedVesselState.STORED
                    && target != BondedVesselState.DEAD
                    && target != BondedVesselState.LOST) {
                throw new IllegalArgumentException(
                        "Lifecycle observer supports only STORED, DEAD, or LOST");
            }
        }
    }

    public enum Status { COMMITTED, IDEMPOTENT, SKIPPED, QUARANTINED, INDETERMINATE }

    public record RecoveryReport(int scanned, int completed, int failed) {
        public RecoveryReport {
            if (scanned < 0 || completed < 0 || failed < 0) {
                throw new IllegalArgumentException("Recovery counters cannot be negative");
            }
        }

        private RecoveryReport completedOne() {
            return new RecoveryReport(scanned, completed + 1, failed);
        }

        private RecoveryReport failedOne() {
            return new RecoveryReport(scanned, completed, failed + 1);
        }
    }

    public record Result(@Nonnull Status status, @Nonnull String reason,
                         @Nonnull String profileId) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            profileId = requireText(profileId, "profileId");
        }
        static Result committed(String reason, String profile) {
            return new Result(Status.COMMITTED, reason, profile);
        }
        static Result idempotent(String reason, String profile) {
            return new Result(Status.IDEMPOTENT, reason, profile);
        }
        static Result skipped(String reason, String profile) {
            return new Result(Status.SKIPPED, reason, profile);
        }
        static Result quarantined(String reason, String profile) {
            return new Result(Status.QUARANTINED, reason, profile);
        }
        static Result indeterminate(String reason, String profile) {
            return new Result(Status.INDETERMINATE, reason, profile);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
