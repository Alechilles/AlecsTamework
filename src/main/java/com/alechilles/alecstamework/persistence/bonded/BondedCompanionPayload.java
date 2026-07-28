package com.alechilles.alecstamework.persistence.bonded;

import java.util.Arrays;
import java.util.Objects;

/** Immutable opaque complete companion snapshot payload. */
public final class BondedCompanionPayload {
    private final byte[] bytes;

    private BondedCompanionPayload(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Creates a payload by defensively copying non-empty bytes. */
    public static BondedCompanionPayload of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        return new BondedCompanionPayload(bytes.clone());
    }

    /** Returns a defensive copy for adapter serialization. */
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override public boolean equals(Object other) {
        return other instanceof BondedCompanionPayload payload
                && Arrays.equals(bytes, payload.bytes);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override public String toString() {
        return "BondedCompanionPayload[bytes=" + bytes.length + "]";
    }
}
