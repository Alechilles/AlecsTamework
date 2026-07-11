package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Authority-local state for one opaque public admission capability. */
final class PublicPopulationAdmissionRecord {
    private final PopulationAdmissionRequest request;
    private final String profileId;
    private final UUID currentNpcUuid;
    private final boolean provisionalIdentity;
    private final PreparedCompanionPopulationAdmission prepared;
    private final PopulationAdmissionToken token;
    private final long retainUntilNanos;
    private State state = State.RESERVED;
    private PopulationAdmissionDecision decision;
    private CompletableFuture<PopulationAdmissionDecision> completion;
    private boolean nonterminalQuarantined;

    PublicPopulationAdmissionRecord(@Nonnull PopulationAdmissionRequest request,
                                    @Nonnull String profileId,
                                    @Nonnull UUID currentNpcUuid,
                                    boolean provisionalIdentity,
                                    @Nonnull PreparedCompanionPopulationAdmission prepared,
                                    @Nonnull PopulationAdmissionToken token,
                                    long retainUntilNanos,
                                    @Nonnull PopulationAdmissionDecision decision) {
        this.request = Objects.requireNonNull(request, "request");
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.currentNpcUuid = Objects.requireNonNull(currentNpcUuid, "currentNpcUuid");
        this.provisionalIdentity = provisionalIdentity;
        this.prepared = Objects.requireNonNull(prepared, "prepared");
        this.token = Objects.requireNonNull(token, "token");
        this.retainUntilNanos = retainUntilNanos;
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    @Nonnull
    PopulationAdmissionRequest request() {
        return request;
    }

    @Nonnull
    String profileId() {
        return profileId;
    }

    @Nonnull
    UUID currentNpcUuid() {
        return currentNpcUuid;
    }

    boolean provisionalIdentity() {
        return provisionalIdentity;
    }

    @Nonnull
    PreparedCompanionPopulationAdmission prepared() {
        return prepared;
    }

    @Nonnull
    PopulationAdmissionToken token() {
        return token;
    }

    long retainUntilNanos() {
        return retainUntilNanos;
    }

    synchronized boolean matches(@Nonnull PopulationAdmissionToken candidate) {
        return token.equals(candidate);
    }

    synchronized State state() {
        return state;
    }

    @Nonnull
    synchronized PopulationAdmissionDecision decision() {
        return decision;
    }

    synchronized boolean transition(State expected, State next) {
        if (state != expected) {
            return false;
        }
        state = next;
        return true;
    }

    synchronized void update(State next, @Nonnull PopulationAdmissionDecision nextDecision) {
        state = Objects.requireNonNull(next, "next");
        decision = Objects.requireNonNull(nextDecision, "nextDecision");
    }

    @Nullable
    synchronized CompletableFuture<PopulationAdmissionDecision> completion() {
        return completion;
    }

    synchronized void completion(@Nonnull CompletableFuture<PopulationAdmissionDecision> future) {
        completion = Objects.requireNonNull(future, "future");
    }

    synchronized boolean terminal() {
        return state == State.COMMITTED || state == State.CANCELED || state == State.DEGRADED;
    }

    @Nullable
    synchronized State quarantineExpiredNonterminal() {
        if (state == State.RESERVED || terminal() || nonterminalQuarantined) {
            return null;
        }
        nonterminalQuarantined = true;
        return state;
    }

    enum State {
        RESERVED,
        CLAIMING,
        APPLYING,
        COMMITTING,
        COMMITTED,
        CANCELING,
        CANCELED,
        DEGRADED
    }
}
