package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the one durable resolution boundary for probabilistic and guaranteed captures.
 * Eligibility callers never receive entropy; retries reuse the journaled result.
 */
public final class CaptureAttemptCoordinator {
    private final CaptureAttemptJournal journal;
    private final CapturePolicyRegistry policies;
    private final SpawnerCaptureChanceService chanceService;
    private final CaptureEntropySource entropy;
    private final Clock clock;
    private final Consumer<CaptureAttemptResolvedEvent> events;

    public CaptureAttemptCoordinator(@Nonnull CaptureAttemptJournal journal,
                                     @Nonnull CapturePolicyRegistry policies,
                                     @Nonnull SpawnerCaptureChanceService chanceService,
                                     @Nonnull CaptureEntropySource entropy,
                                     @Nonnull Clock clock,
                                     @Nonnull Consumer<CaptureAttemptResolvedEvent> events) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.chanceService = Objects.requireNonNull(chanceService, "chanceService");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Nonnull
    public CompletableFuture<ResolutionResult> resolve(@Nonnull AttemptRequest request) {
        Objects.requireNonNull(request, "request");
        CapturePolicyConfigView policy = request.itemMechanics().chanceMode() == CaptureChanceMode.GUARANTEED
                ? null
                : policies.snapshot().resolveForRole(request.roleId()).orElse(null);
        long now = clock.millis();
        CaptureAttemptRecord prepared = preparedRecord(request, policy, now);
        return journal.prepare(prepared).thenCompose(result -> {
            if (result.status() == CaptureAttemptRepository.PrepareStatus.CONFLICT
                    || result.attempt() == null) {
                return CompletableFuture.completedFuture(ResolutionResult.denied(
                        request.attemptId(), "capture-attempt-conflict", false, result.attempt()));
            }
            CaptureAttemptRecord active = result.attempt();
            if (active.state() != CaptureAttemptRecord.State.PREPARED) {
                return CompletableFuture.completedFuture(fromExisting(active));
            }
            return resolvePrepared(request, policy, active);
        }).exceptionally(failure -> ResolutionResult.denied(
                request.attemptId(), "capture-attempt-persistence-unavailable", true, null));
    }

    @Nonnull
    private CompletableFuture<ResolutionResult> resolvePrepared(
            AttemptRequest request,
            @Nullable CapturePolicyConfigView policy,
            CaptureAttemptRecord prepared) {
        SpawnerCaptureChanceService.Evaluation evaluation = chanceService.evaluate(
                request.itemMechanics(),
                policy,
                request.currentHealth(),
                request.maximumHealth(),
                request.requirementContext(),
                request.expectedRequirementGeneration(),
                () -> entropy.sample(request.attemptId())
        );
        if (evaluation.outcome() == SpawnerCaptureChanceService.Outcome.DENIED) {
            boolean retryable = evaluation.reason().startsWith("capture-random-provider-");
            if (retryable) {
                return CompletableFuture.completedFuture(ResolutionResult.denied(
                        request.attemptId(), evaluation.reason(), true, prepared));
            }
            return journal.advance(
                    request.attemptId().toString(),
                    CaptureAttemptRecord.State.PREPARED,
                    CaptureAttemptRecord.State.CANCELED,
                    evaluation.reason(),
                    null,
                    clock.millis()
            ).thenApply(cancel -> ResolutionResult.denied(
                    request.attemptId(), evaluation.reason(), false, cancel.attempt()));
        }

        boolean success = evaluation.outcome() == SpawnerCaptureChanceService.Outcome.SUCCESS;
        long resolvedAt = clock.millis();
        long cooldownUntil = success || request.itemMechanics().failureCooldownMs() == 0
                ? 0L
                : saturatedAdd(resolvedAt, request.itemMechanics().failureCooldownMs());
        CaptureAttemptRecord.Resolution evidence = new CaptureAttemptRecord.Resolution(
                request.itemMechanics().power(),
                policy == null ? 0 : policy.minimumPower(),
                request.currentHealth(),
                request.maximumHealth(),
                evaluation.missingHealthFraction(),
                policy == null ? 0.0D : policy.missingHealthBonus(),
                evaluation.effectiveChance(),
                evaluation.entropy(),
                success ? CaptureAttemptOutcome.CAPTURED.name() : CaptureAttemptOutcome.FAILED_ROLL.name(),
                evaluation.reason(),
                cooldownUntil,
                resolvedAt
        );
        CaptureAttemptRepository.ResolutionMutation mutation =
                new CaptureAttemptRepository.ResolutionMutation(
                        request.attemptId().toString(),
                        success,
                        evidence,
                        request.populationOperationId(),
                        request.operationId().toString()
                );
        return journal.resolve(mutation).thenCompose(result -> {
            if (result.attempt() == null
                    || (result.status() != CaptureAttemptRepository.MutationStatus.APPLIED
                    && result.status() != CaptureAttemptRepository.MutationStatus.IDEMPOTENT)) {
                return CompletableFuture.completedFuture(ResolutionResult.denied(
                        request.attemptId(), "capture-attempt-resolution-conflict", true, result.attempt()));
            }
            CaptureAttemptRecord resolved = result.attempt();
            if (resolved.state() == CaptureAttemptRecord.State.RESOLVED_FAILURE) {
                return emitOnce(resolved).thenApply(ignored -> ResolutionResult.failed(resolved));
            }
            return CompletableFuture.completedFuture(ResolutionResult.success(resolved));
        });
    }

