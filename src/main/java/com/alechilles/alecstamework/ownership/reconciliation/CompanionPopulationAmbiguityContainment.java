package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts bounded startup-operation ambiguities into durable operation/profile quarantine fences.
 *
 * <p>The existing population journal remains nonterminal and conservatively counted. Reconciliation
 * may publish healthy unrelated owner scopes only after every corresponding v7 quarantine is
 * durable.</p>
 */
public final class CompanionPopulationAmbiguityContainment {
    @Nullable
    private final PersistenceIncidentReporter incidents;
    @Nullable
    private final PersistenceScopeFactory scopes;

    private CompanionPopulationAmbiguityContainment() {
        this.incidents = null;
        this.scopes = null;
    }

    public CompanionPopulationAmbiguityContainment(
            @Nonnull PersistenceIncidentReporter incidents,
            @Nonnull PersistenceScopeFactory scopes
    ) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    @Nonnull
    static CompanionPopulationAmbiguityContainment disabled() {
        return new CompanionPopulationAmbiguityContainment();
    }

    boolean enabled() {
        return incidents != null && scopes != null;
    }

    /** Opens exact durable fences and reports whether every fence commit succeeded. */
    @Nonnull
    public CompletableFuture<Boolean> containAsync(
            @Nonnull List<CompanionPopulationOperationRecoveryService.AmbiguousOperation> ambiguous
    ) {
        Objects.requireNonNull(ambiguous, "ambiguous");
        if (!enabled()) {
            return CompletableFuture.completedFuture(false);
        }
        if (ambiguous.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        PersistenceIncidentReporter reporter = Objects.requireNonNull(incidents);
        PersistenceScopeFactory scopeFactory = Objects.requireNonNull(scopes);
        List<CompletableFuture<Boolean>> durable = new ArrayList<>();
        try {
            for (var operation : ambiguous) {
                List<PersistenceScope> exactScopes = List.of(
                        scopeFactory.operation(operation.operationId()),
                        scopeFactory.profile(operation.profileId())
                );
                PersistenceFailureContext context = new PersistenceFailureContext(
                        normalize(operation.reason()),
                        PersistenceDomain.RECONCILIATION,
                        PersistenceOperationPhase.RECOVERY,
                        PersistenceTransactionOutcome.NOT_STARTED,
                        exactScopes,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        operation.operationId(),
                        null
                );
                durable.add(reporter.report(context).durableCompletion());
            }
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.allOf(durable.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> failure == null
                        && durable.stream().allMatch(future -> Boolean.TRUE.equals(future.join())));
    }

    @Nonnull
    private static String normalize(@Nonnull String reason) {
        String normalized = reason.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "reconciliation_operation_ambiguous" : normalized;
    }
}
