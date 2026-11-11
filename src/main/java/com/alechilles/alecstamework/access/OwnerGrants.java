package com.alechilles.alecstamework.access;

import com.alechilles.alecstamework.api.access.AccessLevel;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Everything one owner has chosen to share: named players, opted-in groups, and the companions
 * kept private. Immutable; each change returns a new value.
 *
 * @param players           grants to named players, by player id
 * @param groups            level per opted-in group, by {@code AccessGroupProvider} id
 * @param privateCompanions profile ids of companions no grant applies to
 */
public record OwnerGrants(@Nonnull Map<UUID, PlayerGrant> players,
                          @Nonnull Map<String, AccessLevel> groups,
                          @Nonnull Set<UUID> privateCompanions) {
    public static final OwnerGrants EMPTY = new OwnerGrants(Map.of(), Map.of(), Set.of());

    /** A grant to one named player. The name is the one shown when the grant was made. */
    public record PlayerGrant(@Nonnull UUID playerId, @Nullable String playerName, @Nonnull AccessLevel level) {
        public PlayerGrant {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(level, "level");
            if (!level.isGrantable()) {
                throw new IllegalArgumentException("Not a grantable level: " + level);
            }
        }
    }

    public OwnerGrants {
        players = copyOrdered(players);
        groups = copyOrdered(groups);
        privateCompanions = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(privateCompanions));
        for (AccessLevel level : groups.values()) {
            if (!level.isGrantable()) {
                throw new IllegalArgumentException("Not a grantable level: " + level);
            }
        }
    }

    /** True when at least one player or group is granted access. */
    public boolean sharesAnything() {
        return !players.isEmpty() || !groups.isEmpty();
    }

    /** True when nothing is stored for the owner. */
    public boolean isEmpty() {
        return players.isEmpty() && groups.isEmpty() && privateCompanions.isEmpty();
    }

    @Nonnull
    public OwnerGrants withPlayer(@Nonnull PlayerGrant grant) {
        Map<UUID, PlayerGrant> next = new LinkedHashMap<>(players);
        next.put(grant.playerId(), grant);
        return new OwnerGrants(next, groups, privateCompanions);
    }

    @Nonnull
    public OwnerGrants withoutPlayer(@Nonnull UUID playerId) {
        if (!players.containsKey(playerId)) {
            return this;
        }
        Map<UUID, PlayerGrant> next = new LinkedHashMap<>(players);
        next.remove(playerId);
        return new OwnerGrants(next, groups, privateCompanions);
    }

    /** Sets a group's level, or turns the group off when {@code level} is null. */
    @Nonnull
    public OwnerGrants withGroup(@Nonnull String groupId, @Nullable AccessLevel level) {
        Map<String, AccessLevel> next = new LinkedHashMap<>(groups);
        if (level == null) {
            next.remove(groupId);
        } else {
            next.put(groupId, level);
        }
        return new OwnerGrants(players, next, privateCompanions);
    }

    @Nonnull
    public OwnerGrants withPrivate(@Nonnull UUID profileId, boolean isPrivate) {
        if (privateCompanions.contains(profileId) == isPrivate) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(privateCompanions);
        if (isPrivate) {
            next.add(profileId);
        } else {
            next.remove(profileId);
        }
        return new OwnerGrants(players, groups, next);
    }

    private static <K, V> Map<K, V> copyOrdered(Map<K, V> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
