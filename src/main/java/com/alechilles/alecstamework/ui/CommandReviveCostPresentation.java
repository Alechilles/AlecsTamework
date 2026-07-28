package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable linked-panel rendering of one server-authoritative revival quote. */
public record CommandReviveCostPresentation(
        @Nonnull PaidCommandRevivalQuote.Status status,
        long cooldownRemainingMs,
        @Nonnull List<CostLine> costs,
        @Nonnull String configRevision,
        @Nullable String messageKey,
        @Nullable String reason
) {
    public CommandReviveCostPresentation {
        status = Objects.requireNonNull(status, "status");
        if (cooldownRemainingMs < 0L) {
            throw new IllegalArgumentException(
                    "Revival cooldown cannot be negative."
            );
        }
        costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
        configRevision = requireText(configRevision, "configRevision");
        messageKey = normalize(messageKey);
        reason = normalize(reason);
    }

    public boolean affordable() {
        return costs.stream().allMatch(CostLine::satisfied);
    }

    public boolean confirmEnabled() {
        return status == PaidCommandRevivalQuote.Status.READY
                && affordable();
    }

    public boolean actionVisible() {
        return status == PaidCommandRevivalQuote.Status.READY
                || status == PaidCommandRevivalQuote.Status
                .INSUFFICIENT_COST;
    }

    public int missingComponentCount() {
        return (int) costs.stream()
                .filter(line -> !line.satisfied())
                .count();
    }

    /** One exact ordered cost line from the authoritative quote. */
    public record CostLine(
            @Nonnull String itemId,
            @Nonnull String localizedName,
            @Nullable String iconAssetId,
            int ownedQuantity,
            int requiredQuantity
    ) {
        public CostLine {
            itemId = requireText(itemId, "itemId");
            localizedName = requireText(localizedName, "localizedName");
            iconAssetId = normalize(iconAssetId);
            if (ownedQuantity < 0 || requiredQuantity <= 0) {
                throw new IllegalArgumentException(
                        "Revival cost quantities are invalid."
                );
            }
        }

        public int shortageQuantity() {
            return Math.max(0, requiredQuantity - ownedQuantity);
        }

        public boolean satisfied() {
            return shortageQuantity() == 0;
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
