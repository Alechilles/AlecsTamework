package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable identity for one per-player breeding population-cap scope. */
public record BreedingPlayerCapacityScope(@Nonnull Scope scope,
                                          @Nullable String worldId,
                                          @Nonnull UUID ownerId) implements Comparable<BreedingPlayerCapacityScope> {
    public BreedingPlayerCapacityScope {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ownerId, "ownerId");
        worldId = normalizeOptional(worldId);
        if (scope == Scope.PER_WORLD && worldId == null) {
            throw new IllegalArgumentException("PER_WORLD scope requires worldId");
        }
        if (scope == Scope.GLOBAL && worldId != null) {
            throw new IllegalArgumentException("GLOBAL scope must not include worldId");
        }
    }

    @Nonnull
    public static BreedingPlayerCapacityScope perWorld(@Nonnull String worldId, @Nonnull UUID ownerId) {
        return new BreedingPlayerCapacityScope(Scope.PER_WORLD, worldId, ownerId);
    }

    @Nonnull
    public static BreedingPlayerCapacityScope global(@Nonnull UUID ownerId) {
        return new BreedingPlayerCapacityScope(Scope.GLOBAL, null, ownerId);
    }

    @Override
    public int compareTo(@Nonnull BreedingPlayerCapacityScope other) {
        Objects.requireNonNull(other, "other");
        int scopeOrder = scope.compareTo(other.scope);
        if (scopeOrder != 0) {
            return scopeOrder;
        }
        int worldOrder = nullSafe(worldId).compareTo(nullSafe(other.worldId));
        return worldOrder != 0 ? worldOrder : ownerId.compareTo(other.ownerId);
    }

    private static String nullSafe(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Mirrors the configured per-player capacity boundary without depending on config classes. */
    public enum Scope {
        PER_WORLD,
        GLOBAL
    }
}
