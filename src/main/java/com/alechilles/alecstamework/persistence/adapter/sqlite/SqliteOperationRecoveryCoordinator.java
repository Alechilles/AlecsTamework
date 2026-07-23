package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.google.gson.JsonObject;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.DecodedOperationPayload;
import com.alechilles.alecstamework.persistence.operation.OperationDecodeResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationLeaseRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryAction;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryIssue;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryScanResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Bounded recovery enumerator that decodes before leasing and quarantines only undecodable
 * operation scope.
 */
public final class SqliteOperationRecoveryCoordinator {
    private static final PersistenceReadKind RECOVERY_READ =
            new PersistenceReadKind("operation_recovery_scan");
    private static final PersistenceReadKind LEASE_READBACK =
            new PersistenceReadKind("operation_recovery_lease_readback");
    private static final PersistenceReadKind CONTAINMENT_READBACK =
            new PersistenceReadKind("operation_recovery_containment_readback");

    private final OperationDefinitionRegistry definitions;
    private final SqliteReadExecutor reads;
    private final SqliteUnitOfWorkRunner units;

    public SqliteOperationRecoveryCoordinator(
            @Nonnull OperationDefinitionRegistry definitions,
            @Nonnull SqliteReadExecutor reads,
            @Nonnull SqliteUnitOfWorkRunner units
    ) {
        if (definitions == null || reads == null || units == null) {
            throw new IllegalArgumentException("Recovery coordinator dependencies are required");
        }
        this.definitions = definitions;
        this.reads = reads;
        this.units = units;
    }

