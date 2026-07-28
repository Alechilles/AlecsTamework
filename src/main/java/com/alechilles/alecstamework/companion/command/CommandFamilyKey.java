package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import javax.annotation.Nonnull;

/** Stable owner-scoped command-family identity. */
public record CommandFamilyKey(
        @Nonnull OwnerId ownerId,
        @Nonnull String familyId
) implements Comparable<CommandFamilyKey> {
    public CommandFamilyKey {
        if (ownerId == null || familyId == null
                || familyId.isBlank()) {
            throw new IllegalArgumentException(
                    "Command family owner and ID are required"
            );
        }
        familyId = familyId.trim();
    }

    @Override
    public int compareTo(CommandFamilyKey other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other command family is required"
            );
        }
        int owner = ownerId.value().compareTo(other.ownerId().value());
        return owner != 0 ? owner : familyId.compareTo(other.familyId());
    }
}