    /** Fences a successful attempt before world/item/profile application starts. */
    @Nonnull
    public CompletableFuture<Boolean> beginApply(@Nonnull UUID attemptId) {
        return journal.advance(
                attemptId.toString(),
                CaptureAttemptRecord.State.RESOLVED_SUCCESS,
                CaptureAttemptRecord.State.APPLYING,
                "capture-apply-started", null, clock.millis()
        ).thenApply(result -> result.status() == CaptureAttemptRepository.MutationStatus.APPLIED
                || (result.attempt() != null && result.attempt().state() == CaptureAttemptRecord.State.APPLYING));
    }

    /** Closes the successful attempt only after canonical population/profile/item commit. */
    @Nonnull
    public CompletableFuture<Boolean> commit(@Nonnull UUID attemptId) {
        return journal.advance(
                attemptId.toString(),
                CaptureAttemptRecord.State.APPLYING,
                CaptureAttemptRecord.State.COMMITTED,
                "capture-committed", null, clock.millis()
        ).thenCompose(result -> {
            if (result.attempt() == null || result.attempt().state() != CaptureAttemptRecord.State.COMMITTED) {
                return CompletableFuture.completedFuture(false);
            }
            return emitOnce(result.attempt()).thenApply(ignored -> true);
        }).exceptionally(failure -> false);
    }

    /** Contains an apply failure without changing or re-rolling its successful outcome. */
    @Nonnull
    public CompletableFuture<Boolean> quarantineApply(@Nonnull UUID attemptId, @Nonnull String reason) {
        String normalized = requireText(reason, "reason");
        return journal.advance(
                attemptId.toString(), CaptureAttemptRecord.State.APPLYING,
                CaptureAttemptRecord.State.QUARANTINED,
                "capture-apply-quarantined", normalized, clock.millis()
        ).thenApply(result -> result.status() == CaptureAttemptRepository.MutationStatus.APPLIED
                || (result.attempt() != null && result.attempt().state() == CaptureAttemptRecord.State.QUARANTINED))
                .exceptionally(failure -> false);
    }

