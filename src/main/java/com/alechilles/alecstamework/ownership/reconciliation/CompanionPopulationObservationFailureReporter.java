package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.LegacyGlobalPersistenceFailureBridge;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentReporter;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Preserves and reports the first terminal live-population observation failure. */
final class CompanionPopulationObservationFailureReporter {
    private final PersistenceHealthService persistenceHealth;
    private final PersistenceIncidentReporter incidents;
    private final PersistenceScopeFactory scopes;
    private volatile Consumer<String> warningSink;

    CompanionPopulationObservationFailureReporter(
            @Nonnull PersistenceHealthService persistenceHealth) {
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
        this.incidents = null;
        this.scopes = null;
    }

    CompanionPopulationObservationFailureReporter(
            @Nonnull PersistenceHealthService persistenceHealth,
            @Nonnull PersistenceIncidentReporter incidents,
            @Nonnull PersistenceScopeFactory scopes) {
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    void setWarningSink(@Nullable Consumer<String> warningSink) {
        this.warningSink = warningSink;
    }

    boolean report(@Nonnull CompanionPopulationObservation observation,
                   @Nonnull String healthReason,
                   @Nonnull String status,
                   @Nonnull String detail) {
        boolean scoped = incidents != null && scopes != null;
        if (scoped) {
            reportScoped(observation, healthReason, status);
        } else if (!LegacyGlobalPersistenceFailureBridge.markDegraded(
                persistenceHealth, healthReason)) {
            return false;
        }
        Consumer<String> sink = warningSink;
        if (sink == null) {
            return scoped;
        }
        try {
            sink.accept(message(observation, status, detail));
        } catch (RuntimeException ignored) {
            // The persistence quarantine must not depend on its diagnostic sink.
        }
        return scoped;
    }

    private void reportScoped(CompanionPopulationObservation observation,
                              String reason,
                              String status) {
        ArrayList<PersistenceScope> exact = new ArrayList<>();
        exact.add(scopes.profile(observation.profileId()));
        if (observation.ownerUuid() != null) {
            exact.add(scopes.ownerGlobal(observation.ownerUuid()));
            exact.add(scopes.ownerWorld(
                    observation.ownerUuid(), observation.ownershipWorldName()));
        }
        if (observation.physicalWorldName() != null) {
            exact.add(scopes.world(observation.physicalWorldName()));
        }
        boolean committed = "IDENTITY_CACHE_CONFLICT".equals(status);
        incidents.report(new PersistenceFailureContext(
                normalize(reason), PersistenceDomain.RECONCILIATION,
                committed ? PersistenceOperationPhase.PUBLICATION : PersistenceOperationPhase.COMMIT,
                committed ? PersistenceTransactionOutcome.COMMITTED
                        : PersistenceTransactionOutcome.NOT_STARTED,
                exact, true, true, false, false, false,
                reason.contains("identity"), false, true, null, null));
    }

    private String normalize(String reason) {
        return reason.trim().replace('-', '_').replace(':', '_');
    }

    @Nonnull
    static String failureDetail(@Nonnull Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
    }

    @Nonnull
    private String message(@Nonnull CompanionPopulationObservation observation,
                           @Nonnull String status,
                           @Nonnull String reason) {
        return "Companion population observation failed: profile=" + observation.profileId()
                + " npc=" + observation.currentNpcUuid()
                + " lifecycle=" + observation.lifecycleState()
                + " source=" + observation.source()
                + " status=" + status
                + " reason=" + reason;
    }
}
