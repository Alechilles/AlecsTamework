package com.alechilles.alecstamework.companion.capture;

import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Frozen command-config identity and access item set checked before capture entropy. */
public record CaptureCommandAccessEvidence(
        @Nonnull String configId,
        long configRevision,
        @Nonnull String commandFamilyId,
        @Nonnull List<String> accessItemIds
) {
    public CaptureCommandAccessEvidence {
        configId = text(configId, "Command config ID");
        commandFamilyId = text(
                commandFamilyId, "Command family ID"
        );
        if (configRevision < 0 || accessItemIds == null
                || accessItemIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Complete command access evidence is required"
            );
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String itemId : accessItemIds) {
            normalized.add(text(itemId, "Command access item ID"));
        }
        accessItemIds = List.copyOf(normalized);
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
