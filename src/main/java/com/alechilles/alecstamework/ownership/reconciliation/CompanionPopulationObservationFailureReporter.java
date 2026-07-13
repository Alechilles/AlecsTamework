package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Preserves and reports the first terminal live-population observation failure. */
final class CompanionPopulationObservationFailureReporter {
    private final PersistenceHealthService persistenceHealth;
    private volatile Consumer<String> warningSink;

    CompanionPopulationObservationFailureReporter(
            @Nonnull PersistenceHealthService persistenceHealth) {
        this.persistenceHealth = Objects.requireNonNull(persistenceHealth, "persistenceHealth");
    }

    void setWarningSink(@Nullable Consumer<String> warningSink) {
        this.warningSink = warningSink;
    }

    void report(@Nonnull CompanionPopulationObservation observation,
                @Nonnull String healthReason,
                @Nonnull String status,
                @Nonnull String detail) {
        if (!persistenceHealth.markDegraded(healthReason)) {
            return;
        }
        Consumer<String> sink = warningSink;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(message(observation, status, detail));
        } catch (RuntimeException ignored) {
            // The persistence quarantine must not depend on its diagnostic sink.
        }
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
