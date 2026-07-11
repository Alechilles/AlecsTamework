package com.alechilles.alecstamework.integration.claims;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opaque capability for an accepted physical claim transition.
 */
public final class ClaimAdmissionReservation {
    private final UUID tokenId;
    private final Object authority;
    private final ClaimAdmissionOperation operation;
    private final List<ClaimOccupancyTransition> transitions;
    private final ClaimChunkCoordinate destinationChunk;
    private final String providerId;
    private final ClaimProviderGeneration providerGeneration;
    private final long settingsRevision;
    private final ClaimPopulationKey targetClaimKey;
    private final String footprintDigest;
    private final ClaimLookupResult.Status topologyStatus;
    private final boolean topologyCheckRequired;
    private final long reservedSlots;
    private final long expiresAtMonotonicNanos;
    private volatile State state = State.RESERVED;

    ClaimAdmissionReservation(@Nonnull UUID tokenId,
                              @Nonnull Object authority,
                              @Nonnull ClaimAdmissionRequest request,
                              @Nullable ClaimResolution target,
                              boolean topologyCheckRequired,
                              long reservedSlots,
                              long expiresAtMonotonicNanos) {
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.operation = request.operation();
        this.transitions = request.transitions();
        this.destinationChunk = request.destinationChunk();
        this.providerId = request.policyContext().providerId();
        this.providerGeneration = request.policyContext().providerGeneration();
        this.settingsRevision = request.policyContext().settingsRevision();
        this.targetClaimKey = target == null ? null : target.key();
        this.footprintDigest = target == null || target.footprint() == null
                ? null
                : target.footprint().digest();
        this.topologyStatus = target == null ? null : target.status();
        this.topologyCheckRequired = topologyCheckRequired;
        this.reservedSlots = Math.max(0L, reservedSlots);
        this.expiresAtMonotonicNanos = expiresAtMonotonicNanos;
    }

    @Nonnull
    public UUID tokenId() {
        return tokenId;
    }

    @Nonnull
    public ClaimAdmissionOperation operation() {
        return operation;
    }

    @Nonnull
    public Set<String> profileIds() {
        LinkedHashSet<String> profiles = new LinkedHashSet<>();
        for (ClaimOccupancyTransition transition : transitions) {
            profiles.add(transition.profileId());
        }
        return Set.copyOf(profiles);
    }

    @Nullable
    public ClaimChunkCoordinate destinationChunk() {
        return destinationChunk;
    }

    @Nonnull
    public String providerId() {
        return providerId;
    }

    @Nonnull
    public ClaimProviderGeneration providerGeneration() {
        return providerGeneration;
    }

    public long settingsRevision() {
        return settingsRevision;
    }

    @Nullable
    public ClaimPopulationKey targetClaimKey() {
        return targetClaimKey;
    }

    @Nullable
    public String footprintDigest() {
        return footprintDigest;
    }

    public long reservedSlots() {
        return reservedSlots;
    }

    /** Monotonic deadline captured by the reservation authority. */
    public long expiresAtMonotonicNanos() {
        return expiresAtMonotonicNanos;
    }

    @Nonnull
    public State state() {
        return state;
    }

    List<ClaimOccupancyTransition> transitions() {
        return transitions;
    }

    ClaimLookupResult.Status topologyStatus() {
        return topologyStatus;
    }

    public boolean topologyCheckRequired() {
        return topologyCheckRequired;
    }

    boolean belongsTo(Object authority) {
        return this.authority == authority;
    }

    void setState(State state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public enum State {
        RESERVED,
        APPLYING,
        COMMITTED,
        CANCELED,
        EXPIRED,
        INVALIDATED
    }

    @Override
    public String toString() {
        return "ClaimAdmissionReservation[" + tokenId + "]";
    }
}
