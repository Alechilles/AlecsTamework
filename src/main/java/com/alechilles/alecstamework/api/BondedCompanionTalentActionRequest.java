package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Revision-fenced request to change talents held by a durable bonded profile.
 *
 * <p>The request names an action rather than accepting arbitrary talent state,
 * so the bonded authority remains responsible for validating levels, costs,
 * prerequisites, and the configured talent tree.</p>
 */
public record BondedCompanionTalentActionRequest(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String profileId,
        long expectedRevision,
        @Nonnull Action action,
        @Nullable String talentId
) {
    public enum Action { PURCHASE, RESET }

    public BondedCompanionTalentActionRequest {
        callerNamespace = required(callerNamespace, "callerNamespace");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = required(rosterId, "rosterId");
        profileId = required(profileId, "profileId");
        action = Objects.requireNonNull(action, "action");
        talentId = optional(talentId);
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision cannot be negative");
        }
        if (action == Action.PURCHASE && talentId == null) {
            throw new IllegalArgumentException("talentId is required for purchase");
        }
        if (action == Action.RESET && talentId != null) {
            throw new IllegalArgumentException("talentId is not used for reset");
        }
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
