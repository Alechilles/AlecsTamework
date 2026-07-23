package com.alechilles.alecstamework.persistence.kernel;

import javax.annotation.Nonnull;

/**
 * Lossless result of a canonical persistence read.
 *
 * <p>Absence and failure are distinct variants so storage or decoding problems cannot silently
 * become successful empty reads.</p>
 *
 * @param <T> immutable value returned by a successful read
 */
public sealed interface PersistenceReadResult<T>
        permits PersistenceReadResult.Found, PersistenceReadResult.Absent, PersistenceReadResult.Failed {

    /**
     * Returns a successful value and the non-negative canonical revision read with it.
     *
     * @param <T> value type
     * @param value immutable value
     * @param revision canonical revision; zero is valid
     */
    record Found<T>(@Nonnull T value, long revision) implements PersistenceReadResult<T> {
        public Found {
            if (value == null) {
                throw new IllegalArgumentException("A found persistence read requires a value");
            }
            if (revision < 0) {
                throw new IllegalArgumentException("Persistence revision cannot be negative");
            }
        }
    }

    /** Represents authoritative absence after a successful read. */
    record Absent<T>() implements PersistenceReadResult<T> {
    }

    /**
     * Returns a typed storage failure without pretending the requested value was absent.
     *
     * @param <T> value type
     * @param failure driver-neutral failure details
     */
    record Failed<T>(@Nonnull StorageFailure failure) implements PersistenceReadResult<T> {
        public Failed {
            if (failure == null) {
                throw new IllegalArgumentException("A failed persistence read requires failure details");
            }
        }
    }

    /** Creates a successful read result. */
    @Nonnull
    static <T> PersistenceReadResult<T> found(@Nonnull T value, long revision) {
        return new Found<>(value, revision);
    }

    /** Creates an authoritative absence result. */
    @Nonnull
    static <T> PersistenceReadResult<T> absent() {
        return new Absent<>();
    }

    /** Creates a failed read result. */
    @Nonnull
    static <T> PersistenceReadResult<T> failed(@Nonnull StorageFailure failure) {
        return new Failed<>(failure);
    }
}