    /** Starts bounded fail-closed startup recovery before the capability is advertised. */
    @Nonnull
    public CompletableFuture<RecoveryReport> recover(int maximumAttempts) {
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("maximumAttempts must be positive");
        }
        final List<CaptureAttemptRecord> recoverable;
        try {
            recoverable = journal.loadRecoverable();
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(false, 0, 0, 0, 1));
        }
        CompletableFuture<MutableRecovery> chain = CompletableFuture.completedFuture(new MutableRecovery());
        for (CaptureAttemptRecord attempt : recoverable.stream().limit(maximumAttempts).toList()) {
            chain = chain.thenCompose(report -> recoverOne(attempt).handle((result, failure) -> {
                if (failure != null) report.failed++;
                else if (result == RecoveryAction.CANCELED) report.canceled++;
                else if (result == RecoveryAction.QUARANTINED) report.quarantined++;
                else report.resumable++;
                return report;
            }));
        }
        return chain.thenApply(report -> new RecoveryReport(
                report.failed == 0, recoverable.size(), report.canceled,
                report.quarantined, report.failed));
    }

    private CompletableFuture<RecoveryAction> recoverOne(CaptureAttemptRecord attempt) {
        if (attempt.state() == CaptureAttemptRecord.State.PREPARED) {
            if (attempt.expiresAtMs() > 0L && attempt.expiresAtMs() <= clock.millis()) {
                return journal.advance(
                        attempt.identity().attemptId(), CaptureAttemptRecord.State.PREPARED,
                        CaptureAttemptRecord.State.CANCELED, "capture-attempt-expired",
                        null, clock.millis()).thenApply(ignored -> RecoveryAction.CANCELED);
            }
            return CompletableFuture.completedFuture(RecoveryAction.RESUMABLE);
        }
        if (attempt.state() == CaptureAttemptRecord.State.QUARANTINED) {
            return CompletableFuture.completedFuture(RecoveryAction.RESUMABLE);
        }
        CaptureAttemptRecord.State expected = attempt.state();
        return journal.advance(
                attempt.identity().attemptId(), expected, CaptureAttemptRecord.State.QUARANTINED,
                "capture-recovery-needs-live-fences",
                "Successful capture apply requires the original world and source-item fences.",
                clock.millis()).thenApply(ignored -> RecoveryAction.QUARANTINED);
    }

    private CompletableFuture<Boolean> emitOnce(CaptureAttemptRecord attempt) {
        long emittedAt = clock.millis();
        return journal.markEventEmitted(attempt.identity().attemptId(), emittedAt).thenApply(claimed -> {
            if (claimed) events.accept(toEvent(attempt, emittedAt));
            return claimed;
        });
    }

    private static CaptureAttemptRecord preparedRecord(
            AttemptRequest request, @Nullable CapturePolicyConfigView policy, long now) {
        boolean bypass = request.itemMechanics().chanceMode() == CaptureChanceMode.GUARANTEED;
        return new CaptureAttemptRecord(
                new CaptureAttemptRecord.Identity(
                        request.attemptId().toString(), request.callerNamespace(), request.idempotencyKey(),
                        request.actorUuid(), request.targetNpcUuid(), request.profileId(),
                        request.expectedProfileRevision() < 0 ? null : request.expectedProfileRevision(),
                        request.sourceItemId(), request.roleId(), request.sourceContextJson()),
                new CaptureAttemptRecord.ConfigEvidence(
                        request.spawnerConfigId(), request.spawnerConfigRevision(),
                        policy == null ? null : policy.configId(),
                        policy == null ? null : policy.configRevision(),
                        bypass, bypass),
                CaptureAttemptRecord.State.PREPARED, null, request.populationOperationId(),
                request.operationId().toString(), 0L, "READY",
                request.expiresAtMs(), now, now, 0L, null);
    }

    private static ResolutionResult fromExisting(CaptureAttemptRecord attempt) {
        return switch (attempt.state()) {
            case RESOLVED_FAILURE -> ResolutionResult.failed(attempt);
            case RESOLVED_SUCCESS, APPLYING, COMMITTED -> ResolutionResult.success(attempt);
            case CANCELED -> ResolutionResult.denied(
                    UUID.fromString(attempt.identity().attemptId()), "capture-attempt-canceled", false, attempt);
            case COMPENSATING, QUARANTINED -> ResolutionResult.denied(
                    UUID.fromString(attempt.identity().attemptId()), "capture-attempt-quarantined", true, attempt);
            case PREPARED -> throw new IllegalStateException("prepared attempt was not resolved");
        };
    }

    private static CaptureAttemptResolvedEvent toEvent(CaptureAttemptRecord attempt, long emittedAt) {
        CaptureAttemptRecord.Resolution resolution = Objects.requireNonNull(attempt.resolution());
        CaptureAttemptRecord.Identity identity = attempt.identity();
        CaptureAttemptRecord.ConfigEvidence config = attempt.config();
        return new CaptureAttemptResolvedEvent(
                UUID.fromString(identity.attemptId()),
                UUID.fromString(Objects.requireNonNull(attempt.captureOperationId())),
                identity.actorUuid(), identity.targetNpcUuid(), identity.profileId(),
                Objects.requireNonNull(identity.sourceRoleId()), identity.sourceItemId(),
                config.spawnerConfigId(), config.spawnerConfigRevision(),
                config.targetPolicyConfigId(), config.targetPolicyConfigRevision() == null
                        ? -1L : config.targetPolicyConfigRevision(),
                (int) resolution.power(), (int) resolution.minimumPower(),
                resolution.currentHealth(), resolution.maximumHealth(),
                resolution.missingHealthFraction(), resolution.conditionBonus(),
                resolution.effectiveChance(), config.guaranteed(),
                attempt.state() == CaptureAttemptRecord.State.RESOLVED_FAILURE
                        ? CaptureAttemptOutcome.FAILED_ROLL : CaptureAttemptOutcome.CAPTURED,
                resolution.reasonCode(), resolution.resolvedAtMs(), emittedAt);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public record AttemptRequest(
            @Nonnull UUID attemptId,
            @Nonnull UUID operationId,
            @Nullable String callerNamespace,
            @Nullable String idempotencyKey,
            @Nonnull UUID actorUuid,
            @Nonnull UUID targetNpcUuid,
            @Nullable String profileId,
            long expectedProfileRevision,
            @Nonnull String sourceItemId,
            @Nonnull String roleId,
            @Nonnull String sourceContextJson,
            @Nonnull String spawnerConfigId,
            long spawnerConfigRevision,
            @Nonnull ItemFeatureConfig.CaptureItemMechanics itemMechanics,
            double currentHealth,
            double maximumHealth,
            @Nonnull CaptureRequirementContext requirementContext,
            long expectedRequirementGeneration,
            @Nullable String populationOperationId,
            long expiresAtMs) {
        public AttemptRequest {
            attemptId = Objects.requireNonNull(attemptId, "attemptId");
            operationId = Objects.requireNonNull(operationId, "operationId");
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
            sourceItemId = requireText(sourceItemId, "sourceItemId");
            roleId = requireText(roleId, "roleId");
            sourceContextJson = requireText(sourceContextJson, "sourceContextJson");
            spawnerConfigId = requireText(spawnerConfigId, "spawnerConfigId");
            itemMechanics = Objects.requireNonNull(itemMechanics, "itemMechanics");
            requirementContext = Objects.requireNonNull(requirementContext, "requirementContext");
            if ((callerNamespace == null) != (idempotencyKey == null)) {
                throw new IllegalArgumentException("caller namespace and idempotency key must be paired");
            }
            if (expectedProfileRevision < -1L || spawnerConfigRevision < 0L
                    || expectedRequirementGeneration < 0L || expiresAtMs < 0L) {
                throw new IllegalArgumentException("capture revisions and expiration are invalid");
            }
            if (!Double.isFinite(currentHealth) || !Double.isFinite(maximumHealth)
                    || currentHealth < 0.0D || maximumHealth <= 0.0D || currentHealth > maximumHealth) {
                throw new IllegalArgumentException("capture health evidence is invalid");
            }
        }
    }

    public record ResolutionResult(@Nonnull UUID attemptId,
                                   @Nonnull ResultStatus status,
                                   @Nonnull String reason,
                                   boolean retryable,
                                   @Nullable CaptureAttemptRecord attempt) {
        static ResolutionResult success(CaptureAttemptRecord attempt) {
            return new ResolutionResult(UUID.fromString(attempt.identity().attemptId()),
                    ResultStatus.SUCCESS, attempt.resolution().reasonCode(), false, attempt);
        }
        static ResolutionResult failed(CaptureAttemptRecord attempt) {
            return new ResolutionResult(UUID.fromString(attempt.identity().attemptId()),
                    ResultStatus.FAILED_ROLL, attempt.resolution().reasonCode(), false, attempt);
        }
        static ResolutionResult denied(UUID attemptId, String reason, boolean retryable,
                                       @Nullable CaptureAttemptRecord attempt) {
            return new ResolutionResult(attemptId, ResultStatus.DENIED, reason, retryable, attempt);
        }
    }

    public enum ResultStatus { SUCCESS, FAILED_ROLL, DENIED }

    public record RecoveryReport(boolean ready, int discovered, int canceled,
                                 int quarantined, int failed) { }

    private enum RecoveryAction { RESUMABLE, CANCELED, QUARANTINED }
    private static final class MutableRecovery {
        int resumable;
        int canceled;
        int quarantined;
        int failed;
    }
}
