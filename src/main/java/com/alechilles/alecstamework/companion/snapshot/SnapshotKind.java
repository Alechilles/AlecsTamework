package com.alechilles.alecstamework.companion.snapshot;

import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Stable registered kind of companion snapshot.
 *
 * @param value lowercase snake-case identifier
 */
public record SnapshotKind(@Nonnull String value) implements Comparable<SnapshotKind> {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public SnapshotKind {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Snapshot kind must be lowercase snake case");
        }
    }

    @Override
    public int compareTo(SnapshotKind other) {
        if (other == null) {
            throw new NullPointerException("Other snapshot kind is required");
        }
        return value.compareTo(other.value);
    }

    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
