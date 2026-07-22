package com.alechilles.alecstamework.items.capturepolicy.runtime;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureAttemptResolvedEvent;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CapturePolicyConfigView;
import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementDecision;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.capturepolicy.SpawnerCaptureChanceService;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private final CaptureAttemptRecoveryEvidence recoveryEvidence;

    public CaptureAttemptCoordinator(@Nonnull CaptureAttemptJournal journal,
                                     @Nonnull CapturePolicyRegistry policies,
                                     @Nonnull SpawnerCaptureChanceService chanceService,
                                     @Nonnull CaptureEntropySource entropy,
                                     @Nonnull Clock clock,
                                     @Nonnull Consumer<CaptureAttemptResolvedEvent> events) {
        this(journal, policies, chanceService, entropy, clock, events,
                CaptureAttemptRecoveryEvidence.unavailable());
    }

    public CaptureAttemptCoordinator(@Nonnull CaptureAttemptJournal journal,
                                     @Nonnull CapturePolicyRegistry policies,
                                     @Nonnull SpawnerCaptureChanceService chanceService,
                                     @Nonnull CaptureEntropySource entropy,
                                     @Nonnull Clock clock,
                                     @Nonnull Consumer<CaptureAttemptResolvedEvent> events,
                                     @Nonnull CaptureAttemptRecoveryEvidence recoveryEvidence) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.chanceService = Objects.requireNonNull(chanceService, "chanceService");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.recoveryEvidence = Objects.requireNonNull(recoveryEvidence, "recoveryEvidence");
    }

    @Nonnull
    public CompletableFuture<ResolutionResult> resolve(@Nonnull AttemptRequest request) {
        Objects.requireNonNull(request, "request");
        return journal.findFailureCooldown(request.actorUuid(), request.spawnerConfigId())
                .thenCompose(cooldown -> {
                    long now = clock.millis();
                    if (cooldown != null && cooldown.cooldownUntilMs() > now
                            && !request.attemptId().toString().equals(cooldown.attemptId())) {
                        return CompletableFuture.completedFuture(ResolutionResult.denied(
                                request.attemptId(), "capture-failure-cooldown-active", false, null));
                    }
                    return resolveAfterCooldown(request, now);
                }).exceptionally(failure -> ResolutionResult.denied(
                        request.attemptId(), "capture-attempt-persistence-unavailable", true, null));
    }

    @Nonnull
    private CompletableFuture<ResolutionResult> resolveAfterCooldown(
            AttemptRequest request, long now) {
        CapturePolicyConfigView policy = request.itemMechanics().chanceMode() == CaptureChanceMode.GUARANTEED
                ? null
                : policies.snapshot().resolveForRole(request.roleId()).orElse(null);
        CaptureAttemptRecord prepared = preparedRecord(request, policy, now);
        return journal.prepare(prepared).thenCompose(result -> {
            if (result.status() == CaptureAttemptRepository.PrepareStatus.TOMBSTONED) {
                return CompletableFuture.completedFuture(ResolutionResult.denied(
                        request.attemptId(), "capture-attempt-compacted", false, null));
            }
            if (result.status() == CaptureAttemptRepository.PrepareStatus.CONFLICT
                    || result.attempt() == null) {
                return CompletableFuture.completedFuture(ResolutionResult.denied(
                        request.attemptId(), "capture-attempt-conflict", false, result.attempt()));
            }
            CaptureAttemptRecord active = result.attempt();
            if (active.state() != CaptureAttemptRecord.State.PREPARED) {
                return CompletableFuture.completedFuture(fromExisting(active));
            }
            if (!active.identity().attemptId().equals(request.attemptId().toString())) {
                return CompletableFuture.completedFuture(ResolutionResult.denied(
                        request.attemptId(), "capture-attempt-canonical-identity-mismatch",
                        false, active));
            }
            return resolvePrepared(request, policy, active);
        });
    }

    /** Revalidates pinned custom requirements immediately before the prepared mutation applies. */
    @Nonnull
    public CaptureRequirementDecision revalidateBeforeApply(
            @Nonnull CaptureAttemptRecord attempt,
            @Nonnull CaptureRequirementContext context,
            long expectedRequirementGeneration) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(context, "context");
        if (attempt.config().guaranteed()) {
            return CaptureRequirementDecision.allow();
        }
        CapturePolicyConfigView policy = null;
        String policyId = attempt.config().targetPolicyConfigId();
        if (policyId != null) {
            policy = policies.snapshot().getById(policyId).orElse(null);
            Long expectedRevision = attempt.config().targetPolicyConfigRevision();
            if (policy == null || expectedRevision == null
                    || policy.configRevision() != expectedRevision) {
                return CaptureRequirementDecision.deny("capture-policy-revision-changed");
            }
        }
        return chanceService.revalidateRequirements(
                policy, context, expectedRequirementGeneration);
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
                        request.operationId().toString(),
                        requiresSourceSpend(request.itemMechanics(), success),
                        request.sourceSpendBeforeFingerprint(),
                        request.sourceSpendAfterFingerprint()
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
                if (resolved.sourceSpend().state()
                        == CaptureAttemptRecord.SourceSpendState.PENDING) {
                    return CompletableFuture.completedFuture(ResolutionResult.failed(resolved));
                }
                return emitOnce(resolved).thenApply(ignored -> ResolutionResult.failed(resolved));
            }
            return CompletableFuture.completedFuture(ResolutionResult.success(resolved));
        });
    }

    /** Records one already-applied exact-stack decrement and releases result publication/apply. */
    @Nonnull
    public CompletableFuture<CaptureAttemptRecord> confirmSourceConsumed(@Nonnull UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        long consumedAt = clock.millis();
        return journal.markSourceConsumed(attemptId.toString(), consumedAt).thenCompose(result -> {
            CaptureAttemptRecord attempt = result.attempt();
            if (attempt == null || attempt.sourceSpend().state()
                    != CaptureAttemptRecord.SourceSpendState.CONSUMED) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("capture_source_spend_not_committed"));
            }
            if (attempt.state() == CaptureAttemptRecord.State.RESOLVED_FAILURE) {
                return emitOnce(attempt).thenApply(ignored -> attempt);
            }
            return CompletableFuture.completedFuture(attempt);
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
            return CompletableFuture.completedFuture(
                    new RecoveryReport(false, 0, 0, 0, 0, 0, 0, 1));
        }
        CompletableFuture<MutableRecovery> chain = CompletableFuture.completedFuture(new MutableRecovery());
        for (CaptureAttemptRecord attempt : recoverable.stream().limit(maximumAttempts).toList()) {
            chain = chain.thenCompose(report -> recoverOne(attempt).handle((result, failure) -> {
                if (failure != null) report.failed++;
                else if (result == RecoveryAction.CANCELED) report.canceled++;
                else if (result == RecoveryAction.QUARANTINED) report.quarantined++;
                else if (result == RecoveryAction.COMMITTED) report.committed++;
                else if (result == RecoveryAction.COMPENSATED) report.compensated++;
                else report.resumable++;
                return report;
            }));
        }
        return chain.thenApply(report -> new RecoveryReport(
                report.failed == 0, recoverable.size(), report.canceled,
                report.quarantined, report.committed, report.compensated,
                report.resumable, report.failed));
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
        return recoverResolved(attempt);
    }

    private CompletableFuture<RecoveryAction> recoverResolved(CaptureAttemptRecord attempt) {
        final CaptureAttemptRecoveryEvidence.Evidence evidence;
        try {
            evidence = recoveryEvidence.inspect(attempt);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return switch (evidence.status()) {
            case COMMITTED -> journal.reconcileTerminal(
                    attempt.identity().attemptId(), attempt.state(),
                    CaptureAttemptRecord.State.COMMITTED, evidence.reason(), clock.millis()
            ).thenCompose(result -> {
                if (result.attempt() == null
                        || result.attempt().state() != CaptureAttemptRecord.State.COMMITTED) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("capture_recovery_commit_conflict"));
                }
                return emitOnce(result.attempt()).thenApply(ignored -> RecoveryAction.COMMITTED);
            });
            case COMPENSATED -> journal.reconcileTerminal(
                    attempt.identity().attemptId(), attempt.state(),
                    CaptureAttemptRecord.State.CANCELED, evidence.reason(), clock.millis()
            ).thenApply(result -> {
                if (result.attempt() == null
                        || result.attempt().state() != CaptureAttemptRecord.State.CANCELED) {
                    throw new IllegalStateException("capture_recovery_compensation_conflict");
                }
                return RecoveryAction.COMPENSATED;
            });
            case RESUMABLE -> CompletableFuture.completedFuture(RecoveryAction.RESUMABLE);
            case CONFLICT, UNAVAILABLE -> quarantineRecovery(attempt, evidence.reason());
        };
    }

    private CompletableFuture<RecoveryAction> quarantineRecovery(
            CaptureAttemptRecord attempt, String reason) {
        if (attempt.state() == CaptureAttemptRecord.State.QUARANTINED) {
            return CompletableFuture.completedFuture(RecoveryAction.QUARANTINED);
        }
        return journal.advance(
                attempt.identity().attemptId(), attempt.state(), CaptureAttemptRecord.State.QUARANTINED,
                "capture-recovery-evidence-conflict", reason, clock.millis()
        ).thenApply(result -> {
            if (result.attempt() == null
                    || result.attempt().state() != CaptureAttemptRecord.State.QUARANTINED) {
                throw new IllegalStateException("capture_recovery_quarantine_conflict");
            }
            return RecoveryAction.QUARANTINED;
        });
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
                        bypass, bypass,
                        request.itemMechanics().sourceConsumption(),
                        request.itemMechanics().successDisposition(),
                        request.itemMechanics().commandFamilyId(),
                        request.itemMechanics().requiredCommandConfigId(),
                        request.itemMechanics().requireCommandAccessItem()),
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

    private static boolean requiresSourceSpend(
            ItemFeatureConfig.CaptureItemMechanics mechanics, boolean success) {
        return success
                ? mechanics.successDisposition()
                    == com.alechilles.alecstamework.api.CaptureSuccessDisposition.TAME_AND_COMMAND_LINK
                : mechanics.sourceConsumption()
                    == com.alechilles.alecstamework.api.CaptureSourceConsumption.RESOLVED_ATTEMPT;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String requireSourceContext(String value) {
        String normalized = requireText(value, "sourceContextJson");
        if (normalized.length() > 2_048) {
            throw new IllegalArgumentException("sourceContextJson exceeds 2048 characters");
        }
        final JsonObject context;
        try {
            context = JsonParser.parseString(normalized).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("sourceContextJson must be an object", failure);
        }
        if (!context.has("version") || context.get("version").getAsInt() != 1
                || !context.has("world") || context.get("world").getAsString().isBlank()
                || context.get("world").getAsString().length() > 256
                || !context.has("inventory")
                || !"hotbar".equals(context.get("inventory").getAsString())
                || !context.has("slot") || context.get("slot").getAsInt() < 0
                || !context.has("fingerprint")
                || context.get("fingerprint").getAsString().isBlank()
                || context.get("fingerprint").getAsString().length() > 512) {
            throw new IllegalArgumentException(
                    "sourceContextJson must contain bounded version/world/hotbar/slot/fingerprint evidence");
        }
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
            long expiresAtMs,
            @Nullable String sourceSpendBeforeFingerprint,
            @Nullable String sourceSpendAfterFingerprint) {
        /** Source-compatible constructor for attempts which do not require a pre-result spend. */
        public AttemptRequest(UUID attemptId, UUID operationId, String callerNamespace,
                              String idempotencyKey, UUID actorUuid, UUID targetNpcUuid,
                              String profileId, long expectedProfileRevision, String sourceItemId,
                              String roleId, String sourceContextJson, String spawnerConfigId,
                              long spawnerConfigRevision,
                              ItemFeatureConfig.CaptureItemMechanics itemMechanics,
                              double currentHealth, double maximumHealth,
                              CaptureRequirementContext requirementContext,
                              long expectedRequirementGeneration, String populationOperationId,
                              long expiresAtMs) {
            this(attemptId, operationId, callerNamespace, idempotencyKey, actorUuid,
                    targetNpcUuid, profileId, expectedProfileRevision, sourceItemId, roleId,
                    sourceContextJson, spawnerConfigId, spawnerConfigRevision, itemMechanics,
                    currentHealth, maximumHealth, requirementContext,
                    expectedRequirementGeneration, populationOperationId, expiresAtMs, null, null);
        }

        public AttemptRequest {
            attemptId = Objects.requireNonNull(attemptId, "attemptId");
            operationId = Objects.requireNonNull(operationId, "operationId");
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
            sourceItemId = requireText(sourceItemId, "sourceItemId");
            roleId = requireText(roleId, "roleId");
            sourceContextJson = requireSourceContext(sourceContextJson);
            spawnerConfigId = requireText(spawnerConfigId, "spawnerConfigId");
            itemMechanics = Objects.requireNonNull(itemMechanics, "itemMechanics");
            requirementContext = Objects.requireNonNull(requirementContext, "requirementContext");
            sourceSpendBeforeFingerprint = normalizeFingerprint(
                    sourceSpendBeforeFingerprint, "sourceSpendBeforeFingerprint");
            sourceSpendAfterFingerprint = normalizeFingerprint(
                    sourceSpendAfterFingerprint, "sourceSpendAfterFingerprint");
            boolean mayNeedSpend = itemMechanics.sourceConsumption()
                    == com.alechilles.alecstamework.api.CaptureSourceConsumption.RESOLVED_ATTEMPT
                    || itemMechanics.successDisposition()
                    == com.alechilles.alecstamework.api.CaptureSuccessDisposition.TAME_AND_COMMAND_LINK;
            if (mayNeedSpend && (sourceSpendBeforeFingerprint == null
                    || sourceSpendAfterFingerprint == null)) {
                throw new IllegalArgumentException(
                        "Capture mechanics that may spend before apply require source fingerprints");
            }
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

        @Nullable
        private static String normalizeFingerprint(@Nullable String value, String field) {
            if (value == null) return null;
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > 512) {
                throw new IllegalArgumentException(field + " must contain 1..512 characters");
            }
            return normalized;
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
                                 int quarantined, int committed, int compensated,
                                 int resumable, int failed) { }

    private enum RecoveryAction { RESUMABLE, CANCELED, QUARANTINED, COMMITTED, COMPENSATED }
    private static final class MutableRecovery {
        int resumable;
        int canceled;
        int quarantined;
        int committed;
        int compensated;
        int failed;
    }
}
