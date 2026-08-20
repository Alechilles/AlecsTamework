package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact owner, domain, and world bucket used by one reservation row. */
public record PopulationDomainBucket(
        @Nonnull OwnerId ownerId,
        @Nonnull String domainId,
        @Nonnull PopulationDomainScope scope,
        @Nullable String ownerWorldKey
) implements Comparable<PopulationDomainBucket> {
    public PopulationDomainBucket {
        if (ownerId == null || domainId == null || domainId.isBlank()
                || scope == null) {
            throw new IllegalArgumentException("Complete domain bucket is required");
        }
        domainId = domainId.trim();
        ownerWorldKey = normalize(ownerWorldKey);
        if ((scope == PopulationDomainScope.PER_WORLD)
                != (ownerWorldKey != null)) {
            throw new IllegalArgumentException(
                    "Only per-world domain buckets carry an owner world"
            );
        }
    }

    @Nonnull
    public String storedWorldKey() {
        return ownerWorldKey == null ? "" : ownerWorldKey;
    }

    @Override
    public int compareTo(PopulationDomainBucket other) {
        if (other == null) {
            throw new NullPointerException("Other domain bucket is required");
        }
        int owner = ownerId.toString().compareTo(other.ownerId.toString());
        if (owner != 0) {
            return owner;
        }
        int domain = domainId.compareTo(other.domainId);
        if (domain != 0) {
            return domain;
        }
        int scopeOrder = scope.compareTo(other.scope);
        if (scopeOrder != 0) {
            return scopeOrder;
        }
        if (ownerWorldKey == null) {
            return other.ownerWorldKey == null ? 0 : -1;
        }
        return other.ownerWorldKey == null
                ? 1
                : ownerWorldKey.compareTo(other.ownerWorldKey);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
