package com.alechilles.alecstamework.companion.index;

import java.util.Objects;
import javax.annotation.Nonnull;

/** A named admission-domain claim returned by an admission provider (spec 8.10). */
public record DomainClaim(@Nonnull String domainId, int weight, boolean owned, boolean deployable) {
    public DomainClaim {
        Objects.requireNonNull(domainId, "domainId");
        if (weight <= 0) {
            throw new IllegalArgumentException("a domain claim's weight must be positive");
        }
    }
}
