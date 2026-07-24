package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationOutcome;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/**
 * Applies one positive loaded-world observation to imported canonical profile state.
 *
 * <p>Any required alias rotation and the sole lifecycle revision advance occur in
 * the caller's shared operation transaction.</p>
 */
final class SqliteLoadedProfileReconciliation {
    private SqliteLoadedProfileReconciliation() {
    }

    @Nonnull
    static Result apply(
            @Nonnull SqlitePersistenceTransactionContext transaction,
            @Nonnull OperationId operationId,
            @Nonnull CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        Current current = loadCurrent(transaction, reconciliation);
        if (alreadyApplied(
                current.lifecycle(),
                current.alias(),
                reconciliation
        )) {
            return unchanged(current.identity(), reconciliation);
        }
        if (!matchesCurrent(
                current.lifecycle(),
                current.alias(),
                reconciliation
        )) {
            return unchanged(current.identity(), reconciliation);
        }
        return transition(transaction, operationId, reconciliation, current);
    }

    private static Current loadCurrent(
            SqlitePersistenceTransactionContext transaction,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        return new Current(
                transaction.identities().findProfile(reconciliation.profileId())
                        .orElseThrow(() -> failure("profile_missing")),
                transaction.lifecycles().findByProfile(reconciliation.profileId())
                        .orElseThrow(() -> failure("lifecycle_missing")),
                transaction.identities().findCurrentAlias(
                        reconciliation.profileId()
                ).orElse(null)
        );
    }

    private static Result transition(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionProfileMutation.ReconcileLoaded reconciliation,
            Current current
    ) {
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        reconciliation.profileId()
                );
        boolean aliasChanged = rotateAliasIfRequired(
                transaction,
                operationId,
                current.alias(),
                reconciliation
        );
        CompanionLifecycle next = reconciliation.resolvedLifecycle(
                current.lifecycle()
        );
        transitionLifecycle(transaction, current.lifecycle(), next);
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        reconciliation.profileId()
                );
        return changed(
                current,
                reconciliation,
                before,
                after,
                next,
                aliasChanged
        );
    }

    private static void transitionLifecycle(
            SqlitePersistenceTransactionContext transaction,
            CompanionLifecycle current,
            CompanionLifecycle next
    ) {
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                current.revision(),
                                current.activeOperationId(),
                                next
                        )
                ),
                "lifecycle_transition"
        );
    }

    private static Result changed(
            Current current,
            CompanionProfileMutation.ReconcileLoaded reconciliation,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            CompanionLifecycle next,
            boolean aliasChanged
    ) {
        return new Result(
                outcome(
                        CompanionProfileMutationOutcome.Status.UPDATED,
                        current.identity(),
                        reconciliation
                ),
                before,
                after,
                current.lifecycle(),
                next,
                aliasChanged
                        ? CompanionProfileProjectionChange.Source.ALIAS
                        : CompanionProfileProjectionChange.Source.LIFECYCLE,
                next.revision().value()
        );
    }

    private static boolean matchesCurrent(
            CompanionLifecycle lifecycle,
            CompanionAlias alias,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        if (lifecycle.quarantined()
                || lifecycle.activeOperationId() != null
                || !lifecycle.revision().equals(
                reconciliation.expectedLifecycleRevision()
        )
                || !lifecycle.lastReconciledGeneration().equals(
                reconciliation.expectedReconciliationGeneration()
                )
                || alias == null
                || alias.state() != CompanionAlias.State.CURRENT
                || !alias.alias().equals(
                reconciliation.expectedCurrentAlias()
        )) {
            return false;
        }
        if (lifecycle.state() == LifecycleState.UNRESOLVED) {
            return true;
        }
        return lifecycle.state() == LifecycleState.ACTIVE
                && reconciliation.observedAlias().equals(
                reconciliation.expectedCurrentAlias()
        )
                && lifecycle.location().key().equals(
                reconciliation.expectedCurrentAlias().toString()
        );
    }

    private static boolean rotateAliasIfRequired(
            SqlitePersistenceTransactionContext transaction,
            OperationId operationId,
            CompanionAlias currentAlias,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        if (currentAlias.alias().equals(reconciliation.observedAlias())) {
            return false;
        }
        if (transaction.identities()
                .resolveAlias(reconciliation.observedAlias()).isPresent()) {
            throw failure("observed_alias_conflict");
        }
        requireApplied(
                transaction.identities().leaseAlias(
                        reconciliation.profileId(),
                        reconciliation.observedAlias(),
                        operationId,
                        reconciliation.requestedAtMs()
                ),
                "observed_alias_lease"
        );
        requireApplied(
                transaction.identities().promoteAlias(
                        reconciliation.observedAlias(),
                        operationId,
                        reconciliation.requestedAtMs()
                ),
                "observed_alias_promote"
        );
        return true;
    }

    private static boolean alreadyApplied(
            CompanionLifecycle lifecycle,
            CompanionAlias alias,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        return lifecycle.state() == LifecycleState.ACTIVE
                && lifecycle.revision().equals(
                reconciliation.expectedLifecycleRevision().next()
        )
                && lifecycle.lastReconciledGeneration().equals(
                reconciliation.expectedReconciliationGeneration().next()
        )
                && lifecycle.location().key().equals(
                reconciliation.observedAlias().toString()
        )
                && lifecycle.location().worldKey().equals(
                reconciliation.worldKey()
        )
                && alias != null
                && alias.state() == CompanionAlias.State.CURRENT
                && alias.alias().equals(reconciliation.observedAlias());
    }

    private static Result unchanged(
            CompanionIdentity identity,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        return new Result(
                outcome(
                        CompanionProfileMutationOutcome.Status.UNCHANGED,
                        identity,
                        reconciliation
                ),
                null,
                null,
                null,
                null,
                CompanionProfileProjectionChange.Source.LIFECYCLE,
                reconciliation.expectedLifecycleRevision().next().value()
        );
    }

    private static CompanionProfileMutationOutcome outcome(
            CompanionProfileMutationOutcome.Status status,
            CompanionIdentity identity,
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        return new CompanionProfileMutationOutcome(
                status,
                reconciliation.profileId(),
                identity.metadataRevision(),
                reconciliation.requestedAtMs()
        );
    }

    private static <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String action
    ) {
        if (result == null || !result.applied()) {
            throw failure(
                    action + "_" + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(
                "loaded_profile_reconciliation_" + code
        );
    }

    record Result(
            @Nonnull CompanionProfileMutationOutcome outcome,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            CompanionLifecycle beforeLifecycle,
            CompanionLifecycle afterLifecycle,
            @Nonnull CompanionProfileProjectionChange.Source source,
            long sourceRevision
    ) {
    }

    private record Current(
            CompanionIdentity identity,
            CompanionLifecycle lifecycle,
            CompanionAlias alias
    ) {
    }
}
