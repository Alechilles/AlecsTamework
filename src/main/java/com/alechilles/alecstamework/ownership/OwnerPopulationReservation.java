package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.UUID;

/** Opaque capability returned for an accepted owner population transition. */
public final class OwnerPopulationReservation {
    private final UUID tokenId;
    private final Object authority;
    private ReservationState state = ReservationState.RESERVED;

    OwnerPopulationReservation(UUID tokenId, Object authority) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Stable diagnostic identifier; it does not expose or permit mutation of reservation state. */
    public UUID tokenId() {
        return tokenId;
    }

    ReservationState state() {
        return state;
    }

    boolean belongsTo(Object authority) {
        return this.authority == authority;
    }

    void setState(ReservationState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    enum ReservationState {
        RESERVED,
        APPLYING,
        COMMITTED,
        CANCELED,
        EXPIRED
    }

    @Override
    public String toString() {
        return "OwnerPopulationReservation[" + tokenId + "]";
    }
}
