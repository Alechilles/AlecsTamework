package com.alechilles.alecstamework.api.access;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * A group of players an owner can choose to share companions with, such as the owner's guild or
 * friends list. Registering a provider grants nothing: each owner turns the group on and picks a
 * level.
 *
 * <p>{@link #membership} is called from world threads and from a background thread, often, and
 * for offline owners. It must answer from memory: no file, database or network access, and no
 * waiting. Answer {@link AccessGroupMembership#UNKNOWN} when the data is not loaded.</p>
 */
public interface AccessGroupProvider {
    /** A stable, namespaced id such as {@code "runeteria:guild"}. It is saved in owners' grants. */
    @Nonnull
    String id();

    /** Translation key for the group's name as shown to players, for example "My guild". */
    @Nonnull
    String displayNameKey();

    /** Whether {@code actorUuid} is in {@code ownerUuid}'s group. The two ids are never equal. */
    @Nonnull
    AccessGroupMembership membership(@Nonnull UUID ownerUuid, @Nonnull UUID actorUuid);
}
