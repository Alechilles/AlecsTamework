package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Bounded asynchronous cleanup of expired public capabilities and their durable journals. */
final class PublicPopulationExpiredAdmissionCleaner {
    private static final int CLEANUP_LIMIT = 64;

    private final Map<UUID, PublicPopulationAdmissionRecord> admissions;
    private final LongSupplier monotonicClock;

    PublicPopulationExpiredAdmissionCleaner(
            @Nonnull Map<UUID, PublicPopulationAdmissionRecord> admissions,
            @Nonnull LongSupplier monotonicClock
    ) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    @Nonnull
    CompletionStage<Integer> cleanup(
            @Nonnull Function<PopulationAdmissionToken, CompletionStage<PopulationAdmissionDecision>> canceler,
            @Nonnull BiConsumer<PublicPopulationAdmissionRecord, PublicPopulationAdmissionRecord.State>
                    staleNonterminal
    ) {
        Objects.requireNonNull(canceler, "canceler");
        Objects.requireNonNull(staleNonterminal, "staleNonterminal");
        long now = monotonicClock.getAsLong();
        List<PopulationAdmissionToken> expired = new ArrayList<>();
        List<Quarantine> quarantined = new ArrayList<>();
        for (PublicPopulationAdmissionRecord record : admissions.values()) {
            if (expired.size() + quarantined.size() >= CLEANUP_LIMIT) {
                break;
            }
            synchronized (record) {
                if (record.state() == PublicPopulationAdmissionRecord.State.RESERVED
                        && now >= record.token().expiresAtMonotonicNanos()) {
                    expired.add(record.token());
                } else if (now >= record.token().expiresAtMonotonicNanos()) {
                    PublicPopulationAdmissionRecord.State state =
                            record.quarantineExpiredNonterminal();
                    if (state != null) quarantined.add(new Quarantine(record, state));
                }
            }
        }
        for (Quarantine quarantine : quarantined) {
            try {
                staleNonterminal.accept(quarantine.record(), quarantine.state());
            } catch (RuntimeException | LinkageError ignored) {
                // The nonterminal capability remains conservative even if diagnostics fail.
            }
        }
        List<CompletableFuture<PopulationAdmissionDecision>> cancellations = new ArrayList<>(expired.size());
        for (PopulationAdmissionToken token : expired) {
            try {
                CompletionStage<PopulationAdmissionDecision> stage = canceler.apply(token);
                CompletableFuture<PopulationAdmissionDecision> future = stage == null
                        ? CompletableFuture.completedFuture(null)
                        : stage.toCompletableFuture().exceptionally(failure -> null);
                cancellations.add(future);
            } catch (RuntimeException | LinkageError failure) {
                cancellations.add(CompletableFuture.completedFuture(null));
            }
        }
        CompletableFuture<?>[] waits = cancellations.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).thenApply(ignored -> canceledCount(cancellations));
    }

    private record Quarantine(PublicPopulationAdmissionRecord record,
                              PublicPopulationAdmissionRecord.State state) {
    }

    private static int canceledCount(List<CompletableFuture<PopulationAdmissionDecision>> futures) {
        int canceled = 0;
        for (CompletableFuture<PopulationAdmissionDecision> future : futures) {
            PopulationAdmissionDecision decision = future.getNow(null);
            if (decision != null && decision.status() == PopulationAdmissionDecision.Status.CANCELED) {
                canceled++;
            }
        }
        return canceled;
    }
}
