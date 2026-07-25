package com.alechilles.alecstamework.config.assets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Role-scoped balance and payment settings for paid command revival. */
public final class TwCompanionReviveSettings {
    static final boolean DEFAULT_ENABLED = true;
    static final long DEFAULT_GAMEPLAY_COOLDOWN_MS = 10_000L;

    private boolean enabled = DEFAULT_ENABLED;
    private long gameplayCooldownMs = DEFAULT_GAMEPLAY_COOLDOWN_MS;
    private TwItemCostComponent[] costs = TwItemCostComponent.EMPTY_ARRAY;
    private String insufficientCostMessage;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the non-negative balance duration applied to the signed world-time death instant.
     *
     * <p>Zero means no additional gameplay delay. It is a duration, not a timestamp sentinel.
     */
    public long getGameplayCooldownMs() {
        return gameplayCooldownMs;
    }

    @Nonnull
    public TwItemCostComponent[] getCosts() {
        return TwItemCostComponent.validateAndCopy(costs);
    }

    @Nullable
    public String getInsufficientCostMessage() {
        return insufficientCostMessage;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setGameplayCooldownMs(long gameplayCooldownMs) {
        if (gameplayCooldownMs < 0L) {
            throw new IllegalArgumentException(
                    "Revive GameplayCooldownMs must not be negative."
            );
        }
        this.gameplayCooldownMs = gameplayCooldownMs;
    }

    void setCosts(@Nullable TwItemCostComponent[] costs) {
        this.costs = TwItemCostComponent.validateAndCopy(costs);
    }

    void setInsufficientCostMessage(@Nullable String message) {
        this.insufficientCostMessage = normalizeOptional(message);
    }

    @Nonnull
    TwCompanionReviveSettings copy() {
        TwCompanionReviveSettings copy =
                new TwCompanionReviveSettings();
        copy.enabled = enabled;
        copy.gameplayCooldownMs = gameplayCooldownMs;
        copy.costs = TwItemCostComponent.validateAndCopy(costs);
        copy.insufficientCostMessage = insufficientCostMessage;
        return copy;
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
