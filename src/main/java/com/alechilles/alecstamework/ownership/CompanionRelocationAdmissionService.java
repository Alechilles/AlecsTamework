package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Async, mutation-bound population gate for deliberate companion relocation. */
public final class CompanionRelocationAdmissionService {
    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimIndex;
    private final PopulationAdmissionApi authority;
    private final Executor executor;

    CompanionRelocationAdmissionService(@Nonnull OwnerPopulationIndex ownerIndex,
                                        @Nonnull CompanionIdentityResolver identityResolver,
                                        @Nonnull ClaimOccupancyIndex claimIndex,
                                        @Nonnull PopulationAdmissionApi authority) {
        this(ownerIndex, identityResolver, claimIndex, authority, ForkJoinPool.commonPool());
    }

    CompanionRelocationAdmissionService(@Nonnull OwnerPopulationIndex ownerIndex,
                                        @Nonnull CompanionIdentityResolver identityResolver,
                                        @Nonnull ClaimOccupancyIndex claimIndex,
                                        @Nonnull PopulationAdmissionApi authority,
                                        @Nonnull Executor executor) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Prepares a durable capability off the calling world thread. */
    @Nonnull
    public CompletionStage<Decision> prepare(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> plan(request), executor)
                .thenCompose(plan -> plan.request() == null
                        ? CompletableFuture.completedFuture(Decision.denied(plan.reason()))
                        : authority.tryAdmit(plan.request()).thenApply(this::map));
    }

    /** Freshly revalidates settings/provider/topology immediately before the live move. */
    @Nonnull
    public Decision claimForApply(@Nonnull Admission admission) {
        Objects.requireNonNull(admission, "admission");
        return map(authority.claimForApply(admission.token()));
    }

    @Nonnull
    public CompletionStage<Decision> commit(@Nonnull Admission admission) {
        Objects.requireNonNull(admission, "admission");
        return authority.commit(admission.token()).thenApply(this::map);
    }

    @Nonnull
    public CompletionStage<Decision> cancel(@Nonnull Admission admission) {
        Objects.requireNonNull(admission, "admission");
        return authority.cancel(admission.token()).thenApply(this::map);
    }

    private Plan plan(Request request) {
        String profileId = identityResolver.resolveProfileId(request.npcUuid()).orElse(null);
        if (profileId == null) {
            return Plan.denied("relocation-profile-unavailable");
        }
        OwnerPopulationEntry owner = ownerIndex.entry(profileId).orElse(null);
        ClaimOccupancyEntry claim = claimIndex.entry(profileId).orElse(null);
        if (owner == null || claim == null || claim.physicalChunk() == null) {
            return Plan.denied("relocation-population-state-unavailable");
        }
        if (owner.ownerId() == null || !owner.ownerId().equals(request.expectedOwnerUuid())
                || !Objects.equals(claim.ownerId(), owner.ownerId())) {
            return Plan.denied("relocation-owner-mismatch");
        }
        if (owner.revision() != claim.revision()) {
            return Plan.denied("relocation-population-revision-mismatch");
        }
        if (owner.lifecycleState() != CompanionLifecycleState.ACTIVE
                && owner.lifecycleState() != CompanionLifecycleState.UNLOADED) {
            return Plan.denied("relocation-lifecycle-not-physical");
        }
        ClaimChunkCoordinate source = claim.physicalChunk();
        PopulationAdmissionRequest admissionRequest = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(profileId, null, null),
                request.npcUuid(),
                owner.revision(),
                owner.ownerId(),
                owner.ownerId(),
                location(source),
                new PopulationAdmissionLocation(
                        request.destinationWorldName(), request.destinationChunkX(), request.destinationChunkZ()
                ),
                PopulationAdmissionOperation.REHOME,
                1,
                request.forcePolicy() == ForcePolicy.ENGINE_RELOCATION
                        ? PopulationAdmissionForcePolicy.ENGINE_RELOCATION
                        : PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return Plan.allowed(admissionRequest);
    }

    private Decision map(@Nullable PopulationAdmissionDecision decision) {
        if (decision == null) {
            return Decision.denied("relocation-admission-no-decision");
        }
        Status status = switch (decision.status()) {
            case RESERVED -> Status.RESERVED;
            case APPLYING -> Status.APPLYING;
            case COMMITTED -> Status.COMMITTED;
            case CANCELED -> Status.CANCELED;
            case DEGRADED -> Status.DEGRADED;
            default -> Status.DENIED;
        };
        Admission admission = (status == Status.RESERVED || status == Status.APPLYING)
                && decision.token() != null
                ? new Admission(decision.token()) : null;
        return new Decision(status, decision.reason(), admission);
    }

    private static PopulationAdmissionLocation location(ClaimChunkCoordinate coordinate) {
        return new PopulationAdmissionLocation(
                coordinate.worldName(), coordinate.chunkX(), coordinate.chunkZ()
        );
    }

    public enum ForcePolicy {
        ENFORCE,
        ENGINE_RELOCATION
    }

    public enum Status {
        DENIED,
        RESERVED,
        APPLYING,
        COMMITTED,
        CANCELED,
        DEGRADED
    }

    public record Request(@Nonnull UUID npcUuid,
                          @Nonnull UUID expectedOwnerUuid,
                          @Nonnull String destinationWorldName,
                          int destinationChunkX,
                          int destinationChunkZ,
                          @Nonnull ForcePolicy forcePolicy) {
        public Request {
            Objects.requireNonNull(npcUuid, "npcUuid");
            Objects.requireNonNull(expectedOwnerUuid, "expectedOwnerUuid");
            destinationWorldName = Objects.requireNonNull(destinationWorldName, "destinationWorldName").trim();
            if (destinationWorldName.isBlank()) {
                throw new IllegalArgumentException("Destination world is required.");
            }
            Objects.requireNonNull(forcePolicy, "forcePolicy");
        }
    }

    public record Admission(@Nonnull PopulationAdmissionToken token) {
        public Admission {
            Objects.requireNonNull(token, "token");
        }
    }

    public record Decision(@Nonnull Status status,
                           @Nonnull String reason,
                           @Nullable Admission admission) {
        public Decision {
            Objects.requireNonNull(status, "status");
            reason = reason == null || reason.isBlank() ? "relocation-admission-denied" : reason;
            boolean capabilityStatus = status == Status.RESERVED || status == Status.APPLYING;
            if (capabilityStatus != (admission != null)) {
                throw new IllegalArgumentException("Only RESERVED/APPLYING decisions carry an admission.");
            }
        }

        static Decision denied(String reason) {
            return new Decision(Status.DENIED, reason, null);
        }
    }

    private record Plan(@Nullable PopulationAdmissionRequest request, @Nonnull String reason) {
        static Plan allowed(PopulationAdmissionRequest request) {
            return new Plan(request, "relocation-admission-planned");
        }

        static Plan denied(String reason) {
            return new Plan(null, reason);
        }
    }
}
