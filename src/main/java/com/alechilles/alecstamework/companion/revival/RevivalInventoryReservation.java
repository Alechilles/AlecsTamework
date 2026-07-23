package com.alechilles.alecstamework.companion.revival;

import javax.annotation.Nonnull;

/** Exact source-stack evidence for one portion of a frozen revival cost. */
public record RevivalInventoryReservation(
        int costOrdinal,
        int stackOrdinal,
        @Nonnull String compartmentId,
        int slotIndex,
        int quantity,
        @Nonnull String sourceStackFingerprint,
        long reservationGeneration
) implements Comparable<RevivalInventoryReservation> {
    public RevivalInventoryReservation {
        if (costOrdinal < 0 || stackOrdinal < 0 || slotIndex < 0
                || quantity <= 0 || reservationGeneration < 0) {
            throw new IllegalArgumentException(
                    "Revival reservation coordinates are invalid"
            );
        }
        compartmentId = text(
                compartmentId, "Revival reservation compartment"
        );
        sourceStackFingerprint = text(
                sourceStackFingerprint,
                "Revival reservation stack fingerprint"
        );
    }

    @Override
    public int compareTo(RevivalInventoryReservation other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other revival reservation is required"
            );
        }
        int cost = Integer.compare(costOrdinal, other.costOrdinal);
        if (cost != 0) {
            return cost;
        }
        return Integer.compare(stackOrdinal, other.stackOrdinal);
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
