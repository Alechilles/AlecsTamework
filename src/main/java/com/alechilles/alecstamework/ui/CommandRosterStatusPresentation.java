package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Authoritative per-row roster, lease, cooldown, and active-cap presentation. */
public record CommandRosterStatusPresentation(
        @Nonnull String profileId,
        @Nonnull String commandFamilyId,
        @Nonnull CommandTimedSummoningState state,
        long revision,
        @Nullable Long remainingMs,
        long configuredDurationMs,
        boolean unlimitedDuration,
        long cooldownRemainingMs,
        int activeCount,
        int activeLimit,
        @Nullable String blockingGroupId,
        @Nullable String blockingReason
) {
    public CommandRosterStatusPresentation {
        profileId = requireText(profileId, "profileId");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        state = Objects.requireNonNull(state, "state");
        if (revision < 0L || remainingMs != null && remainingMs < 0L || configuredDurationMs < 0L
                || cooldownRemainingMs < 0L || activeCount < 0 || activeLimit < 0) {
            throw new IllegalArgumentException("roster status values cannot be negative");
        }
        blockingGroupId = normalize(blockingGroupId);
        blockingReason = normalize(blockingReason);
    }

    public boolean capUnlimited() { return activeLimit == 0; }
    public boolean capBlocked() { return activeLimit > 0 && activeCount >= activeLimit; }
    public boolean summonVisible() { return state == CommandTimedSummoningState.ROSTER_STORED; }
    public boolean summonEnabled() { return summonVisible() && cooldownRemainingMs == 0L && !capBlocked(); }
    public boolean dismissVisible() {
        return state == CommandTimedSummoningState.ACTIVE
                || state == CommandTimedSummoningState.UNLOADED;
    }
    public boolean dismissEnabled() { return dismissVisible(); }
    public boolean reviveCapBlocked() {
        return state == CommandTimedSummoningState.DEAD_REVIVABLE && capBlocked();
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
