package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.control.PersistenceContainmentListener;
import com.alechilles.alecstamework.persistence.compensation.PreparedCompensationDetail;
import com.alechilles.alecstamework.persistence.compensation.TimedCompensatedOperationWork;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Staged application engine for the one shared replacement operation protocol.
 *
 * <p>Live effects occur between {@link #transition} to {@code LIVE_APPLYING} and
 * {@link #commitDurable}; this engine never accepts a live callback inside a SQLite transaction.
 * Canonical mutations, the {@code DURABLE} phase, and outbox events commit atomically.</p>
 */
public final class SqliteOperationEngine {
    private static final PersistenceReadKind PREPARE_READBACK =
            new PersistenceReadKind("operation_prepare_readback");
    private static final PersistenceReadKind TRANSITION_READBACK =
            new PersistenceReadKind("operation_transition_readback");
    private static final PersistenceReadKind DURABLE_READBACK =
            new PersistenceReadKind("operation_durable_readback");
    private final OperationDefinitionRegistry definitions;
    private final SqliteUnitOfWorkRunner units;
    private final PersistenceOperationAdmissionGate admission;
    private final PersistenceContainmentListener containmentListener;
    private final SqliteOperationCompensationEngine compensations;
    private final SqliteOperationContainmentEngine containment;

    public SqliteOperationEngine(@Nonnull OperationDefinitionRegistry definitions,
                                 @Nonnull SqliteUnitOfWorkRunner units) {
        this(
                definitions,
                units,
                PersistenceOperationAdmissionGate.allowAll(),
                PersistenceContainmentListener.NO_OP
        );
    }

    public SqliteOperationEngine(
            @Nonnull OperationDefinitionRegistry definitions,
            @Nonnull SqliteUnitOfWorkRunner units,
            @Nonnull PersistenceOperationAdmissionGate admission
    ) {
        this(
                definitions,
                units,
                admission,
                PersistenceContainmentListener.NO_OP
        );
    }

    public SqliteOperationEngine(
            @Nonnull OperationDefinitionRegistry definitions,
            @Nonnull SqliteUnitOfWorkRunner units,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull PersistenceContainmentListener containmentListener
    ) {
        if (definitions == null || units == null || admission == null
                || containmentListener == null) {
            throw new IllegalArgumentException("Operation engine dependencies are required");
        }
        this.definitions = definitions;
        this.units = units;
        this.admission = admission;
        this.containmentListener = containmentListener;
        this.compensations = new SqliteOperationCompensationEngine(units);
        this.containment = new SqliteOperationContainmentEngine(units);
    }

    /** Encodes and durably prepares one idempotent operation. */
    @Nonnull
    public <T> SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepare(
            @Nonnull OperationDefinition<T> definition,
            @Nonnull OperationRequest<T> request
    ) {
        return prepare(definition, request, PreparedOperationDetail.none());
    }

    /**
     * Atomically prepares an operation and its typed, idempotent pre-live detail.
     */
    @Nonnull
    public <T> SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepare(
            @Nonnull OperationDefinition<T> definition,
            @Nonnull OperationRequest<T> request,
            @Nonnull PreparedOperationDetail detail
    ) {
        if (definition == null || request == null) {
            throw new IllegalArgumentException("Operation definition and request are required");
        }
        if (detail == null) {
            throw new IllegalArgumentException("Prepared operation detail is required");
        }
        admission.requireAdmission(
                definition.kind(),
                request.featureScope(),
                request.participants()
        );
        OperationDefinitionRegistry.EncodedOperation encoded =
                definitions.encode(definition, request.payload());
        PreparedOperation prepared = new PreparedOperation(
                request.operationId(), request.idempotencyKey(), encoded.kind(),
                encoded.payloadVersion(), encoded.payloadJson(), request.featureScope(),
                request.expectedLifecycleRevision(), request.participants(), request.createdAtMs()
        );
        SqliteTransactionCommand<OperationEnvelope> command = new SqliteTransactionCommand<>(
                prepared.operationId(),
                prepared.kind(),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> {
                    SqlitePersistenceTransactionContext transaction =
                            new SqlitePersistenceTransactionContext(connection);
                    boolean existing = transaction.operations()
                            .findByIdempotency(
                                    prepared.kind(),
                                    prepared.idempotencyKey()
                            )
                            .isPresent();
                    if (!existing) {
                        requireNotQuarantined(transaction, prepared);
                    }
                    OperationEnvelope operation = requireApplied(
                            transaction.operations().prepare(prepared),
                            "operation_prepare"
                    );
                    SqliteCommandFamilyOperationFence.requireAvailable(
                            connection, operation
                    );
                    if (operation.phase() == OperationPhase.PREPARED
                            && !detail.matches(transaction, operation)) {
                        detail.prepare(transaction, operation);
                    }
                    if (!detail.matches(transaction, operation)) {
                        throw new IllegalStateException(
                                "operation_prepared_detail_missing"
                        );
                    }
                    return operation;
                }
        );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                PREPARE_READBACK,
                connection -> {
                    Optional<OperationEnvelope> found =
                            new SqliteOperationStore(connection).findByIdempotency(
                                    prepared.kind(), prepared.idempotencyKey()
                    );
                    if (found.isPresent() && matchesPreparation(found.get(), prepared)) {
                        SqlitePersistenceTransactionContext transaction =
                                new SqlitePersistenceTransactionContext(connection);
                        SqliteCommandFamilyOperationFence.requireAvailable(
                                connection, found.get()
                        );
                        if (detail.matches(transaction, found.get())) {
                            return PersistenceReadResult.found(
                                    found.get(), found.get().attemptCount()
                            );
                        }
                    }
                    return PersistenceReadResult.absent();
                }
        ));
    }

    /**
     * Records one unknown operation incident and blocks only its proven affected scopes.
     */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<
            com.alechilles.alecstamework.persistence.incidents.IncidentRecord>
    containUnknown(
            @Nonnull OperationEnvelope operation,
            @Nonnull String failureCode,
            @Nonnull String summary,
            @Nonnull List<OperationScope> scopes,
            long containedAtMs
    ) {
        List<OperationScope> notifiedScopes = List.copyOf(
                new java.util.TreeSet<>(scopes)
        );
        SqliteUnitOfWorkRunner.Submission<
                com.alechilles.alecstamework.persistence.incidents
                        .IncidentRecord> submitted = containment.contain(
                operation, failureCode, summary, scopes, containedAtMs
        );
        return new SqliteUnitOfWorkRunner.Submission<>(
                submitted.acceptance(),
                submitted.completion().thenApply(result -> {
                    if (result instanceof PersistenceTransactionResult
                            .Committed<?>) {
                        containmentListener.contained(
                                notifiedScopes, failureCode
                        );
                    }
                    return result;
                })
        );
    }

    /** Advances one exact phase/lease edge through the shared state graph. */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<OperationEnvelope> transition(
            @Nonnull OperationEnvelope expected,
            @Nonnull OperationPhase nextPhase,
            @Nullable String failureKind,
            @Nullable String failureCode,
            long transitionedAtMs
    ) {
        if (expected == null || nextPhase == null) {
            throw new IllegalArgumentException("Expected operation and next phase are required");
        }
        OperationTransition transition = new OperationTransition(
                expected.operationId(), expected.phase(), nextPhase, expected.leaseOwner(),
                failureKind, failureCode, transitionedAtMs
        );
        SqliteTransactionCommand<OperationEnvelope> command = new SqliteTransactionCommand<>(
                expected.operationId(),
                expected.kind(),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> {
                    OperationEnvelope result = requireApplied(
                            new SqliteOperationStore(connection).transition(transition),
                            "operation_transition"
                    );
                    return result;
                }
        );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                TRANSITION_READBACK,
                connection -> exactTransitionReadback(connection, transition)
        ));
    }

    /**
     * Commits canonical mutations, durable phase evidence, and outbox rows atomically.
     */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<DurableCommitEvidence> commitDurable(
            @Nonnull OperationEnvelope expected,
            @Nonnull DurableOperationWork work,
            long committedAtMs
    ) {
        if (expected == null || work == null) {
            throw new IllegalArgumentException("Expected operation and durable work are required");
        }
        if (expected.phase() != OperationPhase.PREPARED
                && expected.phase() != OperationPhase.LIVE_APPLYING
                && expected.phase() != OperationPhase.RETRYABLE
                && expected.phase() != OperationPhase.UNKNOWN) {
            throw new IllegalArgumentException(
                    "Durable commit requires a live-verifiable source phase"
            );
        }
        SqliteTransactionCommand<DurableCommitEvidence> command = new SqliteTransactionCommand<>(
                expected.operationId(),
                expected.kind(),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> commitDurableTransaction(connection, expected, work, committedAtMs)
        );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                DURABLE_READBACK,
                connection -> exactDurableReadback(connection, expected)
        ));
    }

    /**
     * Atomically records typed compensation detail and enters the shared compensating phase.
     */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<OperationEnvelope> beginCompensation(
            @Nonnull OperationEnvelope expected,
            @Nonnull PreparedCompensationDetail detail,
            long preparedAtMs
    ) {
        return compensations.begin(expected, detail, preparedAtMs);
    }

    /** Atomically commits compensation evidence, domain cleanup, and the terminal phase. */
    @Nonnull
    public <T> SqliteUnitOfWorkRunner.Submission<OperationEnvelope> commitCompensated(
            @Nonnull OperationEnvelope expected,
            @Nonnull T payload,
            @Nonnull String liveEvidence,
            @Nonnull TimedCompensatedOperationWork<T> work,
            long compensatedAtMs
    ) {
        return compensations.commit(
                expected,
                payload,
                liveEvidence,
                work,
                compensatedAtMs
        );
    }

    private DurableCommitEvidence commitDurableTransaction(
            java.sql.Connection connection,
            OperationEnvelope expected,
            DurableOperationWork work,
            long committedAtMs
    ) throws Exception {
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        OperationEnvelope current = transaction.operations()
                .find(expected.operationId())
                .orElseThrow(() -> new IllegalStateException("operation_not_found"));
        requireExpected(current, expected);
        List<ProjectionEventDraft> drafts = work.execute(transaction, current);
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalStateException("durable_operation_requires_outbox_event");
        }
        List<ProjectionEvent> events = appendEvents(transaction, current, drafts);
        OperationEnvelope durable = requireApplied(
                transaction.operations().transition(new OperationTransition(
                        current.operationId(), current.phase(), OperationPhase.DURABLE,
                        current.leaseOwner(), null, null, committedAtMs
                )),
                "operation_durable_transition"
        );
        return new DurableCommitEvidence(durable, events);
    }

    private List<ProjectionEvent> appendEvents(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            List<ProjectionEventDraft> drafts
    ) {
        java.util.ArrayList<ProjectionEvent> events = new java.util.ArrayList<>();
        for (ProjectionEventDraft draft : List.copyOf(drafts)) {
            if (draft == null || !draft.operationId().equals(operation.operationId())) {
                throw new IllegalArgumentException(
                        "Durable outbox event must belong to its operation"
                );
            }
            events.add(requireApplied(
                    transaction.outbox().append(draft),
                    "operation_outbox_append"
            ));
        }
        return List.copyOf(events);
    }

    private PersistenceReadResult<OperationEnvelope> exactTransitionReadback(
            java.sql.Connection connection,
            OperationTransition transition
    ) throws Exception {
        OperationEnvelope found = new SqliteOperationStore(connection)
                .find(transition.operationId())
                .orElse(null);
        if (found != null && found.phase() == transition.nextPhase()
                && java.util.Objects.equals(found.failureKind(), transition.failureKind())
                && java.util.Objects.equals(found.failureCode(), transition.failureCode())) {
            return PersistenceReadResult.found(found, found.attemptCount());
        }
        return PersistenceReadResult.absent();
    }

    private PersistenceReadResult<DurableCommitEvidence> exactDurableReadback(
            java.sql.Connection connection,
            OperationEnvelope expected
    ) throws Exception {
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        OperationEnvelope operation = transaction.operations()
                .find(expected.operationId())
                .orElse(null);
        if (operation == null || operation.phase() != OperationPhase.DURABLE) {
            return PersistenceReadResult.absent();
        }
        List<ProjectionEvent> events =
                transaction.outbox().findByOperation(expected.operationId());
        if (events.isEmpty()) {
            return PersistenceReadResult.absent();
        }
        return PersistenceReadResult.found(
                new DurableCommitEvidence(operation, events),
                events.getLast().sequence().value()
        );
    }

    private boolean matchesPreparation(OperationEnvelope existing, PreparedOperation requested) {
        return existing.kind().equals(requested.kind())
                && existing.idempotencyKey().equals(requested.idempotencyKey())
                && existing.payloadVersion() == requested.payloadVersion()
                && existing.payloadJson().equals(requested.payloadJson())
                && existing.featureScope().equals(requested.featureScope())
                && java.util.Objects.equals(
                        existing.expectedLifecycleRevision(),
                        requested.expectedLifecycleRevision()
                )
                && semanticParticipants(existing.participants())
                .equals(semanticParticipants(requested.participants()));
    }

    private List<OperationScope> semanticParticipants(List<OperationScope> participants) {
        return participants.stream()
                .filter(scope -> scope.type() != OperationScopeType.OPERATION)
                .sorted()
                .toList();
    }

    private void requireNotQuarantined(
            SqlitePersistenceTransactionContext transaction,
            PreparedOperation prepared
    ) {
        java.util.ArrayList<OperationScope> candidates =
                new java.util.ArrayList<>(prepared.participants());
        candidates.add(new OperationScope(
                OperationScopeType.FEATURE,
                prepared.featureScope()
        ));
        candidates.add(OperationScope.global());
        if (!transaction.incidents()
                .findActiveQuarantines(candidates)
                .isEmpty()) {
            throw new IllegalStateException(
                    "operation_scope_quarantined"
            );
        }
    }

    private void requireExpected(OperationEnvelope current, OperationEnvelope expected) {
        if (current.phase() != expected.phase()
                || !java.util.Objects.equals(current.leaseOwner(), expected.leaseOwner())) {
            throw new IllegalStateException("operation_phase_or_lease_mismatch");
        }
    }

    private <T> T requireApplied(
            com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult<T> result,
            String operation
    ) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null ? "null" : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }

}
