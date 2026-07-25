package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Authoritative UI/API view of one timed command-roster session. */
public record CommandTimedSummoningView(
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        long revision,
        @Nonnull CommandTimedSummoningState state,
        @Nullable String summonSessionId,
        @Nullable Long remainingMs,
        boolean unlimited,
        long cooldownUntilMs,
        long updatedAtMs
) {
    public CommandTimedSummoningView {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        Objects.requireNonNull(state, "state");
        summonSessionId = normalize(summonSessionId);
        if (revision < 1L || (remainingMs != null && remainingMs < 0L)) {
            throw new IllegalArgumentException(
                    "Timed summon revision and remaining duration are invalid."
            );
        }
        if (unlimited != (remainingMs == null && isPotentiallyActive(state))) {
            throw new IllegalArgumentException("Unlimited is valid only for a projected session with no timer.");
        }
    }

    private static boolean isPotentiallyActive(CommandTimedSummoningState state) {
        return state == CommandTimedSummoningState.RESTORING
                || state == CommandTimedSummoningState.ACTIVE
                || state == CommandTimedSummoningState.UNLOADED
                || state == CommandTimedSummoningState.STORING;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