    /** Enumerates, decodes, and claims at most {@code limit} recoverable operations. */
    @Nonnull
    public CompletionStage<OperationRecoveryScanResult> scanAndClaim(
            @Nonnull String workerId,
            long nowMs,
            long leaseUntilMs,
            int limit
    ) {
        if (workerId == null || workerId.isBlank() || leaseUntilMs == 0
                || leaseUntilMs <= nowMs || limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("Valid recovery worker, lease, and limit are required");
        }
        String normalizedWorker = workerId.trim();
        return reads.execute(new SqliteReadCommand<>(
                RECOVERY_READ,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqlitePersistenceTransactionContext transaction =
                            new SqlitePersistenceTransactionContext(connection);
                    List<OperationEnvelope> recoverable =
                            transaction.operations().findRecoverable(nowMs, limit);
                    ArrayList<OperationEnvelope> eligible = new ArrayList<>();
                    int skipped = 0;
                    for (OperationEnvelope operation : recoverable) {
                        ScopeQuarantine quarantine = transaction.incidents()
                                .findQuarantine(OperationScope.operation(operation.operationId()))
                                .orElse(null);
                        if (quarantine != null && quarantine.state() == QuarantineState.ACTIVE) {
                            skipped++;
                        } else {
                            eligible.add(operation);
                        }
                    }
                    return PersistenceReadResult.found(
                            new RecoveryCandidates(List.copyOf(eligible), skipped),
                            eligible.size()
                    );
                }
        )).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Failed<RecoveryCandidates> failed) {
                return CompletableFuture.completedFuture(new OperationRecoveryScanResult(
                        OperationRecoveryScanResult.Status.READ_FAILED,
                        List.of(), List.of(), 0, failed.failure()
                ));
            }
            if (!(read instanceof PersistenceReadResult.Found<RecoveryCandidates> found)) {
                throw new IllegalStateException("Recovery scan cannot return absence");
            }
            return process(
                    found.value(), normalizedWorker, nowMs, leaseUntilMs,
                    0, new ArrayList<>(), new ArrayList<>()
            );
        });
    }

    private CompletionStage<OperationRecoveryScanResult> process(
            RecoveryCandidates candidates,
            String workerId,
            long nowMs,
            long leaseUntilMs,
            int index,
            ArrayList<OperationRecoveryClaim> claims,
            ArrayList<OperationRecoveryIssue> issues
    ) {
        if (index >= candidates.operations().size()) {
            return CompletableFuture.completedFuture(new OperationRecoveryScanResult(
                    OperationRecoveryScanResult.Status.COMPLETE,
                    claims, issues, candidates.skippedQuarantined(), null
            ));
        }
        OperationEnvelope operation = candidates.operations().get(index);
        OperationDecodeResult<DecodedOperationPayload> decoded = definitions.decode(operation);
        if (decoded instanceof OperationDecodeResult.Failed<DecodedOperationPayload> failed) {
            return containDecodeFailure(operation, failed).thenCompose(issue -> {
                issues.add(issue);
                return process(
                        candidates, workerId, nowMs, leaseUntilMs,
                        index + 1, claims, issues
                );
            });
        }
        DecodedOperationPayload payload =
                ((OperationDecodeResult.Decoded<DecodedOperationPayload>) decoded).value();
        return claim(operation, workerId, nowMs, leaseUntilMs).thenCompose(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<OperationEnvelope>
                    committed) {
                claims.add(new OperationRecoveryClaim(
                        committed.value(), payload, action(operation)
                ));
            } else {
                issues.add(new OperationRecoveryIssue(
                        operation.operationId(),
                        "recovery_lease_not_acquired",
                        false,
                        transactionFailure(result)
                ));
            }
            return process(
                    candidates, workerId, nowMs, leaseUntilMs,
                    index + 1, claims, issues
            );
        });
    }

    private CompletionStage<PersistenceTransactionResult<OperationEnvelope>> claim(
            OperationEnvelope operation,
            String workerId,
            long nowMs,
            long leaseUntilMs
    ) {
        OperationLeaseRequest request = new OperationLeaseRequest(
                operation.operationId(), workerId, nowMs, leaseUntilMs
        );
        SqliteTransactionCommand<OperationEnvelope> command = new SqliteTransactionCommand<>(
                operation.operationId(), operation.kind(),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> requireApplied(
                        new SqliteOperationStore(connection).acquireLease(request),
                        "recovery_lease"
                )
        );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                LEASE_READBACK,
                connection -> {
                    OperationEnvelope found = new SqliteOperationStore(connection)
                            .find(operation.operationId())
                            .orElse(null);
                    return found != null
                            && workerId.equals(found.leaseOwner())
                            && found.leaseUntilMs() == leaseUntilMs
                            ? PersistenceReadResult.found(found, found.attemptCount())
                            : PersistenceReadResult.absent();
                }
        )).completion();
    }

    private CompletionStage<OperationRecoveryIssue> containDecodeFailure(
            OperationEnvelope operation,
            OperationDecodeResult.Failed<DecodedOperationPayload> failure
    ) {
        IncidentRecord incident = decodeIncident(operation, failure);
        OperationScope scope = OperationScope.operation(operation.operationId());
        ScopeQuarantine quarantine = new ScopeQuarantine(
                scope, incident.incidentId(), QuarantineState.ACTIVE,
                failure.code(), operation.updatedAtMs(), null
        );
        SqliteTransactionCommand<IncidentRecord> command = new SqliteTransactionCommand<>(
                operation.operationId(), operation.kind(),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> {
                    SqliteIncidentStore incidents = new SqliteIncidentStore(connection);
                    IncidentRecord stored = requireApplied(
                            incidents.createIncident(incident), "recovery_incident"
                    );
                    requireApplied(incidents.quarantine(quarantine), "recovery_quarantine");
                    return stored;
                }
        );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                CONTAINMENT_READBACK,
                connection -> {
                    SqliteIncidentStore incidents = new SqliteIncidentStore(connection);
                    IncidentRecord stored = incidents.findIncident(incident.incidentId())
                            .orElse(null);
                    ScopeQuarantine fence = incidents.findQuarantine(scope).orElse(null);
                    return stored != null && fence != null
                            && fence.state() == QuarantineState.ACTIVE
                            && fence.incidentId().equals(incident.incidentId())
                            ? PersistenceReadResult.found(stored, 0)
                            : PersistenceReadResult.absent();
                }
        )).completion().thenApply(result -> new OperationRecoveryIssue(
                operation.operationId(),
                failure.code(),
                result instanceof PersistenceTransactionResult.Committed<?>,
                failure.cause()
        ));
    }

    private IncidentRecord decodeIncident(
            OperationEnvelope operation,
            OperationDecodeResult.Failed<DecodedOperationPayload> failure
    ) {
        IncidentId incidentId = new IncidentId(UUID.nameUUIDFromBytes(
                ("operation-decode:" + operation.operationId() + ":" + failure.code())
                        .getBytes(StandardCharsets.UTF_8)
        ));
        JsonObject evidence = new JsonObject();
        evidence.addProperty("operationId", operation.operationId().toString());
        evidence.addProperty("operationKind", operation.kind().toString());
        evidence.addProperty("payloadVersion", operation.payloadVersion());
        evidence.addProperty("phase", operation.phase().name());
        return new IncidentRecord(
                incidentId, "DECODE", failure.code(), IncidentState.OPEN,
                "Recovery cannot decode operation " + operation.operationId(),
                evidence.toString(), operation.updatedAtMs(), null
        );
    }

    private OperationRecoveryAction action(OperationEnvelope operation) {
        return switch (operation.phase()) {
            case PREPARED -> OperationRecoveryAction.RESUME_LIVE_APPLY;
            case LIVE_APPLYING -> OperationRecoveryAction.VERIFY_LIVE_APPLY;
            case DURABLE -> OperationRecoveryAction.PUBLISH_DURABLE;
            case COMPENSATING -> OperationRecoveryAction.VERIFY_COMPENSATION;
            case RETRYABLE -> OperationRecoveryAction.RETRY_FROM_EVIDENCE;
            case UNKNOWN -> OperationRecoveryAction.MANUAL_REVIEW;
            case PUBLISHED, COMPENSATED, FAILED ->
                    throw new IllegalArgumentException("Terminal operation cannot be recovered");
        };
    }

    private <T> T requireApplied(PersistenceMutationResult<T> result, String operation) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null ? "null" : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }

    private Throwable transactionFailure(PersistenceTransactionResult<?> result) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> rolledBack) {
            return rolledBack.failure().cause();
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?> unknown) {
            return unknown.failure().cause();
        }
        return null;
    }

    private record RecoveryCandidates(List<OperationEnvelope> operations,
                                      int skippedQuarantined) {
        private RecoveryCandidates {
            operations = List.copyOf(operations);
            if (skippedQuarantined < 0) {
                throw new IllegalArgumentException("Skipped quarantine count cannot be negative");
            }
        }
    }
}
