package com.alechilles.alecstamework.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningDisposition;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationView;
import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.api.ProvisionedCompanionView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionProvisioningRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restart-safe coordinator for exactly-one dormant creation and optional active projection. */
public final class CompanionProvisioningCoordinator {
    private static final int DEFAULT_RECOVERY_LIMIT = 128;
    private final ProvisioningOperationJournal journal;
    private final ProvisioningPopulationBackend backend;
    private final LongSupplier wallClockMs;

    public CompanionProvisioningCoordinator(@Nonnull ProvisioningOperationJournal journal,
                                            @Nonnull ProvisioningPopulationBackend backend) {
        this(journal, backend, System::currentTimeMillis);
    }

    public CompanionProvisioningCoordinator(@Nonnull ProvisioningOperationJournal journal,
                                            @Nonnull ProvisioningPopulationBackend backend,
                                            @Nonnull LongSupplier wallClockMs) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.wallClockMs = Objects.requireNonNull(wallClockMs, "wallClockMs");
    }

    @Nonnull
    public Optional<ProvisionedCompanionView> getByProfileId(@Nonnull String profileId) {
        String normalized = requireText(profileId, "profileId");
        try {
            CompanionProvisioningOperationRecord operation = journal.findByProfile(normalized);
            if (operation == null || operation.canonicalProfileId() == null) return Optional.empty();
            return backend.findProfile(normalized).map(profile -> view(operation, profile));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Nonnull
    public Optional<ProvisionedCompanionView> getByOrigin(
            @Nonnull String callerNamespace, @Nonnull String idempotencyKey) {
        String namespace = requireText(callerNamespace, "callerNamespace");
        String key = requireText(idempotencyKey, "idempotencyKey");
        try {
            CompanionProvisioningOperationRecord operation = journal.findByOrigin(namespace, key);
            if (operation == null || operation.canonicalProfileId() == null) return Optional.empty();
            return backend.findProfile(operation.canonicalProfileId())
                    .map(profile -> view(operation, profile));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Nonnull
    public CompletionStage<CompanionProvisioningResult> provision(
            @Nonnull CompanionProvisioningRequest request) {
        Objects.requireNonNull(request, "request");
        ProvisioningPopulationBackend.PolicyResolution policy =
                backend.resolvePolicy(request.roleId(), request.expectedPolicyRevision());
        if (!policy.available()) return completedUnavailable(policy.reason());
        if (!policy.matched()) return completedDenied(request, policy.reason());

        UUID operationId = stableOperationId("provision", request.callerNamespace(), request.idempotencyKey());
        long nowMs = nowMs();
        CompanionProvisioningOperationRecord requested = new CompanionProvisioningOperationRecord(
                operationId.toString(), request.callerNamespace(), request.idempotencyKey(),
                request.correlationId() == null ? null : request.correlationId().toString(),
                request.ownerUuid(), request.roleId(), map(request.disposition()),
                request.ownershipWorldName(), destinationJson(request.destination()),
                initialProfileJson(request.displayName(), request.homePosition()), policy.revision(),
                provisionalProfileId(operationId), null,
                CompanionProvisioningOperationRecord.State.PREPARING_DORMANT,
                null, null, null, null, "PREPARING", nowMs, nowMs, 0L
        );
        return journal.create(requested).thenCompose(created -> {
            if (created == null || created.operation() == null) {
                return completedUnavailable("provisioning-journal-create-missing");
            }
            return switch (created.status()) {
                case CREATED, IDEMPOTENT -> resume(created.operation(), created.status() ==
                        CompanionProvisioningRepository.Status.IDEMPOTENT);
                case CONFLICT -> completedDenied(request, "provisioning-idempotency-conflict");
                default -> completedUnavailable(reason(created.reason(), "provisioning-journal-create-failed"));
            };
        }).exceptionally(failure -> CompanionProvisioningResult.unavailable("provisioning-runtime-failed"));
    }

    @Nonnull
    public CompletionStage<CompanionProvisioningResult> transition(
            @Nonnull ProvisionedCompanionTransitionRequest request) {
        Objects.requireNonNull(request, "request");
        UUID operationId = stableOperationId("transition", request.callerNamespace(), request.idempotencyKey());
        ProvisioningPopulationBackend.TransitionRequest internal =
                new ProvisioningPopulationBackend.TransitionRequest(
                        operationId, request.callerNamespace(), request.idempotencyKey(),
                        request.actorUuid(), request.profileId(), request.expectedProfileRevision(),
                        request.transition(), request.ownershipWorldName(), request.destination());
        return backend.transition(internal).thenApply(outcome -> transitionResult(request, operationId, outcome))
                .exceptionally(failure -> CompanionProvisioningResult.unavailable("provisioning-transition-failed"));
    }

    @Nonnull
    public CompletionStage<Optional<CompanionProvisioningOperationView>> findOperation(
            @Nonnull String callerNamespace, @Nonnull String idempotencyKey) {
        String namespace = requireText(callerNamespace, "callerNamespace");
        String key = requireText(idempotencyKey, "idempotencyKey");
        try {
            CompanionProvisioningOperationRecord operation = journal.findByOrigin(namespace, key);
            return CompletableFuture.completedFuture(Optional.ofNullable(operation).map(this::operationView));
        } catch (Exception ignored) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /** Resumes a bounded number of durable nonterminal rows after persistence bootstrap. */
    @Nonnull
    public CompletionStage<RecoveryReport> recover() {
        return recover(DEFAULT_RECOVERY_LIMIT);
    }

    @Nonnull
    public CompletionStage<RecoveryReport> recover(int limit) {
        if (limit <= 0) return CompletableFuture.completedFuture(new RecoveryReport(0, 0, 0));
        final List<CompanionProvisioningOperationRecord> operations;
        try {
            operations = journal.loadRecoverable(limit);
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new RecoveryReport(0, 0, 1));
        }
        CompletionStage<RecoveryReport> chain =
                CompletableFuture.completedFuture(new RecoveryReport(operations.size(), 0, 0));
        for (CompanionProvisioningOperationRecord operation : operations) {
            chain = chain.thenCompose(report -> resume(operation, true)
                    .handle((result, failure) -> failure == null && result != null && result.accepted()
                            ? report.succeeded() : report.failed()));
        }
        return chain;
    }

    private CompletionStage<CompanionProvisioningResult> resume(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        return switch (operation.state()) {
            case PREPARING_DORMANT -> prepareDormant(operation, recovered);
            case DORMANT_PREPARED -> claimDormant(operation, recovered);
            case DORMANT_APPLYING -> recovered
                    ? resumeDormantApplying(operation)
                    : commitDormant(operation, false);
            case DORMANT_COMMITTED -> finishDormantOrPrepareActive(operation, recovered);
            case ACTIVE_PREPARED -> claimActive(operation, recovered);
            case ACTIVE_APPLYING -> commitActive(operation, recovered);
            case PARTIAL_DORMANT -> retryPartialActive(operation, recovered);
            case COMMITTED, DENIED, CANCELED, QUARANTINED ->
                    CompletableFuture.completedFuture(resultFromDurable(operation));
        };
    }

    private CompletionStage<CompanionProvisioningResult> prepareDormant(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        ProvisioningPopulationBackend.DormantRequest request = dormantRequest(operation);
        return backend.prepareDormant(request).thenCompose(prepared -> {
            if (prepared.status() != ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED) {
                return terminalFromPreparation(operation, prepared);
            }
            return advance(operation, CompanionProvisioningOperationRecord.State.DORMANT_PREPARED,
                    null, prepared.populationOperationId().toString(), null,
                    null, prepared.reason(), recovered ? "RECOVERED_PREPARED" : "PREPARED")
                    .thenCompose(next -> resume(next, recovered));
        });
    }

    private CompletionStage<CompanionProvisioningResult> claimDormant(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        UUID populationOperationId = UUID.fromString(requireText(
                operation.dormantPopulationOperationId(), "dormantPopulationOperationId"));
        if (recovered) {
            return backend.resumeDormant(dormantRequest(operation), populationOperationId)
                    .thenCompose(prepared -> {
                        if (prepared.status()
                                != ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED
                                || prepared.populationOperationId() == null) {
                            return terminalFromPreparation(operation, prepared);
                        }
                        if (!populationOperationId.equals(prepared.populationOperationId())) {
                            return terminalAdvance(operation,
                                    CompanionProvisioningOperationRecord.State.QUARANTINED,
                                    "dormant-population-operation-changed-on-resume",
                                    "QUARANTINED");
                        }
                        return claimDormantAcquired(operation, true, populationOperationId);
                    });
        }
        return claimDormantAcquired(operation, false, populationOperationId);
    }

    private CompletionStage<CompanionProvisioningResult> claimDormantAcquired(
            CompanionProvisioningOperationRecord operation,
            boolean recovered,
            UUID populationOperationId) {
        ProvisioningPopulationBackend.ClaimResult claim = backend.claimDormant(populationOperationId);
        if (!claim.claimed()) {
            return backend.cancelDormant(populationOperationId, claim.reason())
                    .handle((ignored, failure) -> null)
                    .thenCompose(ignored -> terminalAdvance(operation,
                            CompanionProvisioningOperationRecord.State.DENIED,
                            claim.reason(), "TERMINAL_DENIED"));
        }
        return advance(operation, CompanionProvisioningOperationRecord.State.DORMANT_APPLYING,
                null, null, null, null, claim.reason(),
                recovered ? "RECOVERED_APPLYING" : "APPLYING")
                .thenCompose(next -> resume(next, recovered));
    }

    private CompletionStage<CompanionProvisioningResult> resumeDormantApplying(
            CompanionProvisioningOperationRecord operation) {
        UUID populationOperationId = UUID.fromString(requireText(
                operation.dormantPopulationOperationId(), "dormantPopulationOperationId"));
        return backend.resumeDormant(dormantRequest(operation), populationOperationId)
                .thenCompose(prepared -> {
                    if (prepared.status()
                            != ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED
                            || prepared.populationOperationId() == null
                            || !populationOperationId.equals(prepared.populationOperationId())) {
                        return terminalAdvance(operation,
                                CompanionProvisioningOperationRecord.State.QUARANTINED,
                                prepared.reason(), "QUARANTINED");
                    }
                    ProvisioningPopulationBackend.ClaimResult claim =
                            backend.claimDormant(populationOperationId);
                    if (!claim.claimed()) {
                        return terminalAdvance(operation,
                                CompanionProvisioningOperationRecord.State.QUARANTINED,
                                claim.reason(), "QUARANTINED");
                    }
                    return commitDormant(operation, true);
                });
    }

    private CompletionStage<CompanionProvisioningResult> commitDormant(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        UUID populationOperationId = UUID.fromString(requireText(
                operation.dormantPopulationOperationId(), "dormantPopulationOperationId"));
        InitialProfile initial = parseInitialProfile(operation.initialProfileJson());
        ProvisioningPopulationBackend.DormantProfileDraft draft =
                new ProvisioningPopulationBackend.DormantProfileDraft(
                        operation.provisionalProfileId(), operation.ownerUuid(), operation.targetRoleId(),
                        operation.ownershipWorldName(), initial.displayName(), initial.homePosition());
        return backend.commitDormant(populationOperationId, draft).thenCompose(committed -> {
            if (committed.status() != ProvisioningPopulationBackend.DormantCommit.Status.COMMITTED
                    || committed.profile() == null) {
                CompanionProvisioningOperationRecord.State terminal =
                        committed.status() == ProvisioningPopulationBackend.DormantCommit.Status.QUARANTINED
                                ? CompanionProvisioningOperationRecord.State.QUARANTINED
                                : CompanionProvisioningOperationRecord.State.DENIED;
                return terminalAdvance(operation, terminal, committed.reason(), terminal.name());
            }
            ProvisioningPopulationBackend.ProfileSnapshot profile = committed.profile();
            return advance(operation, CompanionProvisioningOperationRecord.State.DORMANT_COMMITTED,
                    profile.profileId(), null, null, null, committed.reason(),
                    recovered ? "RECOVERED_DORMANT" : "DORMANT_COMMITTED")
                    .thenCompose(next -> resume(next, recovered));
        });
    }

    private ProvisioningPopulationBackend.DormantRequest dormantRequest(
            CompanionProvisioningOperationRecord operation) {
        return new ProvisioningPopulationBackend.DormantRequest(
                UUID.fromString(operation.operationId()), operation.provisionalProfileId(),
                operation.ownerUuid(), operation.targetRoleId(), operation.ownershipWorldName(),
                Objects.requireNonNull(operation.expectedPolicyRevision(), "expectedPolicyRevision"));
    }

    private CompletionStage<CompanionProvisioningResult> finishDormantOrPrepareActive(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        if (operation.requestedDisposition()
                == CompanionProvisioningOperationRecord.RequestedDisposition.PROVISIONED_DORMANT) {
            return advance(operation, CompanionProvisioningOperationRecord.State.COMMITTED,
                    null, null, null, "provisioned_dormant", "not_requested", "COMMITTED")
                    .thenApply(this::resultFromDurable);
        }
        ProvisioningPopulationBackend.ProfileSnapshot profile = backend
                .findProfile(requireText(operation.canonicalProfileId(), "canonicalProfileId"))
                .orElse(null);
        if (profile == null) {
            return terminalAdvance(operation, CompanionProvisioningOperationRecord.State.QUARANTINED,
                    "canonical-profile-missing", "QUARANTINED");
        }
        PopulationAdmissionLocation destination = parseDestination(operation.destinationContextJson());
        ProvisioningPopulationBackend.ActiveRequest request =
                new ProvisioningPopulationBackend.ActiveRequest(
                        UUID.fromString(operation.operationId()), profile.profileId(), profile.ownerUuid(),
                        profile.roleId(), operation.ownershipWorldName(), destination,
                        profile.profileRevision());
        return backend.prepareActive(request).thenCompose(prepared -> {
            if (prepared.status() != ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED) {
                return partialDormant(operation, prepared.reason());
            }
            return advance(operation, CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED,
                    null, null, prepared.populationOperationId().toString(), null,
                    prepared.reason(), recovered ? "RECOVERED_ACTIVE_PREPARED" : "ACTIVE_PREPARED")
                    .thenCompose(next -> resume(next, recovered));
        });
    }

    /** Explicit caller retry reopens only projection; dormant ownership/profile creation is not run again. */
    private CompletionStage<CompanionProvisioningResult> retryPartialActive(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        if (operation.requestedDisposition()
                != CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE) {
            return CompletableFuture.completedFuture(resultFromDurable(operation));
        }
        ProvisioningPopulationBackend.ProfileSnapshot profile = backend
                .findProfile(requireText(operation.canonicalProfileId(), "canonicalProfileId"))
                .orElse(null);
        if (profile == null) {
            return CompletableFuture.completedFuture(resultFromDurable(operation));
        }
        ProvisioningPopulationBackend.ActiveRequest request =
                new ProvisioningPopulationBackend.ActiveRequest(
                        UUID.fromString(operation.operationId()), profile.profileId(), profile.ownerUuid(),
                        profile.roleId(), operation.ownershipWorldName(),
                        parseDestination(operation.destinationContextJson()), profile.profileRevision());
        return backend.prepareActive(request).thenCompose(prepared -> {
            if (prepared.status() != ProvisioningPopulationBackend.AdmissionPreparation.Status.PREPARED) {
                return CompletableFuture.completedFuture(resultFromDurable(operation));
            }
            return advance(operation, CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED,
                    profile.profileId(), null, prepared.populationOperationId().toString(),
                    null, prepared.reason(), recovered ? "RECOVERED_ACTIVE_RETRY" : "ACTIVE_RETRY")
                    .thenCompose(next -> resume(next, recovered));
        });
    }

    private CompletionStage<CompanionProvisioningResult> claimActive(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        UUID populationOperationId = UUID.fromString(requireText(
                operation.activePopulationOperationId(), "activePopulationOperationId"));
        ProvisioningPopulationBackend.ClaimResult claim = backend.claimActive(populationOperationId);
        if (!claim.claimed()) {
            return backend.cancelActive(populationOperationId, claim.reason())
                    .handle((ignored, failure) -> null)
                    .thenCompose(ignored -> partialDormant(operation, claim.reason()));
        }
        return advance(operation, CompanionProvisioningOperationRecord.State.ACTIVE_APPLYING,
                null, null, null, null, claim.reason(),
                recovered ? "RECOVERED_ACTIVE_APPLYING" : "ACTIVE_APPLYING")
                .thenCompose(next -> resume(next, recovered));
    }

    private CompletionStage<CompanionProvisioningResult> commitActive(
            CompanionProvisioningOperationRecord operation, boolean recovered) {
        UUID populationOperationId = UUID.fromString(requireText(
                operation.activePopulationOperationId(), "activePopulationOperationId"));
        return backend.commitActive(populationOperationId)
                .thenCompose(profile -> advance(operation,
                        CompanionProvisioningOperationRecord.State.COMMITTED,
                        profile.profileId(), null, null, "provisioned_active", "active",
                        recovered ? "RECOVERED_COMMITTED" : "COMMITTED"))
                .thenApply(this::resultFromDurable)
                .exceptionallyCompose(failure -> partialDormant(operation, "active-projection-failed"));
    }

    private CompletionStage<CompanionProvisioningResult> terminalFromPreparation(
            CompanionProvisioningOperationRecord operation,
            ProvisioningPopulationBackend.AdmissionPreparation preparation) {
        CompanionProvisioningOperationRecord.State state = switch (preparation.status()) {
            case QUARANTINED -> CompanionProvisioningOperationRecord.State.QUARANTINED;
            case DENIED -> CompanionProvisioningOperationRecord.State.DENIED;
            case UNAVAILABLE -> null;
            case PREPARED -> throw new IllegalStateException("prepared handled separately");
        };
        return state == null
                ? completedUnavailable(preparation.reason())
                : terminalAdvance(operation, state, preparation.reason(), state.name());
    }

    private CompletionStage<CompanionProvisioningResult> partialDormant(
            CompanionProvisioningOperationRecord operation, String reason) {
        return advance(operation, CompanionProvisioningOperationRecord.State.PARTIAL_DORMANT,
                operation.canonicalProfileId(), null, null, "partial_dormant", reason,
                "PARTIAL_DORMANT").thenApply(this::resultFromDurable);
    }

    private CompletionStage<CompanionProvisioningResult> terminalAdvance(
            CompanionProvisioningOperationRecord operation,
            CompanionProvisioningOperationRecord.State terminal,
            String resultReason,
            String recoveryStatus) {
        return advance(operation, terminal, operation.canonicalProfileId(), null, null,
                terminal == CompanionProvisioningOperationRecord.State.DENIED
                        ? "denied" : terminal.name().toLowerCase(),
                resultReason, recoveryStatus).thenApply(this::resultFromDurable);
    }

    private CompletionStage<CompanionProvisioningOperationRecord> advance(
            CompanionProvisioningOperationRecord operation,
            CompanionProvisioningOperationRecord.State next,
            @Nullable String canonicalProfileId,
            @Nullable String dormantPopulationOperationId,
            @Nullable String activePopulationOperationId,
            @Nullable String resultCode,
            @Nullable String projectionReason,
            @Nullable String recoveryStatus) {
        CompanionProvisioningRepository.AdvanceMutation mutation =
                new CompanionProvisioningRepository.AdvanceMutation(
                        operation.operationId(), operation.state(), next, canonicalProfileId,
                        dormantPopulationOperationId, activePopulationOperationId, resultCode,
                        projectionReason, recoveryStatus, nowMs());
        return journal.advance(mutation).thenCompose(result -> {
            if (result != null && result.operation() != null
                    && result.status() != CompanionProvisioningRepository.Status.CONFLICT
                    && result.status() != CompanionProvisioningRepository.Status.NOT_FOUND
                    && result.status() != CompanionProvisioningRepository.Status.INVALID_STATE) {
                return CompletableFuture.completedFuture(result.operation());
            }
            return CompletableFuture.failedFuture(new IllegalStateException(
                    result == null ? "provisioning-journal-advance-missing"
                            : reason(result.reason(), "provisioning-journal-state-changed")));
        });
    }

    private CompanionProvisioningResult resultFromDurable(
            CompanionProvisioningOperationRecord operation) {
        ProvisioningPopulationBackend.ProfileSnapshot profile = operation.canonicalProfileId() == null
                ? null : backend.findProfile(operation.canonicalProfileId()).orElse(null);
        CompanionProvisioningResult.Status status = switch (operation.state()) {
            case COMMITTED -> operation.requestedDisposition()
                    == CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE
                    ? CompanionProvisioningResult.Status.PROVISIONED_ACTIVE
                    : CompanionProvisioningResult.Status.PROVISIONED_DORMANT;
            case PARTIAL_DORMANT -> CompanionProvisioningResult.Status.PARTIAL_DORMANT;
            case DENIED, CANCELED -> CompanionProvisioningResult.Status.DENIED;
            case QUARANTINED -> CompanionProvisioningResult.Status.QUARANTINED;
            default -> CompanionProvisioningResult.Status.UNAVAILABLE;
        };
        if ((status == CompanionProvisioningResult.Status.PROVISIONED_ACTIVE
                || status == CompanionProvisioningResult.Status.PROVISIONED_DORMANT
                || status == CompanionProvisioningResult.Status.PARTIAL_DORMANT) && profile == null) {
            return CompanionProvisioningResult.unavailable("canonical-provisioned-profile-unavailable");
        }
        CompanionProvisioningProjectionStatus projection = profile == null
                ? CompanionProvisioningProjectionStatus.UNAVAILABLE
                : status == CompanionProvisioningResult.Status.PARTIAL_DORMANT
                ? CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE
                : profile.projectionStatus();
        return new CompanionProvisioningResult(
                status, reason(operation.resultCode(), operation.state().name().toLowerCase()),
                operation.callerNamespace(), operation.idempotencyKey(),
                UUID.fromString(operation.operationId()), operation.canonicalProfileId(),
                operation.ownerUuid(), operation.targetRoleId(), profile == null ? null : profile.lifecycle(),
                projection, reason(operation.projectionReason(), projection.name().toLowerCase()),
                null, profile == null ? CompanionProvisioningResult.UNKNOWN_PROFILE_REVISION
                        : profile.profileRevision());
    }

    private CompanionProvisioningResult transitionResult(
            ProvisionedCompanionTransitionRequest request, UUID operationId,
            ProvisioningPopulationBackend.TransitionOutcome outcome) {
        if (outcome.status() == ProvisioningPopulationBackend.TransitionOutcome.Status.UNAVAILABLE) {
            return CompanionProvisioningResult.unavailable(outcome.reason());
        }
        if (outcome.status() == ProvisioningPopulationBackend.TransitionOutcome.Status.DENIED
                || outcome.profile() == null) {
            return new CompanionProvisioningResult(
                    outcome.status() == ProvisioningPopulationBackend.TransitionOutcome.Status.QUARANTINED
                            ? CompanionProvisioningResult.Status.QUARANTINED
                            : CompanionProvisioningResult.Status.DENIED,
                    outcome.reason(), request.callerNamespace(), request.idempotencyKey(), operationId,
                    request.profileId(), null, null, null,
                    CompanionProvisioningProjectionStatus.UNAVAILABLE, outcome.reason(),
                    outcome.populationDecision(), CompanionProvisioningResult.UNKNOWN_PROFILE_REVISION);
        }
        ProvisioningPopulationBackend.ProfileSnapshot profile = outcome.profile();
        return new CompanionProvisioningResult(
                outcome.status() == ProvisioningPopulationBackend.TransitionOutcome.Status.IDEMPOTENT
                        ? CompanionProvisioningResult.Status.ALREADY_TRANSITIONED
                        : CompanionProvisioningResult.Status.TRANSITIONED,
                outcome.reason(), request.callerNamespace(), request.idempotencyKey(), operationId,
                profile.profileId(), profile.ownerUuid(), profile.roleId(), profile.lifecycle(),
                profile.projectionStatus(), outcome.reason(), outcome.populationDecision(),
                profile.profileRevision());
    }

    private CompanionProvisioningOperationView operationView(
            CompanionProvisioningOperationRecord operation) {
        ProvisioningPopulationBackend.ProfileSnapshot profile = operation.canonicalProfileId() == null
                ? null : backend.findProfile(operation.canonicalProfileId()).orElse(null);
        return new CompanionProvisioningOperationView(
                UUID.fromString(operation.operationId()), operation.callerNamespace(),
                operation.idempotencyKey(), parseUuid(operation.correlationId()), map(operation.state()),
                reason(operation.resultCode(), operation.state().name().toLowerCase()),
                operation.canonicalProfileId(), operation.ownerUuid(), operation.targetRoleId(),
                profile == null ? null : profile.lifecycle(), projectionFor(operation, profile),
                profile == null ? -1L : profile.profileRevision(),
                operation.recoveryStatus().startsWith("RECOVERED"), operation.updatedAtMs());
    }

    private ProvisionedCompanionView view(CompanionProvisioningOperationRecord operation,
                                          ProvisioningPopulationBackend.ProfileSnapshot profile) {
        return new ProvisionedCompanionView(
                UUID.fromString(operation.operationId()), operation.callerNamespace(),
                operation.idempotencyKey(), profile.profileId(), profile.ownerUuid(), profile.roleId(),
                profile.lifecycle(), profile.projectionStatus(), profile.currentNpcUuid(),
                profile.profileRevision(), profile.updatedAtMs());
    }

    private CompanionProvisioningProjectionStatus projectionFor(
            CompanionProvisioningOperationRecord operation,
            @Nullable ProvisioningPopulationBackend.ProfileSnapshot profile) {
        if (operation.state() == CompanionProvisioningOperationRecord.State.PARTIAL_DORMANT) {
            return CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE;
        }
        if (operation.state() == CompanionProvisioningOperationRecord.State.ACTIVE_PREPARED
                || operation.state() == CompanionProvisioningOperationRecord.State.ACTIVE_APPLYING) {
            return CompanionProvisioningProjectionStatus.PENDING;
        }
        return profile == null ? CompanionProvisioningProjectionStatus.NOT_REQUESTED
                : profile.projectionStatus();
    }

    private static CompanionProvisioningOperationStatus map(
            CompanionProvisioningOperationRecord.State state) {
        return switch (state) {
            case PREPARING_DORMANT -> CompanionProvisioningOperationStatus.PREPARING;
            case DORMANT_PREPARED -> CompanionProvisioningOperationStatus.PREPARED;
            case DORMANT_APPLYING -> CompanionProvisioningOperationStatus.APPLYING;
            case DORMANT_COMMITTED -> CompanionProvisioningOperationStatus.DORMANT_COMMITTED;
            case ACTIVE_PREPARED, ACTIVE_APPLYING -> CompanionProvisioningOperationStatus.PROJECTING;
            case COMMITTED -> CompanionProvisioningOperationStatus.COMMITTED;
            case PARTIAL_DORMANT -> CompanionProvisioningOperationStatus.PARTIAL_DORMANT;
            case CANCELED -> CompanionProvisioningOperationStatus.CANCELED;
            case DENIED -> CompanionProvisioningOperationStatus.TERMINAL_DENIED;
            case QUARANTINED -> CompanionProvisioningOperationStatus.QUARANTINED;
        };
    }

    private static CompanionProvisioningOperationRecord.RequestedDisposition map(
            CompanionProvisioningDisposition disposition) {
        return disposition == CompanionProvisioningDisposition.ACTIVE
                ? CompanionProvisioningOperationRecord.RequestedDisposition.ACTIVE
                : CompanionProvisioningOperationRecord.RequestedDisposition.PROVISIONED_DORMANT;
    }

    private static String initialProfileJson(@Nullable String displayName,
                                             @Nullable Vector3View homePosition) {
        JsonObject root = new JsonObject();
        if (displayName != null) root.addProperty("displayName", displayName);
        if (homePosition != null) {
            JsonObject home = new JsonObject();
            home.addProperty("x", homePosition.x());
            home.addProperty("y", homePosition.y());
            home.addProperty("z", homePosition.z());
            root.add("homePosition", home);
        }
        return root.toString();
    }

    private static InitialProfile parseInitialProfile(@Nullable String json) {
        if (json == null) return new InitialProfile(null, null);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String displayName = root.has("displayName") ? root.get("displayName").getAsString() : null;
        Vector3View home = null;
        if (root.has("homePosition")) {
            JsonObject value = root.getAsJsonObject("homePosition");
            home = new Vector3View(value.get("x").getAsDouble(), value.get("y").getAsDouble(),
                    value.get("z").getAsDouble());
        }
        return new InitialProfile(displayName, home);
    }

    private static String destinationJson(@Nullable PopulationAdmissionLocation destination) {
        if (destination == null) return null;
        JsonObject root = new JsonObject();
        root.addProperty("worldName", destination.worldName());
        root.addProperty("chunkX", destination.chunkX());
        root.addProperty("chunkZ", destination.chunkZ());
        return root.toString();
    }

    private static PopulationAdmissionLocation parseDestination(@Nullable String json) {
        JsonObject root = JsonParser.parseString(requireText(json, "destinationContextJson"))
                .getAsJsonObject();
        return new PopulationAdmissionLocation(root.get("worldName").getAsString(),
                root.get("chunkX").getAsInt(), root.get("chunkZ").getAsInt());
    }

    private static UUID stableOperationId(String kind, String namespace, String key) {
        return UUID.nameUUIDFromBytes(("tamework:" + kind + ":" + namespace + "\0" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String provisionalProfileId(UUID operationId) {
        return "provisioned:" + operationId;
    }

    private long nowMs() {
        return Math.max(0L, wallClockMs.getAsLong());
    }

    private static CompletionStage<CompanionProvisioningResult> completedUnavailable(String reason) {
        return CompletableFuture.completedFuture(CompanionProvisioningResult.unavailable(reason));
    }

    private static CompletionStage<CompanionProvisioningResult> completedDenied(
            CompanionProvisioningRequest request, String reason) {
        return CompletableFuture.completedFuture(new CompanionProvisioningResult(
                CompanionProvisioningResult.Status.DENIED, reason,
                request.callerNamespace(), request.idempotencyKey(), null, null,
                request.ownerUuid(), request.roleId(), null,
                CompanionProvisioningProjectionStatus.UNAVAILABLE, reason, null,
                CompanionProvisioningResult.UNKNOWN_PROFILE_REVISION));
    }

    private static String requireText(@Nullable String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String reason(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Nullable
    private static UUID parseUuid(@Nullable String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record InitialProfile(@Nullable String displayName, @Nullable Vector3View homePosition) {}

    public record RecoveryReport(int attempted, int completed, int failures) {
        public RecoveryReport {
            if (attempted < 0 || completed < 0 || failures < 0) {
                throw new IllegalArgumentException("Recovery counts cannot be negative");
            }
        }
        RecoveryReport succeeded() { return new RecoveryReport(attempted, completed + 1, failures); }
        RecoveryReport failed() { return new RecoveryReport(attempted, completed, failures + 1); }
    }
}
