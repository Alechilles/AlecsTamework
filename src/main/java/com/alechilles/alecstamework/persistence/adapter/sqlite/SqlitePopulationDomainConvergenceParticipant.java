package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainPort;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Shared-operation participant that converges exact retained domain rows after
 * canonical lifecycle work succeeds.
 *
 * <p>Preparation only validates the frozen source set. Durable decoration then
 * applies the exact row updates or deletes in the same SQLite transaction as the
 * delegated lifecycle mutation. It never creates a second operation or calls a
 * provider.</p>
 */
public final class SqlitePopulationDomainConvergenceParticipant
        implements PreparedOperationDetail {
    private final PopulationDomainConvergencePlan plan;

    public SqlitePopulationDomainConvergenceParticipant(
            @Nonnull PopulationDomainConvergencePlan plan
    ) {
        if (plan == null) {
            throw new IllegalArgumentException("Domain convergence plan is required");
        }
        this.plan = plan;
    }

    @Override
    public void prepare(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) {
        requireOperation(operation);
        if (!sourceMatches(transaction.populationDomains(), operation)) {
            throw new IllegalStateException(
                    "population_domain_convergence_source_mismatch"
            );
        }
    }

    @Override
    public boolean matches(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationEnvelope operation
    ) {
        requireOperation(operation);
        return switch (operation.phase()) {
            case DURABLE, PUBLISHED -> durableMatches(transaction, operation);
            case COMPENSATED, FAILED -> terminalRollbackMatches(
                    transaction.populationDomains(), operation
            );
            case PREPARED, LIVE_APPLYING, RETRYABLE, UNKNOWN, COMPENSATING ->
                    sourceMatches(
                    transaction.populationDomains(), operation
            );
        };
    }

    /** Decorates canonical durable work with exact source-row convergence. */
    @Nonnull
    public DurableOperationWork decorate(@Nonnull DurableOperationWork delegated) {
        if (delegated == null) {
            throw new IllegalArgumentException(
                    "Domain convergence durable work is required"
            );
        }
        return (transaction, operation) -> {
            requireOperation(operation);
            if (!sourceMatches(transaction.populationDomains(), operation)) {
                throw new IllegalStateException(
                        "population_domain_convergence_source_mismatch"
                );
            }
            List<ProjectionEventDraft> events = delegated.execute(
                    transaction, operation
            );
            if (!transaction.populationDomains().convergeExact(plan)) {
                throw new IllegalStateException(
                        "population_domain_convergence_failed"
                );
            }
            return events;
        };
    }

    /** Returns the frozen evidence for composition by a lifecycle adapter. */
    @Nonnull
    public PopulationDomainConvergencePlan plan() {
        return plan;
    }

    private boolean sourceMatches(
            PopulationDomainPort domains,
            OperationEnvelope operation
    ) {
        PopulationDomainPort.ProfileEvidence evidence = domains.profileEvidence(
                plan.profileId(), operation.operationId()
        );
        List<PopulationDomainReservation> actual = sorted(evidence.committed());
        List<PopulationDomainReservation> expected = sorted(plan.sourceRows().stream()
                .map(PopulationDomainConvergencePlan.SourceRow::expected)
                .toList());
        return samePersisted(actual, expected)
                && samePersisted(
                sorted(evidence.currentOperationPending()),
                sorted(plan.targetReservations())
        )
                && evidence.foreignPending().isEmpty();
    }

    private boolean terminalRollbackMatches(
            PopulationDomainPort domains,
            OperationEnvelope operation
    ) {
        PopulationDomainPort.ProfileEvidence evidence = domains.profileEvidence(
                plan.profileId(), operation.operationId()
        );
        List<PopulationDomainReservation> expected = sorted(plan.sourceRows().stream()
                .map(PopulationDomainConvergencePlan.SourceRow::expected)
                .toList());
        return samePersisted(sorted(evidence.committed()), expected)
                && evidence.currentOperationPending().isEmpty()
                && evidence.foreignPending().isEmpty();
    }

    private boolean durableMatches(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation
    ) {
        if (residualMatches(transaction.populationDomains(), operation)) {
            return true;
        }
        if (operation.phase() != OperationPhase.DURABLE
                && operation.phase() != OperationPhase.PUBLISHED) {
            return false;
        }
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(plan.profileId())
                .orElse(null);
        return current != null
                && current.revision().value()
                > plan.sourceLifecycleRevision().value()
                && current.activeOperationId() == null
                && !current.quarantined()
                && !transaction.outbox().findByOperation(
                operation.operationId()
        ).isEmpty()
                && residualIdentityMatches(
                transaction.populationDomains().profileEvidence(
                        plan.profileId(), operation.operationId()
                )
        );
    }

    private boolean residualMatches(
            PopulationDomainPort domains,
            OperationEnvelope operation
    ) {
        PopulationDomainPort.ProfileEvidence evidence = domains.profileEvidence(
                plan.profileId(), operation.operationId()
        );
        ArrayList<PopulationDomainReservation> expected = new ArrayList<>();
        plan.sourceRows().stream()
                .map(PopulationDomainConvergencePlan.SourceRow::residualOrNull)
                .filter(Objects::nonNull)
                .forEach(expected::add);
        expected.addAll(plan.targetReservations());
        return samePersisted(sorted(evidence.committed()), sorted(expected))
                && evidence.currentOperationPending().isEmpty()
                && evidence.foreignPending().isEmpty();
    }

    private boolean residualIdentityMatches(
            PopulationDomainPort.ProfileEvidence evidence
    ) {
        if (!evidence.currentOperationPending().isEmpty()
                || !evidence.foreignPending().isEmpty()
                || evidence.committed().size()
                > plan.sourceRows().size() + plan.targetReservations().size()) {
            return false;
        }
        ArrayList<PopulationDomainReservation> allowed = new ArrayList<>();
        plan.sourceRows().stream()
                .map(PopulationDomainConvergencePlan.SourceRow::expected)
                .forEach(allowed::add);
        allowed.addAll(plan.targetReservations());
        boolean[] matched = new boolean[allowed.size()];
        for (PopulationDomainReservation actual : evidence.committed()) {
            boolean found = false;
            for (int index = 0; index < allowed.size(); index++) {
                if (!matched[index]
                        && sameResidualIdentity(actual, allowed.get(index))) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private boolean sameResidualIdentity(
            PopulationDomainReservation actual,
            PopulationDomainReservation expected
    ) {
        return actual.operationId().equals(expected.operationId())
                && actual.profileId().equals(expected.profileId())
                && Objects.equals(
                actual.expectedLifecycleRevision(),
                expected.expectedLifecycleRevision()
        )
                && actual.bucket().equals(expected.bucket())
                && actual.weight() == expected.weight()
                && actual.snapshottedMaxOwned()
                == expected.snapshottedMaxOwned()
                && actual.snapshottedMaxDeployable()
                == expected.snapshottedMaxDeployable()
                && actual.policyRevision() == expected.policyRevision()
                && actual.createdAtMs() == expected.createdAtMs();
    }

    private void requireOperation(OperationEnvelope operation) {
        if (operation == null
                || !Objects.equals(
                operation.expectedLifecycleRevision(),
                plan.sourceLifecycleRevision()
        )) {
            throw new IllegalArgumentException(
                    "Domain convergence plan must match operation revision"
            );
        }
    }

    private List<PopulationDomainReservation> sorted(
            List<PopulationDomainReservation> rows
    ) {
        if (rows == null || rows.stream().anyMatch(Objects::isNull)) {
            return List.of();
        }
        return rows.stream()
                .sorted(Comparator.comparing(
                                (PopulationDomainReservation row) -> row.operationId().toString()
                        )
                        .thenComparing(PopulationDomainReservation::bucket))
                .toList();
    }

    private boolean samePersisted(
            List<PopulationDomainReservation> actual,
            List<PopulationDomainReservation> expected
    ) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < actual.size(); index++) {
            PopulationDomainReservation left = actual.get(index);
            PopulationDomainReservation right = expected.get(index);
            if (!left.operationId().equals(right.operationId())
                    || !left.profileId().equals(right.profileId())
                    || !Objects.equals(
                    left.expectedLifecycleRevision(),
                    right.expectedLifecycleRevision()
            )
                    || !left.bucket().equals(right.bucket())
                    || left.ownedDelta() != right.ownedDelta()
                    || left.deployableDelta() != right.deployableDelta()
                    || left.weight() != right.weight()
                    || left.snapshottedMaxOwned()
                    != right.snapshottedMaxOwned()
                    || left.snapshottedMaxDeployable()
                    != right.snapshottedMaxDeployable()
                    || left.policyRevision() != right.policyRevision()
                    || left.createdAtMs() != right.createdAtMs()) {
                return false;
            }
        }
        return true;
    }
}
