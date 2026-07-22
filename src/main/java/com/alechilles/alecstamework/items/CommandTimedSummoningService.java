package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonPolicySnapshot;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonRepository;
import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonSessionRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceReadExecutor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates command-roster summon leases across roster, population, world, and SQLite boundaries.
 *
 * <p>World-thread affinity belongs to {@link ProjectionPort}; this service passes immutable IDs and
 * plans only. Capacity is reserved before spawn and is released only after the durable
 * {@code STORING -> ROSTER_STORED} population commit.</p>
 */
public final class CommandTimedSummoningService {
    private static final String CALLER_NAMESPACE = "Alechilles:Tamework:CommandTimedSummoning";

    private final CommandTimedSummonRepository repository;
    private final PersistenceReadExecutor reads;
    private final RosterMembershipPort roster;
    private final PopulationPort population;
    private final ProjectionPort projections;
    private final WarningPort warnings;

    public CommandTimedSummoningService(@Nonnull CommandTimedSummonRepository repository,
                                        @Nonnull PersistenceReadExecutor reads,
                                        @Nonnull RosterMembershipPort roster,
                                        @Nonnull PopulationPort population,
                                        @Nonnull ProjectionPort projections,
                                        @Nonnull WarningPort warnings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.population = Objects.requireNonNull(population, "population");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** Capture/provision seam: starts a lease for a projection already admitted as active. */
    @Nonnull
    public CompletionStage<ActionResult> registerActiveProjection(@Nonnull ActiveRegistration request) {
        Objects.requireNonNull(request, "request");
        return membership(request.ownerUuid(), request.commandFamilyId(), request.profileId())
                .thenCompose(member -> {
                    if (!member) return completed(ActionResult.denied("command-roster-membership-required"));
                    String sessionId = deterministicId("session", request.idempotencyKey());
                    Long remaining = request.policy().unlimited() ? null : request.policy().activeDurationMs();
                    CommandTimedSummonSessionRecord session = new CommandTimedSummonSessionRecord(
                            request.ownerUuid(), request.commandFamilyId(), request.profileId(), 1L,
                            CommandTimedSummonSessionRecord.State.ACTIVE, sessionId, remaining, 0L,
                            request.configId(), request.configRevision(), request.policy(), Set.of(), request.nowMs(),
                            null, request.nowMs(), request.nowMs());
                    return write(repository.createSessionAsync(session)).thenApply(result ->
                            acceptedCreate(result)
                                    ? ActionResult.success(result.session(), "active-lease-registered")
                                    : ActionResult.fromRepository(result));
                });
    }

    /** Paid-revival seam: resets the same snapshotted lease only after the live spawn commits. */
    @Nonnull
    public CompletionStage<ActionResult> registerRevivedProjection(@Nonnull ActiveRegistration request) {
        Objects.requireNonNull(request, "request");
        return membership(request.ownerUuid(), request.commandFamilyId(), request.profileId())
                .thenCompose(member -> {
                    if (!member) return completed(ActionResult.denied("command-roster-membership-required"));
                    String sessionId = deterministicId("revival-session", request.idempotencyKey());
                    Long remaining = request.policy().unlimited() ? null : request.policy().activeDurationMs();
                    CommandTimedSummonSessionRecord active = new CommandTimedSummonSessionRecord(
                            request.ownerUuid(), request.commandFamilyId(), request.profileId(), 1L,
                            CommandTimedSummonSessionRecord.State.ACTIVE, sessionId, remaining, 0L,
                            request.configId(), request.configRevision(), request.policy(), Set.of(), request.nowMs(),
                            null, request.nowMs(), request.nowMs());
                    return write(repository.activateAfterRevivalAsync(active)).thenApply(result ->
                            result.status() == CommandTimedSummonRepository.Status.CREATED
                                    || result.status() == CommandTimedSummonRepository.Status.COMMITTED
                                    || result.status() == CommandTimedSummonRepository.Status.IDEMPOTENT
                                    ? ActionResult.success(result.session(), "revival-active-lease-registered")
                                    : ActionResult.fromRepository(result));
                });
    }

    /** Creates the dormant session row after roster membership is durably committed. */
    @Nonnull
    public CompletionStage<ActionResult> registerRosterStored(@Nonnull StoredRegistration request) {
        Objects.requireNonNull(request, "request");
        return membership(request.ownerUuid(), request.commandFamilyId(), request.profileId())
                .thenCompose(member -> {
                    if (!member) return completed(ActionResult.denied("command-roster-membership-required"));
                    CommandTimedSummonSessionRecord session = new CommandTimedSummonSessionRecord(
                            request.ownerUuid(), request.commandFamilyId(), request.profileId(), 1L,
                            CommandTimedSummonSessionRecord.State.ROSTER_STORED, null, null, 0L,
                            request.configId(), request.configRevision(), request.policy(), Set.of(), null,
                            null, request.nowMs(), request.nowMs());
                    return write(repository.createSessionAsync(session)).thenApply(result ->
                            acceptedCreate(result)
                                    ? ActionResult.success(result.session(), "roster-storage-registered")
                                    : ActionResult.fromRepository(result));
                });
    }

    /**
     * Post-provisioning seam for companions that should appear immediately after profile and roster
     * membership commit. The dormant row is committed before the ordinary summon transaction starts,
     * so placement/admission/spawn failures leave the companion safely roster-stored with no lease.
     */
    @Nonnull
    public CompletionStage<ActionResult> registerAndSummonInitial(@Nonnull InitialSummonRequest request) {
        Objects.requireNonNull(request, "request");
        StoredRegistration stored = new StoredRegistration(
                request.ownerUuid(), request.commandFamilyId(), request.profileId(),
                request.configId(), request.configRevision(), request.policy(), request.nowMs());
        return registerRosterStored(stored).thenCompose(registered -> {
            CommandTimedSummonSessionRecord session = registered.session();
            if (registered.status() != Status.SUCCESS
                    && (session == null
                    || session.state() != CommandTimedSummonSessionRecord.State.ROSTER_STORED)) {
                if (session != null && session.state().occupiesActiveCapacity()) {
                    return completed(ActionResult.noop(session, "initial-projection-already-started"));
                }
                return completed(registered);
            }
            return summon(new SummonRequest(
                    request.ownerUuid(), request.commandFamilyId(), request.profileId(),
                    request.expectedProfileRevision(), request.roleId(), request.configId(),
                    request.configRevision(), request.policy(), request.idempotencyKey(), request.nowMs()));
        });
    }

    /** Summons in front of the owner after roster, cooldown, placement, and active-cap preflight. */
    @Nonnull
    public CompletionStage<ActionResult> summon(@Nonnull SummonRequest request) {
        Objects.requireNonNull(request, "request");
        return membership(request.ownerUuid(), request.commandFamilyId(), request.profileId())
                .thenCompose(member -> member
                        ? readSession(request.ownerUuid(), request.commandFamilyId(), request.profileId())
                        : CompletableFuture.completedFuture(null))
                .thenCompose(session -> {
                    if (session == null) return completed(ActionResult.denied("timed-summon-session-not-found"));
                    if (session.state() != CommandTimedSummonSessionRecord.State.ROSTER_STORED) {
                        return completed(ActionResult.denied("companion-is-not-roster-stored"));
                    }
                    if (session.cooldownActive(request.nowMs())) {
                        return completed(ActionResult.cooldown(session, session.resummonCooldownUntilMs()));
                    }
                    return projections.planSpawnInFront(request.ownerUuid(), request.profileId())
                .thenCompose(plan -> {
                    if (!plan.success()) return completed(ActionResult.denied(plan.reason()));
                    PopulationContext context = new PopulationContext(
                            request.ownerUuid(), request.commandFamilyId(), request.profileId(),
                            request.expectedProfileRevision(), null, plan.destinationWorld(),
                            plan.destinationChunkX(), plan.destinationChunkZ(), request.roleId(),
                            request.idempotencyKey());
                    return population.reserveActive(context).thenCompose(reservation -> {
                        if (!reservation.accepted()) {
                            return completed(ActionResult.denied(reservation.reason()));
                        }
                        return beginSummon(request, session, plan, context, reservation);
                    });
                });
                });
    }

    /** Manual dismissal uses the same idempotent storage transaction as lease expiry. */
    @Nonnull
    public CompletionStage<ActionResult> dismiss(@Nonnull DismissRequest request) {
        Objects.requireNonNull(request, "request");
        return readSession(request.ownerUuid(), request.commandFamilyId(), request.profileId())
                .thenCompose(session -> storeSession(
                        session, request.expectedProfileRevision(), request.projectionNpcUuid(),
                        request.nowMs(), "manual-dismiss", request.idempotencyKey()));
    }

    /** Chunk unload/load callbacks preserve the session and timer instead of starting a new lease. */
    @Nonnull
    public CompletionStage<ActionResult> setProjectionLoaded(@Nonnull UUID ownerUuid,
                                                             @Nonnull String commandFamilyId,
                                                             @Nonnull String profileId,
                                                             long expectedRowRevision,
                                                             @Nonnull String summonSessionId,
                                                             boolean loaded,
                                                             long nowMs) {
        CommandTimedSummonRepository.ProjectionAvailabilityMutation mutation =
                new CommandTimedSummonRepository.ProjectionAvailabilityMutation(
                        ownerUuid, commandFamilyId, profileId, expectedRowRevision, summonSessionId,
                        loaded ? CommandTimedSummonSessionRecord.State.ACTIVE
                                : CommandTimedSummonSessionRecord.State.UNLOADED,
                        nowMs);
        return write(repository.setProjectionAvailabilityAsync(mutation)).thenApply(result ->
                result.status() == CommandTimedSummonRepository.Status.CHECKPOINTED
                        || result.status() == CommandTimedSummonRepository.Status.IDEMPOTENT
                        ? ActionResult.success(result.session(), loaded ? "projection-loaded" : "projection-unloaded")
                        : ActionResult.fromRepository(result));
    }

    /** Checkpoints leases, emits each threshold once, and stores every expired active projection. */
    @Nonnull
    public CompletionStage<TickResult> tick(long nowMs) {
        return recover(nowMs).thenCompose(ignored -> tickProjected(nowMs));
    }

    @Nonnull
    private CompletionStage<TickResult> tickProjected(long nowMs) {
        return reads.submit(repository::loadProjectedSessions).thenCompose(projected -> {
        CompletionStage<TickAccumulator> chain = CompletableFuture.completedFuture(new TickAccumulator());
        for (CommandTimedSummonSessionRecord session : projected) {
            if (session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                    && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED) continue;
            chain = chain.thenCompose(accumulator -> checkpointAndMaybeExpire(session, nowMs)
                    .thenApply(accumulator::include));
        }
        return chain.thenApply(TickAccumulator::result);
        }).exceptionally(failure -> new TickResult(0, 0, 0, 1));
    }

    /** Auto-stores only sessions whose snapshotted policy opted in. */
    @Nonnull
    public CompletionStage<TickResult> onOwnerLogout(@Nonnull UUID ownerUuid, long nowMs) {
        return reads.submit(() -> repository.loadSessionsForOwner(ownerUuid)).thenCompose(sessions -> {
        CompletionStage<TickAccumulator> chain = CompletableFuture.completedFuture(new TickAccumulator());
        for (CommandTimedSummonSessionRecord session : sessions) {
            if ((session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                    && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED)
                    || !session.summonPolicy().autoStoreOnOwnerLogout()) continue;
            String key = "logout:" + session.summonSessionId();
            chain = chain.thenCompose(accumulator -> storeSession(
                    session, null, null, nowMs, "owner-logout", key).thenApply(accumulator::include));
        }
        return chain.thenApply(TickAccumulator::result);
        }).exceptionally(failure -> new TickResult(0, 0, 0, 1));
    }

    /** Replays interrupted prepare/apply boundaries before the public capability is activated. */
    @Nonnull
    public CompletionStage<RecoveryResult> recover(long nowMs) {
        return reads.submit(repository::loadRecoverableOperations).thenCompose(operations -> {
        CompletionStage<RecoveryAccumulator> chain =
                CompletableFuture.completedFuture(new RecoveryAccumulator());
        for (CommandTimedSummonOperationRecord operation : operations) {
            chain = chain.thenCompose(accumulator -> recover(operation, nowMs)
                    .thenApply(accumulator::include));
        }
        return chain.thenApply(RecoveryAccumulator::result);
        }).exceptionally(failure -> new RecoveryResult(0, 0, 1));
    }

    private CompletionStage<ActionResult> recover(CommandTimedSummonOperationRecord operation, long nowMs) {
        if (operation.operationState() == CommandTimedSummonOperationRecord.OperationState.PREPARED) {
            return population.recoverCancel(operation.operationId(), operation.populationOperationId())
                    .thenCompose(decision -> decision.accepted()
                            ? write(repository.cancelPreparedAsync(
                                    operation.operationId(), "startup-before-apply", nowMs)).thenApply(result ->
                                    ActionResult.noop(result.session(), "prepared-operation-canceled"))
                            : completed(ActionResult.recovering(decision.reason())));
        }
        if (operation.operationState() != CommandTimedSummonOperationRecord.OperationState.APPLYING) {
            return completed(ActionResult.recovering("quarantined-timed-operation"));
        }
        return readSession(operation.ownerUuid(), operation.commandFamilyId(), operation.profileId())
                .thenCompose(session -> recoverApplying(operation, session, nowMs));
    }

    private CompletionStage<ActionResult> recoverApplying(
            CommandTimedSummonOperationRecord operation,
            @Nullable CommandTimedSummonSessionRecord session,
            long nowMs) {
        if (session == null || !Objects.equals(session.activeOperationId(), operation.operationId())) {
            return completed(ActionResult.recovering("recovery-session-operation-mismatch"));
        }
        PopulationContext context = new PopulationContext(
                operation.ownerUuid(), operation.commandFamilyId(), operation.profileId(),
                operation.expectedProfileRevision(), operation.projectionNpcUuid(), null,
                null, null, null, operation.idempotencyKey());
        return projections.inspect(context, operation.summonSessionId()).thenCompose(evidence -> {
            if (evidence == ProjectionEvidence.AMBIGUOUS) {
                return completed(ActionResult.recovering("projection-evidence-ambiguous"));
            }
            if (operation.kind() == CommandTimedSummonOperationRecord.Kind.SUMMON) {
                if (evidence == ProjectionEvidence.ABSENT) {
                    return population.recoverCancel(operation.operationId(), operation.populationOperationId())
                            .thenCompose(ignored -> write(repository.rollbackApplyingAsync(
                                    operation.operationId(),
                                    CommandTimedSummonRepository.ApplyAbsenceProof.PROJECTION_NEVER_CREATED,
                                    "startup-projection-absent", nowMs)))
                            .thenApply(result -> ActionResult.noop(result.session(), "summon-recovery-rolled-back"));
                }
                return population.recoverActive(context, operation.populationOperationId())
                        .thenCompose(decision -> decision.accepted()
                                ? write(repository.commitAsync(new CommandTimedSummonRepository.CommitMutation(
                                        operation.operationId(), CommandTimedSummonSessionRecord.State.ACTIVE,
                                        0L, "startup-active-converged", nowMs))).thenApply(result ->
                                        ActionResult.success(result.session(), "summon-recovery-committed"))
                                : completed(ActionResult.recovering(decision.reason())));
            }
            if (operation.kind() == CommandTimedSummonOperationRecord.Kind.STORE) {
                if (evidence == ProjectionEvidence.PRESENT) {
                    return population.recoverActive(context, operation.populationOperationId())
                            .thenCompose(decision -> decision.accepted()
                                    ? write(repository.rollbackApplyingAsync(
                                            operation.operationId(),
                                            CommandTimedSummonRepository.ApplyAbsenceProof.PROJECTION_RETAINED,
                                            "startup-projection-retained", nowMs)).thenApply(result ->
                                            ActionResult.noop(result.session(), "storage-recovery-rolled-back"))
                                    : completed(ActionResult.recovering(decision.reason())));
                }
                return population.recoverRosterStored(context, operation.populationOperationId())
                        .thenCompose(decision -> {
                            if (!decision.accepted()) return completed(ActionResult.recovering(decision.reason()));
                            long cooldown = saturatedAdd(nowMs, session.summonPolicy().resummonCooldownMs());
                            return write(repository.commitAsync(new CommandTimedSummonRepository.CommitMutation(
                                    operation.operationId(), CommandTimedSummonSessionRecord.State.ROSTER_STORED,
                                    cooldown, "startup-storage-converged", nowMs))).thenApply(result ->
                                    ActionResult.success(result.session(), "storage-recovery-committed"));
                        });
            }
            return completed(ActionResult.recovering("unsupported-recoverable-operation"));
        });
    }

    private CompletionStage<Boolean> membership(UUID ownerUuid, String commandFamilyId, String profileId) {
        return reads.submit(() -> roster.contains(ownerUuid, commandFamilyId, profileId));
    }

    private CompletionStage<CommandTimedSummonSessionRecord> readSession(
            UUID ownerUuid, String commandFamilyId, String profileId) {
        return reads.submit(() -> repository.findSession(ownerUuid, commandFamilyId, profileId));
    }

    @Nonnull
    private CompletionStage<ActionResult> beginSummon(SummonRequest request,
                                                      CommandTimedSummonSessionRecord session,
                                                      SpawnPlan plan,
                                                      PopulationContext context,
                                                      PopulationReservation reservation) {
        String operationId = deterministicId("summon-operation", request.idempotencyKey());
        String sessionId = deterministicId("summon-session", request.idempotencyKey());
        CommandTimedSummonOperationRecord operation = new CommandTimedSummonOperationRecord(
                operationId, CALLER_NAMESPACE, request.idempotencyKey(), request.ownerUuid(),
                request.commandFamilyId(), request.profileId(),
                CommandTimedSummonOperationRecord.Kind.SUMMON,
                CommandTimedSummonOperationRecord.OperationState.PREPARED,
                CommandTimedSummonSessionRecord.State.ROSTER_STORED, session.rowRevision(),
                request.expectedProfileRevision(), reservation.populationOperationId(), null, null,
                sessionId, CommandTimedSummonSessionRecord.State.ACTIVE, null,
                request.nowMs(), request.nowMs(), 0L);
        return write(repository.prepareAsync(operation)).thenCompose(prepared -> {
            if (!acceptedPrepare(prepared)) {
                return population.cancel(reservation).thenApply(ignored -> ActionResult.fromRepository(prepared));
            }
            Long remaining = request.policy().unlimited() ? null : request.policy().activeDurationMs();
            CommandTimedSummonRepository.ClaimMutation claim =
                    new CommandTimedSummonRepository.ClaimMutation(
                            operationId, sessionId, remaining, request.configId(),
                            request.configRevision(), request.policy(), request.nowMs());
            return write(repository.claimAsync(claim)).thenCompose(claimed -> {
                if (!acceptedClaim(claimed)) {
                    return population.cancel(reservation).thenApply(ignored -> ActionResult.fromRepository(claimed));
                }
                PopulationDecision populationClaim = population.claimActive(reservation);
                if (!populationClaim.accepted()) {
                    return rollbackSummon(operationId, reservation, populationClaim.reason(), request.nowMs());
                }
                return projections.spawn(plan, context, reservation, sessionId).thenCompose(spawned -> {
                    if (!spawned.success()) {
                        return spawned.outcome() == ProjectionOutcome.NOT_APPLIED
                                ? rollbackSummon(operationId, reservation, spawned.reason(), request.nowMs())
                                : completed(ActionResult.recovering("summon-projection-outcome-ambiguous"));
                    }
                    PopulationContext projectedContext = context.withProjection(spawned.projectionNpcUuid());
                    return population.commitActive(reservation, projectedContext).thenCompose(committed -> {
                        if (!committed.accepted()) {
                            return completed(ActionResult.recovering("active-population-commit-ambiguous"));
                        }
                        return write(repository.commitAsync(new CommandTimedSummonRepository.CommitMutation(
                                operationId, CommandTimedSummonSessionRecord.State.ACTIVE,
                                0L, "summoned", request.nowMs()))).thenApply(result ->
                                result.status() == CommandTimedSummonRepository.Status.COMMITTED
                                        || result.status() == CommandTimedSummonRepository.Status.IDEMPOTENT
                                        ? ActionResult.success(result.session(), "summoned")
                                        : ActionResult.recovering(result.reason()));
                    });
                });
            });
        });
    }

    private CompletionStage<ActionResult> rollbackSummon(String operationId,
                                                         PopulationReservation reservation,
                                                         String reason,
                                                         long nowMs) {
        return population.cancel(reservation).thenCompose(ignored -> write(repository.rollbackApplyingAsync(
                operationId, CommandTimedSummonRepository.ApplyAbsenceProof.PROJECTION_NEVER_CREATED,
                reason, nowMs))).thenApply(result -> ActionResult.denied(reason));
    }

    @Nonnull
    private CompletionStage<ActionResult> checkpointAndMaybeExpire(
            CommandTimedSummonSessionRecord session,
            long nowMs) {
        Long remaining = session.remainingAt(nowMs);
        if (remaining == null) return completed(ActionResult.noop(session, "unlimited-lease"));
        Set<Long> receipts = new LinkedHashSet<>(session.emittedWarningThresholdsMs());
        List<Long> newlyCrossed = new ArrayList<>();
        for (long threshold : session.summonPolicy().expiryWarningThresholdsMs()) {
            if (remaining <= threshold && receipts.add(threshold)) newlyCrossed.add(threshold);
        }
        CommandTimedSummonRepository.CheckpointMutation checkpoint =
                new CommandTimedSummonRepository.CheckpointMutation(
                        session.ownerUuid(), session.commandFamilyId(), session.profileId(),
                        session.rowRevision(), Objects.requireNonNull(session.summonSessionId()),
                        remaining, receipts, nowMs);
        return write(repository.checkpointAsync(checkpoint)).thenCompose(result -> {
            if (result.status() != CommandTimedSummonRepository.Status.CHECKPOINTED) {
                return completed(ActionResult.fromRepository(result));
            }
            for (Long threshold : newlyCrossed) {
                warnings.warn(session.ownerUuid(), session.profileId(), remaining, threshold);
            }
            if (remaining > 0L) return completed(
                    ActionResult.checkpointed(result.session(), newlyCrossed.size()));
            CommandTimedSummonSessionRecord checkpointed = result.session();
            String key = "expiry:" + checkpointed.summonSessionId();
            return storeSession(checkpointed, null, null, nowMs, "lease-expired", key)
                    .thenApply(action -> action.withWarnings(newlyCrossed.size()));
        });
    }

    @Nonnull
    private CompletionStage<ActionResult> storeSession(
            @Nullable CommandTimedSummonSessionRecord session,
            @Nullable Long expectedProfileRevision,
            @Nullable UUID projectionNpcUuid,
            long nowMs,
            @Nonnull String reason,
            @Nonnull String idempotencyKey) {
        if (session == null) return completed(ActionResult.denied("timed-summon-session-not-found"));
        if (session.state() == CommandTimedSummonSessionRecord.State.ROSTER_STORED) {
            return completed(ActionResult.noop(session, "already-roster-stored"));
        }
        if (session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED) {
            return completed(ActionResult.recovering("timed-summon-transition-in-progress"));
        }
        String operationId = deterministicId("store-operation", idempotencyKey);
        CommandTimedSummonOperationRecord operation = new CommandTimedSummonOperationRecord(
                operationId, CALLER_NAMESPACE, idempotencyKey, session.ownerUuid(),
                session.commandFamilyId(), session.profileId(),
                CommandTimedSummonOperationRecord.Kind.STORE,
                CommandTimedSummonOperationRecord.OperationState.PREPARED,
                session.state(), session.rowRevision(),
                expectedProfileRevision, null, projectionNpcUuid, null,
                session.summonSessionId(), CommandTimedSummonSessionRecord.State.ROSTER_STORED,
                null, nowMs, nowMs, 0L);
        return write(repository.prepareAsync(operation)).thenCompose(prepared -> {
            if (!acceptedPrepare(prepared)) return completed(ActionResult.fromRepository(prepared));
            Long remaining = session.remainingAt(nowMs);
            return write(repository.claimAsync(new CommandTimedSummonRepository.ClaimMutation(
                    operationId, Objects.requireNonNull(session.summonSessionId()), remaining,
                    session.summonConfigId(), session.summonConfigRevision(), session.summonPolicy(), nowMs)))
                    .thenCompose(claimed -> {
                        if (!acceptedClaim(claimed)) return completed(ActionResult.fromRepository(claimed));
                        PopulationContext context = new PopulationContext(
                                session.ownerUuid(), session.commandFamilyId(), session.profileId(),
                                expectedProfileRevision, projectionNpcUuid, null, null, null, null,
                                idempotencyKey);
                        return population.beginStoring(context).thenCompose(storing -> {
                            if (!storing.accepted()) {
                                return rollbackStorage(operationId, context, storing.reason(), nowMs);
                            }
                            return projections.snapshotAndDespawn(context, session.summonSessionId())
                                    .thenCompose(stored -> {
                                        if (!stored.success()) {
                                            return stored.outcome() == ProjectionOutcome.NOT_APPLIED
                                                    ? rollbackStorage(operationId, context, stored.reason(), nowMs)
                                                    : completed(ActionResult.recovering(
                                                            "storage-projection-outcome-ambiguous"));
                                        }
                                        return population.commitRosterStored(context).thenCompose(committed -> {
                                            if (!committed.accepted()) {
                                                return completed(ActionResult.recovering(
                                                        "roster-storage-population-commit-ambiguous"));
                                            }
                                            long cooldownUntil = saturatedAdd(
                                                    nowMs, session.summonPolicy().resummonCooldownMs());
                                            return write(repository.commitAsync(
                                                    new CommandTimedSummonRepository.CommitMutation(
                                                            operationId,
                                                            CommandTimedSummonSessionRecord.State.ROSTER_STORED,
                                                            cooldownUntil, reason, nowMs)))
                                                    .thenApply(result -> result.status()
                                                            == CommandTimedSummonRepository.Status.COMMITTED
                                                            || result.status()
                                                            == CommandTimedSummonRepository.Status.IDEMPOTENT
                                                            ? ActionResult.success(result.session(), reason)
                                                            : ActionResult.recovering(result.reason()));
                                        });
                                    });
                        });
                    });
        });
    }

    private CompletionStage<ActionResult> rollbackStorage(String operationId,
                                                          PopulationContext context,
                                                          String reason,
                                                          long nowMs) {
        return population.rollbackStoring(context).thenCompose(ignored ->
                write(repository.rollbackApplyingAsync(
                        operationId, CommandTimedSummonRepository.ApplyAbsenceProof.PROJECTION_RETAINED,
                        reason, nowMs))).thenApply(result -> ActionResult.denied(reason));
    }

    private static boolean acceptedCreate(CommandTimedSummonRepository.MutationResult result) {
        return result.status() == CommandTimedSummonRepository.Status.CREATED
                || result.status() == CommandTimedSummonRepository.Status.IDEMPOTENT;
    }

    private static boolean acceptedPrepare(CommandTimedSummonRepository.MutationResult result) {
        return result.status() == CommandTimedSummonRepository.Status.PREPARED
                || result.status() == CommandTimedSummonRepository.Status.IDEMPOTENT;
    }

    private static boolean acceptedClaim(CommandTimedSummonRepository.MutationResult result) {
        return result.status() == CommandTimedSummonRepository.Status.APPLYING
                || result.status() == CommandTimedSummonRepository.Status.IDEMPOTENT;
    }

    private static CompletionStage<CommandTimedSummonRepository.MutationResult> write(
            PersistenceWriteQueue.WriteSubmission<CommandTimedSummonRepository.MutationResult> submission) {
        return submission.completion().thenCompose(outcome -> {
            if (outcome.isCommitted() && outcome.value() != null) {
                return CompletableFuture.completedFuture(outcome.value());
            }
            CompletableFuture<CommandTimedSummonRepository.MutationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(outcome.failure() != null
                    ? outcome.failure() : new IllegalStateException(outcome.failureReason()));
            return failed;
        });
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return Math.max(0L, left);
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static String deterministicId(String purpose, String key) {
        return UUID.nameUUIDFromBytes((purpose + "|" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static CompletionStage<ActionResult> completed(ActionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    public interface RosterMembershipPort {
        boolean contains(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId, @Nonnull String profileId);
    }

    public interface PopulationPort {
        @Nonnull CompletionStage<PopulationReservation> reserveActive(@Nonnull PopulationContext context);
        @Nonnull PopulationDecision claimActive(@Nonnull PopulationReservation reservation);
        @Nonnull CompletionStage<PopulationDecision> commitActive(
                @Nonnull PopulationReservation reservation, @Nonnull PopulationContext context);
        @Nonnull CompletionStage<PopulationDecision> cancel(@Nonnull PopulationReservation reservation);
        @Nonnull CompletionStage<PopulationDecision> beginStoring(@Nonnull PopulationContext context);
        @Nonnull CompletionStage<PopulationDecision> commitRosterStored(@Nonnull PopulationContext context);
        @Nonnull CompletionStage<PopulationDecision> rollbackStoring(@Nonnull PopulationContext context);
        default CompletionStage<PopulationDecision> recoverCancel(
                String operationId, @Nullable String populationOperationId) {
            return CompletableFuture.completedFuture(PopulationDecision.denied("population-recovery-unavailable"));
        }
        default CompletionStage<PopulationDecision> recoverActive(
                PopulationContext context, @Nullable String populationOperationId) {
            return CompletableFuture.completedFuture(PopulationDecision.denied("population-recovery-unavailable"));
        }
        default CompletionStage<PopulationDecision> recoverRosterStored(
                PopulationContext context, @Nullable String populationOperationId) {
            return CompletableFuture.completedFuture(PopulationDecision.denied("population-recovery-unavailable"));
        }
    }

    public interface ProjectionPort {
        @Nonnull CompletionStage<SpawnPlan> planSpawnInFront(@Nonnull UUID ownerUuid, @Nonnull String profileId);
        @Nonnull CompletionStage<ProjectionResult> spawn(
                @Nonnull SpawnPlan plan, @Nonnull PopulationContext context,
                @Nonnull PopulationReservation reservation, @Nonnull String summonSessionId);
        @Nonnull CompletionStage<ProjectionResult> snapshotAndDespawn(
                @Nonnull PopulationContext context, @Nonnull String summonSessionId);
        default CompletionStage<ProjectionEvidence> inspect(
                @Nonnull PopulationContext context, @Nullable String summonSessionId) {
            return CompletableFuture.completedFuture(ProjectionEvidence.AMBIGUOUS);
        }
    }

    public interface WarningPort {
        void warn(@Nonnull UUID ownerUuid, @Nonnull String profileId, long remainingMs, long thresholdMs);
    }

    public record PopulationContext(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId,
                                    @Nonnull String profileId, @Nullable Long expectedProfileRevision,
                                    @Nullable UUID projectionNpcUuid, @Nullable String destinationWorld,
                                    @Nullable Integer destinationChunkX, @Nullable Integer destinationChunkZ,
                                    @Nullable String roleId, @Nonnull String idempotencyKey) {
        public PopulationContext {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            profileId = requireText(profileId, "profileId");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }

        PopulationContext withProjection(UUID npcUuid) {
            return new PopulationContext(ownerUuid, commandFamilyId, profileId, expectedProfileRevision,
                    npcUuid, destinationWorld, destinationChunkX, destinationChunkZ, roleId, idempotencyKey);
        }
    }

    public record PopulationReservation(boolean accepted, @Nullable String populationOperationId,
                                        @Nonnull String reason) {
        public PopulationReservation { reason = requireText(reason, "reason"); }
    }

    public record PopulationDecision(boolean accepted, @Nonnull String reason) {
        public PopulationDecision { reason = requireText(reason, "reason"); }
        public static PopulationDecision accepted(String reason) { return new PopulationDecision(true, reason); }
        public static PopulationDecision denied(String reason) { return new PopulationDecision(false, reason); }
    }

    public record SpawnPlan(boolean success, @Nullable String destinationWorld,
                            @Nullable Integer destinationChunkX, @Nullable Integer destinationChunkZ,
                            @Nonnull String reason) {
        public SpawnPlan { reason = requireText(reason, "reason"); }
    }

    public enum ProjectionOutcome { SUCCESS, NOT_APPLIED, AMBIGUOUS }
    public enum ProjectionEvidence { PRESENT, ABSENT, AMBIGUOUS }

    public record ProjectionResult(@Nonnull ProjectionOutcome outcome,
                                   @Nullable UUID projectionNpcUuid,
                                   @Nonnull String reason) {
        public ProjectionResult {
            Objects.requireNonNull(outcome, "outcome");
            reason = requireText(reason, "reason");
        }
        public boolean success() { return outcome == ProjectionOutcome.SUCCESS; }
    }

    public record ActiveRegistration(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId,
                                     @Nonnull String profileId, long expectedProfileRevision,
                                     @Nonnull UUID projectionNpcUuid, @Nullable String configId,
                                     @Nullable Long configRevision,
                                     @Nonnull CommandTimedSummonPolicySnapshot policy,
                                     @Nonnull String idempotencyKey, long nowMs) {
        public ActiveRegistration { validateBase(ownerUuid, commandFamilyId, profileId, policy, idempotencyKey, nowMs); }
    }

    public record StoredRegistration(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId,
                                     @Nonnull String profileId, @Nullable String configId,
                                     @Nullable Long configRevision,
                                     @Nonnull CommandTimedSummonPolicySnapshot policy, long nowMs) {
        public StoredRegistration { validateBase(ownerUuid, commandFamilyId, profileId, policy, "stored", nowMs); }
    }

    public record InitialSummonRequest(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId,
                                       @Nonnull String profileId, long expectedProfileRevision,
                                       @Nonnull String roleId, @Nullable String configId,
                                       @Nullable Long configRevision,
                                       @Nonnull CommandTimedSummonPolicySnapshot policy,
                                       @Nonnull String idempotencyKey, long nowMs) {
        public InitialSummonRequest {
            validateBase(ownerUuid, commandFamilyId, profileId, policy, idempotencyKey, nowMs);
            roleId = requireText(roleId, "roleId");
            if (expectedProfileRevision < 0L) throw new IllegalArgumentException("profile revision required.");
        }
    }

    public record SummonRequest(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId,
                                @Nonnull String profileId, long expectedProfileRevision,
                                @Nonnull String roleId, @Nullable String configId,
                                @Nullable Long configRevision,
                                @Nonnull CommandTimedSummonPolicySnapshot policy,
                                @Nonnull String idempotencyKey, long nowMs) {
        public SummonRequest {
            validateBase(ownerUuid, commandFamilyId, profileId, policy, idempotencyKey, nowMs);
            roleId = requireText(roleId, "roleId");
            if (expectedProfileRevision < 0L) throw new IllegalArgumentException("profile revision required.");
        }
    }

    public record DismissRequest(@Nonnull UUID ownerUuid, @Nonnull String commandFamilyId,
                                 @Nonnull String profileId, @Nullable Long expectedProfileRevision,
                                 @Nullable UUID projectionNpcUuid,
                                 @Nonnull String idempotencyKey, long nowMs) {
        public DismissRequest {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            profileId = requireText(profileId, "profileId");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            if (nowMs < 0L) throw new IllegalArgumentException("nowMs must be non-negative.");
        }
    }

    public record ActionResult(@Nonnull Status status, @Nonnull String reason,
                               @Nullable CommandTimedSummonSessionRecord session,
                               @Nullable Long cooldownUntilMs,
                               int warningsEmitted) {
        public ActionResult {
            if (warningsEmitted < 0) throw new IllegalArgumentException("warningsEmitted cannot be negative.");
        }
        static ActionResult success(CommandTimedSummonSessionRecord session, String reason) {
            return new ActionResult(Status.SUCCESS, reason, session, null, 0);
        }
        static ActionResult noop(CommandTimedSummonSessionRecord session, String reason) {
            return new ActionResult(Status.NOOP, reason, session, null, 0);
        }
        static ActionResult checkpointed(CommandTimedSummonSessionRecord session, int warnings) {
            return new ActionResult(Status.NOOP, "lease-checkpointed", session, null, warnings);
        }
        static ActionResult denied(String reason) {
            return new ActionResult(Status.DENIED, reason, null, null, 0);
        }
        static ActionResult recovering(String reason) {
            return new ActionResult(Status.RECOVERING,
                    reason == null ? "recovery-required" : reason, null, null, 0);
        }
        static ActionResult unavailable(String reason) {
            return new ActionResult(Status.UNAVAILABLE, reason, null, null, 0);
        }
        static ActionResult cooldown(CommandTimedSummonSessionRecord session, long until) {
            return new ActionResult(Status.COOLDOWN, "resummon-cooldown-active", session, until, 0);
        }
        static ActionResult fromRepository(CommandTimedSummonRepository.MutationResult result) {
            return new ActionResult(Status.DENIED,
                    result.reason() == null ? result.status().name().toLowerCase() : result.reason(),
                    result.session(), null, 0);
        }
        ActionResult withWarnings(int count) {
            return new ActionResult(status, reason, session, cooldownUntilMs, warningsEmitted + count);
        }
    }

    public enum Status { SUCCESS, NOOP, DENIED, COOLDOWN, RECOVERING, UNAVAILABLE }

    public record TickResult(int checkpointed, int warned, int stored, int failed) { }
    public record RecoveryResult(int converged, int rolledBack, int unresolved) {
        public boolean ready() { return unresolved == 0; }
    }

    private static final class TickAccumulator {
        private int checkpointed;
        private int stored;
        private int failed;
        TickAccumulator include(ActionResult result) {
            warned += result.warningsEmitted();
            if (result.status() == Status.SUCCESS) stored++;
            else if (result.status() == Status.NOOP && "lease-checkpointed".equals(result.reason())) checkpointed++;
            else if (result.status() != Status.NOOP) failed++;
            return this;
        }
        private int warned;
        TickResult result() { return new TickResult(checkpointed, warned, stored, failed); }
    }

    private static final class RecoveryAccumulator {
        private int converged;
        private int rolledBack;
        private int unresolved;
        RecoveryAccumulator include(ActionResult result) {
            if (result.status() == Status.SUCCESS) converged++;
            else if (result.status() == Status.NOOP) rolledBack++;
            else unresolved++;
            return this;
        }
        RecoveryResult result() { return new RecoveryResult(converged, rolledBack, unresolved); }
    }

    private static void validateBase(UUID ownerUuid, String family, String profile,
                                     CommandTimedSummonPolicySnapshot policy, String key, long nowMs) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        requireText(family, "commandFamilyId");
        requireText(profile, "profileId");
        Objects.requireNonNull(policy, "policy");
        requireText(key, "idempotencyKey");
        if (nowMs < 0L) throw new IllegalArgumentException("nowMs must be non-negative.");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }
}
