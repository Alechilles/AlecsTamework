package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure claim-cap and remaining-headroom math.
 */
public final class ClaimCapEvaluator {
    private ClaimCapEvaluator() {
    }

    @Nonnull
    public static Evaluation evaluate(int limitPerClaimChunk,
                                      int limitPerClaimTotal,
                                      int claimChunkCount,
                                      long committedPopulation,
                                      long pendingPopulation) {
        int safePerChunk = Math.max(0, limitPerClaimChunk);
        int safeTotal = Math.max(0, limitPerClaimTotal);
        long committed = Math.max(0L, committedPopulation);
        long pending = Math.max(0L, pendingPopulation);
        if (safePerChunk <= 0 && safeTotal <= 0) {
            return new Evaluation(
                    false, true, 0L, 0L, 0L, committed, pending,
                    Long.MAX_VALUE, LimitingConstraint.NONE, null
            );
        }
        if (safePerChunk > 0 && claimChunkCount <= 0) {
            return new Evaluation(
                    true, false, 0L, safeTotal, 0L, committed, pending,
                    0L, LimitingConstraint.PER_CHUNK, "claim-footprint-required"
            );
        }

        long chunkCapacity = safePerChunk <= 0 ? 0L : (long) safePerChunk * claimChunkCount;
        long totalCapacity = safeTotal;
        long effectiveCapacity = effectiveCapacity(chunkCapacity, totalCapacity);
        LimitingConstraint limiting = limitingConstraint(chunkCapacity, totalCapacity);
        long headroom = remaining(effectiveCapacity, committed, pending);
        return new Evaluation(
                true, true, chunkCapacity, totalCapacity, effectiveCapacity,
                committed, pending, headroom, limiting, null
        );
    }

    private static long effectiveCapacity(long chunkCapacity, long totalCapacity) {
        if (chunkCapacity <= 0L) {
            return totalCapacity;
        }
        if (totalCapacity <= 0L) {
            return chunkCapacity;
        }
        return Math.min(chunkCapacity, totalCapacity);
    }

    @Nonnull
    private static LimitingConstraint limitingConstraint(long chunkCapacity, long totalCapacity) {
        if (chunkCapacity <= 0L) {
            return LimitingConstraint.TOTAL;
        }
        if (totalCapacity <= 0L) {
            return LimitingConstraint.PER_CHUNK;
        }
        if (chunkCapacity == totalCapacity) {
            return LimitingConstraint.TIED;
        }
        return chunkCapacity < totalCapacity ? LimitingConstraint.PER_CHUNK : LimitingConstraint.TOTAL;
    }

    private static long remaining(long capacity, long committed, long pending) {
        if (committed >= capacity) {
            return 0L;
        }
        long afterCommitted = capacity - committed;
        return pending >= afterCommitted ? 0L : afterCommitted - pending;
    }

    public enum LimitingConstraint {
        NONE,
        PER_CHUNK,
        TOTAL,
        TIED
    }

    /** Immutable diagnostics and admission math for one claim snapshot. */
    public record Evaluation(boolean active,
                             boolean valid,
                             long perChunkCapacity,
                             long totalCapacity,
                             long effectiveCapacity,
                             long committedPopulation,
                             long pendingPopulation,
                             long remainingHeadroom,
                             @Nonnull LimitingConstraint limitingConstraint,
                             @Nullable String reason) {
        public boolean admits(long requestedSlots) {
            return valid && Math.max(0L, requestedSlots) <= remainingHeadroom;
        }
    }
}
