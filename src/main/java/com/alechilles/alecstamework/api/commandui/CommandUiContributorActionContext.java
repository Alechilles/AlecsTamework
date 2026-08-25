package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable identities and validated input supplied to a contributor action
 * handler.
 *
 * <p>The context deliberately contains no Hytale component, ECS reference,
 * mutable profile, item stack, or gameplay callback. A handler resolves live
 * domain state from these stable identities on the current world thread.</p>
 */
public final class CommandUiContributorActionContext {
    private final UUID sessionId;
    private final UUID playerId;
    @Nullable
    private final String configId;
    @Nullable
    private final UUID rowId;
    @Nullable
    private final UUID companionId;
    @Nullable
    private final String profileId;
    @Nullable
    private final CommandUiValue input;
    private final boolean confirmed;

    /** Creates a detached contributor action execution context. */
    public CommandUiContributorActionContext(
            @Nonnull UUID sessionId,
            @Nonnull UUID playerId,
            @Nullable String configId,
            @Nullable UUID rowId,
            @Nullable UUID companionId,
            @Nullable String profileId,
            @Nullable CommandUiValue input,
            boolean confirmed
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.configId = normalize(configId);
        this.rowId = rowId;
        this.companionId = companionId;
        this.profileId = normalize(profileId);
        this.input = input;
        this.confirmed = confirmed;
    }

    @Nonnull
    public UUID sessionId() {
        return sessionId;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** Alias that makes the UUID nature of the player identity explicit. */
    @Nonnull
    public UUID playerUuid() {
        return playerId;
    }

    @Nullable
    public String configId() {
        return configId;
    }

    @Nullable
    public UUID rowId() {
        return rowId;
    }

    @Nullable
    public UUID companionId() {
        return companionId;
    }

    @Nullable
    public String profileId() {
        return profileId;
    }

    @Nullable
    public CommandUiValue input() {
        return input;
    }

    public boolean confirmed() {
        return confirmed;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
