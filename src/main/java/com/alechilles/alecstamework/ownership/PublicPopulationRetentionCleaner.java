package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Bounds completed public capability/idempotency tombstones without touching in-flight work. */
final class PublicPopulationRetentionCleaner {
    private static final int MAX_RETAINED_RESULTS = 4_096;
    private static final int SCAN_LIMIT = 128;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5L);

    private final LongSupplier clock;

    PublicPopulationRetentionCleaner(@Nonnull LongSupplier clock) {
        this.clock = clock;
    }

    long retentionDeadline() {
        return retentionDeadlineAfter(clock.getAsLong());
    }

    long admissionRetentionDeadline(long tokenExpiryNanos) {
        return retentionDeadlineAfter(tokenExpiryNanos);
    }

    private static long retentionDeadlineAfter(long baseNanos) {
        return baseNanos > Long.MAX_VALUE - RETENTION_NANOS
                ? Long.MAX_VALUE : baseNanos + RETENTION_NANOS;
    }

    void prune(
            Map<UUID, PublicPopulationAdmissionRecord> admissions,
            Map<String, ? extends RetainedPreparation> singles,
            Map<String, ? extends RetainedPreparation> batches
    ) {
        long now = clock.getAsLong();
        int scanned = 0;
        for (Map.Entry<UUID, PublicPopulationAdmissionRecord> entry : admissions.entrySet()) {
            if (scanned++ >= SCAN_LIMIT) break;
            PublicPopulationAdmissionRecord record = entry.getValue();
            if (record.terminal()
                    && now >= record.token().expiresAtMonotonicNanos()
                    && now >= record.retainUntilNanos()) {
                admissions.remove(entry.getKey(), record);
            }
        }
        prunePreparations(singles, admissions, now);
        prunePreparations(batches, admissions, now);
    }

    private static void prunePreparations(
            Map<String, ? extends RetainedPreparation> preparations,
            Map<UUID, PublicPopulationAdmissionRecord> admissions,
            long now
    ) {
        int scanned = 0;
        for (Map.Entry<String, ? extends RetainedPreparation> entry : preparations.entrySet()) {
            if (scanned++ >= SCAN_LIMIT) break;
            RetainedPreparation value = entry.getValue();
            if (removable(value, admissions) && now >= value.retainUntilNanos()) {
                preparations.remove(entry.getKey(), value);
            }
        }
        int excess = preparations.size() - MAX_RETAINED_RESULTS;
        if (excess <= 0) return;
        for (Map.Entry<String, ? extends RetainedPreparation> entry : preparations.entrySet()) {
            if (excess <= 0) break;
            RetainedPreparation value = entry.getValue();
            if (removable(value, admissions) && preparations.remove(entry.getKey(), value)) {
                excess--;
            }
        }
    }

    private static boolean removable(
            RetainedPreparation preparation,
            Map<UUID, PublicPopulationAdmissionRecord> admissions
    ) {
        CompletableFuture<?> future = preparation.future();
        if (!future.isDone()) {
            return false;
        }
        Object result;
        try {
            result = future.getNow(null);
        } catch (RuntimeException failure) {
            return true;
        }
        if (result instanceof PopulationAdmissionDecision decision) {
            return terminal(decision, admissions);
        }
        if (result instanceof PopulationBatchAdmissionDecision batch) {
            for (PopulationAdmissionDecision decision : batch.unitDecisions()) {
                if (!terminal(decision, admissions)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean terminal(
            PopulationAdmissionDecision decision,
            Map<UUID, PublicPopulationAdmissionRecord> admissions
    ) {
        if (decision.token() == null) {
            return true;
        }
        PublicPopulationAdmissionRecord record = admissions.get(decision.token().operationId());
        return record == null || record.terminal();
    }

    interface RetainedPreparation {
        CompletableFuture<?> future();

        long retainUntilNanos();
    }
}
