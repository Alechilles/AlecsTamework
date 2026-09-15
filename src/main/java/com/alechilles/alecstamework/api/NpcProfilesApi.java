package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface NpcProfilesApi {
    Optional<String> resolveProfileId(UUID npcUuid);

    Optional<NpcProfileView> getByProfileId(String profileId);

    Optional<NpcProfileView> getByNpcUuid(UUID npcUuid);

    Optional<String> getActiveSnapshot(String profileId, String snapshotType);

    Set<String> listActiveSnapshotTypes(String profileId);

    /**
     * Reads one bounded page of owner-scoped durable trait snapshots.
     *
     * <p>An empty optional means this API implementation cannot provide the
     * read. Rows with {@link OwnedTraitSnapshot#traitDataAvailable()} false
     * are still owned profiles, but have no valid saved trait presentation.
     * The saved snapshot timestamp is exposed so callers can present its
     * freshness without consulting a live NPC.</p>
     *
     * <p>The built-in implementation requires a non-null owner, offset >= 0,
     * and limit 1..64. Rows are ordered by profile ID. Captured and stored
     * companions are included; released and dead companions are excluded.
     * Ownership is checked again after asynchronous storage reads. Completion
     * can run off the world thread; revalidate the player on the original
     * world before updating UI.</p>
     */
    default CompletionStage<Optional<List<OwnedTraitSnapshot>>>
    getOwnedTraitSnapshots(UUID ownerUuid, int offset, int limit) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}

