package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * A loan of avatar flight. {@code lenderId} names the plugin or feature that owns the loan; only it
 * may end the loan or add Vigour. The player is put at the return point, in the world the loan started
 * in, when the loan ends; not after death, where respawn places them, or a world change.
 */
public record AvatarFlightLoanRequest(
        @Nonnull String lenderId,
        @Nonnull String configId,
        double returnX,
        double returnY,
        double returnZ,
        float returnYaw
) {
    public AvatarFlightLoanRequest {
        lenderId = requireText(lenderId, "lenderId");
        configId = requireText(configId, "configId");
        if (!Double.isFinite(returnX) || !Double.isFinite(returnY) || !Double.isFinite(returnZ)
                || !Float.isFinite(returnYaw)) {
            throw new IllegalArgumentException("return point must be finite");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
