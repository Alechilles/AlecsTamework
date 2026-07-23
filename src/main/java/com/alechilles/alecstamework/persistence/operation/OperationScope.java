package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;

/**
 * Typed participant scope used to fence an operation and contain failures.
 *
 * @param type scope category
 * @param key stable category-specific key
 */
public record OperationScope(@Nonnull OperationScopeType type, @Nonnull String key)
        implements Comparable<OperationScope> {
    private static final String GLOBAL_KEY = "*";

    public OperationScope {
        if (type == null) {
            throw new IllegalArgumentException("Operation scope type is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Operation scope key is required");
        }
        key = key.trim();
        if (type == OperationScopeType.GLOBAL && !GLOBAL_KEY.equals(key)) {
            throw new IllegalArgumentException("Global operation scope must use '*'");
        }
        if (type != OperationScopeType.GLOBAL && GLOBAL_KEY.equals(key)) {
            throw new IllegalArgumentException("Only global operation scope may use '*'");
        }
    }

    /** Creates an operation scope. */
    public static OperationScope operation(@Nonnull OperationId operationId) {
        return new OperationScope(OperationScopeType.OPERATION, require(operationId, "Operation ID").toString());
    }

    /** Creates a companion profile scope. */
    public static OperationScope profile(@Nonnull ProfileId profileId) {
        return new OperationScope(OperationScopeType.PROFILE, require(profileId, "Profile ID").toString());
    }

    /** Creates a companion owner scope. */
    public static OperationScope owner(@Nonnull OwnerId ownerId) {
        return new OperationScope(OperationScopeType.OWNER, require(ownerId, "Owner ID").toString());
    }

    /** Creates the singleton global scope. */
    public static OperationScope global() {
        return new OperationScope(OperationScopeType.GLOBAL, GLOBAL_KEY);
    }

    @Override
    public int compareTo(OperationScope other) {
        if (other == null) {
            throw new NullPointerException("Other operation scope is required");
        }
        int typeOrder = Integer.compare(type.ordinal(), other.type.ordinal());
        return typeOrder != 0 ? typeOrder : key.compareTo(other.key);
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
