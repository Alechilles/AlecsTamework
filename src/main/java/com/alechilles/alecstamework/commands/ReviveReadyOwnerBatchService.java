package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.revival.ReviveReadyRequest;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Submits revive-ready updates for every dead linked generic companion of one owner. */
public final class ReviveReadyOwnerBatchService {
    private final Supplier<Map<ProfileId, CompanionProfileProjectionState>> profiles;
    private final Supplier<Set<ProfileId>> rosterProfiles;
    private final Supplier<Set<ProfileId>> laggingRosterProfiles;
    private final ReviveReadyMarker marker;
    private final LongSupplier clock;

    public ReviveReadyOwnerBatchService(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations
    ) {
        this(
                queries::projectedProfileSnapshot,
                () -> queries.projectedCommandRosterActions().keySet(),
                queries::projectedLaggingCommandRosterProfiles,
                (profileId, ownerId, requestedAtMs) -> {
                    OperationId operationId = OperationId.create();
                    return operations.markReviveReady(
                            operationId,
                            new IdempotencyKey(
                                    "debug-revive-ready:" + operationId
                            ),
                            new ReviveReadyRequest(
                                    profileId, ownerId, requestedAtMs
                            )
                    ).accepted();
                },
                System::currentTimeMillis
        );
    }

    ReviveReadyOwnerBatchService(
            @Nonnull Supplier<Map<ProfileId, CompanionProfileProjectionState>> profiles,
            @Nonnull Supplier<Set<ProfileId>> rosterProfiles,
            @Nonnull Supplier<Set<ProfileId>> laggingRosterProfiles,
            @Nonnull ReviveReadyMarker marker,
            @Nonnull LongSupplier clock
    ) {
        if (profiles == null || rosterProfiles == null || laggingRosterProfiles == null
                || marker == null || clock == null) {
            throw new IllegalArgumentException(
                    "Revive-ready owner batch dependencies are required"
            );
        }
        this.profiles = profiles;
        this.rosterProfiles = rosterProfiles;
        this.laggingRosterProfiles = laggingRosterProfiles;
        this.marker = marker;
        this.clock = clock;
    }

    /** Submits updates for all current dead linked profiles owned by the player. */
    @Nonnull
    public UpdateResult markAll(@Nonnull OwnerId ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("Revive-ready owner is required");
        }
        long requestedAtMs = clock.getAsLong();
        Set<ProfileId> rosterLinked = Set.copyOf(rosterProfiles.get());
        Set<ProfileId> rosterLagging = Set.copyOf(laggingRosterProfiles.get());
        ArrayList<CompanionProfileProjectionState> candidates =
                new ArrayList<>(profiles.get().values());
        candidates.sort(Comparator.comparing(state ->
                state.profileId().value()));

        for (CompanionProfileProjectionState candidate : candidates) {
            if (laggingOwnerRosterCandidate(candidate, ownerId, rosterLagging)) {
                return new UpdateResult(0, 0, 0, 0, true);
            }
        }

        int total = 0;
        int accepted = 0;
        int alreadyReady = 0;
        int rejected = 0;
        for (CompanionProfileProjectionState candidate : candidates) {
            if (!eligible(candidate, ownerId, rosterLinked)) {
                continue;
            }
            total++;
            if (candidate.restorationAvailableAtMs() <= requestedAtMs) {
                alreadyReady++;
            } else if (marker.mark(
                    candidate.profileId(), ownerId, requestedAtMs
            )) {
                accepted++;
            } else {
                rejected++;
            }
        }
        return new UpdateResult(total, accepted, alreadyReady, rejected, false);
    }

    private boolean eligible(
            CompanionProfileProjectionState candidate,
            OwnerId ownerId,
            Set<ProfileId> rosterLinked
    ) {
        return candidate != null
                && ownerId.equals(candidate.ownerId())
                && candidate.lifecycleState() == LifecycleState.DEAD_REVIVABLE
                && (!candidate.toolIds().isEmpty()
                || rosterLinked.contains(candidate.profileId()));
    }

    private boolean laggingOwnerRosterCandidate(
            CompanionProfileProjectionState candidate,
            OwnerId ownerId,
            Set<ProfileId> rosterLagging
    ) {
        return candidate != null
                && ownerId.equals(candidate.ownerId())
                && candidate.lifecycleState() == LifecycleState.DEAD_REVIVABLE
                && candidate.toolIds().isEmpty()
                && rosterLagging.contains(candidate.profileId());
    }

    @FunctionalInterface
    interface ReviveReadyMarker {
        boolean mark(
                ProfileId profileId,
                OwnerId ownerId,
                long requestedAtMs
        );
    }

    /** Counts the owner-scoped revive-ready batch outcome. */
    public record UpdateResult(
            int total,
            int accepted,
            int alreadyReady,
            int rejected,
            boolean projectionLagging
    ) { }
}
