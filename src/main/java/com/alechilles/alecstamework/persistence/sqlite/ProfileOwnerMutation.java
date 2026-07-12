package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Explicit tri-state profile-owner update used by atomic population transitions.
 */
public record ProfileOwnerMutation(@Nonnull Kind kind, @Nullable UUID ownerUuid) {
    public enum Kind {
        UNCHANGED,
        SET,
        CLEAR
    }

    public ProfileOwnerMutation {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.SET && ownerUuid == null) {
            throw new IllegalArgumentException("SET requires an owner UUID.");
        }
        if (kind != Kind.SET && ownerUuid != null) {
            throw new IllegalArgumentException(kind + " must not carry an owner UUID.");
        }
    }

    @Nonnull
    public static ProfileOwnerMutation unchanged() {
        return new ProfileOwnerMutation(Kind.UNCHANGED, null);
    }

    @Nonnull
    public static ProfileOwnerMutation set(@Nonnull UUID ownerUuid) {
        return new ProfileOwnerMutation(Kind.SET, Objects.requireNonNull(ownerUuid));
    }

    @Nonnull
    public static ProfileOwnerMutation clear() {
        return new ProfileOwnerMutation(Kind.CLEAR, null);
    }
}
