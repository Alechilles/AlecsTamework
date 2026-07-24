package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Declares the mandatory and optional participant scope types for one
 * persistence operation.
 */
public record OperationScopePolicy(
        @Nonnull Set<OperationScopeType> required,
        @Nonnull Set<OperationScopeType> optional
) {
    public OperationScopePolicy {
        if (required == null || required.isEmpty() || optional == null
                || required.stream().anyMatch(java.util.Objects::isNull)
                || optional.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Complete operation scope policy is required"
            );
        }
        required = Set.copyOf(required);
        optional = Set.copyOf(optional);
        if (!java.util.Collections.disjoint(required, optional)) {
            throw new IllegalArgumentException(
                    "Required and optional operation scopes must be disjoint"
            );
        }
    }

    /** Creates a policy that requires exactly the supplied scope types. */
    public static OperationScopePolicy exact(
            @Nonnull Set<OperationScopeType> required
    ) {
        return new OperationScopePolicy(required, Set.of());
    }

    /** Returns every participant scope type admitted by this policy. */
    @Nonnull
    public Set<OperationScopeType> allowed() {
        HashSet<OperationScopeType> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        return Set.copyOf(allowed);
    }

    /** Returns whether the actual participant types satisfy this policy. */
    public boolean admits(@Nonnull Set<OperationScopeType> actual) {
        return actual != null
                && actual.containsAll(required)
                && actual.stream().allMatch(
                        type -> required.contains(type)
                                || optional.contains(type)
                );
    }
}
