package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One companion as a server network's shared store keeps it (see {@link PortableCompanionStore}).
 *
 * <p>A <b>portable</b> entry is a companion that follows its owner between servers: owned and in a
 * capture item, stored (roster, bonded, timed, provisioned), dead or lost. It carries the record
 * and the latest snapshot, which Tamework encodes and reads back; a store keeps both as opaque text.
 * A <b>world-bound</b> entry ({@code portable} false) is held in one server's world (out in the
 * world, in a coop, or released). It carries the record only and names that server in
 * {@code holder}, so other servers can count it toward the owner's limits.</p>
 *
 * <p>Entries are ordered by {@code generation}, then {@code version} (the record's wall-clock
 * {@code updatedAtMs}): every holder change raises the generation, so moves are always ordered,
 * and an edit within one generation goes to the later clock. A store keeps, per profile, only the
 * entry that {@link #supersedes} every other it was given.</p>
 *
 * @param profileId    the companion's stable id
 * @param ownerUuid    the owner, or null for an unowned companion
 * @param generation   the holder generation; never negative
 * @param version      the record's {@code updatedAtMs}
 * @param portable     true when the companion follows its owner
 * @param locationKind where the companion is: {@code LIVE}, {@code ITEM}, {@code COOP}, {@code STORED},
 *                     {@code DEAD}, {@code LOST} or {@code RELEASED}
 * @param roleId       the companion's NPC role
 * @param holder       for a world-bound entry, the {@link PortableCompanionStore#holderId()} of the
 *                     server whose world holds it; null for a portable entry
 * @param recordJson   the encoded record; Tamework writes and reads it, a store keeps it as text
 * @param snapshotJson the encoded snapshot of a portable entry, or null when it has none
 */
public record PortableCompanionEntry(
        @Nonnull UUID profileId,
        @Nullable UUID ownerUuid,
        long generation,
        long version,
        boolean portable,
        @Nonnull String locationKind,
        @Nonnull String roleId,
        @Nullable String holder,
        @Nonnull String recordJson,
        @Nullable String snapshotJson
) {
    public PortableCompanionEntry {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(locationKind, "locationKind");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(recordJson, "recordJson");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    /** True when this entry replaces {@code other}: a higher generation, or the same one and a later version. */
    public boolean supersedes(@Nonnull PortableCompanionEntry other) {
        return supersedes(generation, version, other.generation, other.version);
    }

    /**
     * The ordering rule as plain values, for a store that compares inside its own query: {@code (generation,
     * version)} is newer than {@code (otherGeneration, otherVersion)}.
     */
    public static boolean supersedes(long generation, long version, long otherGeneration, long otherVersion) {
        return generation > otherGeneration || generation == otherGeneration && version > otherVersion;
    }
}
