package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Rebuildable, revision-aware lookup index for current coop occupancy. */
public final class CoopResidencyProjectionIndex implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("coop_residency_index");

    private final Map<CoopSlotKey, CoopOccupancy> bySlot = new HashMap<>();
    private final Map<ProfileId, CoopSlotKey> byProfile = new HashMap<>();
    private final Map<CoopSlotKey, Long> revisions = new HashMap<>();

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    @Nonnull
    public synchronized ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Coop projection event is required");
        }
        if (!CoopResidencyProjectionCodec.EVENT_TYPE.equals(event.eventType())) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        CoopResidencyProjectionChange change =
                CoopResidencyProjectionCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        if (!event.aggregateId().equals(
                CoopResidencyProjectionCodec.aggregateId(change.slotKey())
        ) || event.aggregateRevision() != change.slotRevision()) {
            throw new IllegalArgumentException(
                    "coop_projection_event_identity_mismatch"
            );
        }
        long applied = revisions.getOrDefault(change.slotKey(), 0L);
        if (applied >= change.slotRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        removeSlot(change.slotKey());
        if (change.after() != null) {
            add(new CoopOccupancy(
                    new CoopSlot(
                            change.slotKey(),
                            change.slotRevision(),
                            null,
                            null
                    ),
                    change.after()
            ));
        }
        revisions.put(change.slotKey(), change.slotRevision());
        return ProjectionApplyOutcome.APPLIED;
    }

    /** Replaces the whole derived index from a consistent canonical read. */
    public synchronized void rebuild(
            @Nonnull Collection<CoopOccupancy> occupancies
    ) {
        if (occupancies == null) {
            throw new IllegalArgumentException("Coop occupancy rebuild is required");
        }
        bySlot.clear();
        byProfile.clear();
        revisions.clear();
        for (CoopOccupancy occupancy : List.copyOf(occupancies)) {
            if (occupancy == null || occupancy.slot().residencyRevision() <= 0) {
                throw new IllegalArgumentException(
                        "Canonical coop occupancy needs a positive revision"
                );
            }
            add(occupancy);
            revisions.put(
                    occupancy.slot().key(),
                    occupancy.slot().residencyRevision()
            );
        }
    }

    @Nonnull
    public synchronized Optional<CoopOccupancy> findBySlot(
            @Nonnull CoopSlotKey slotKey
    ) {
        return Optional.ofNullable(bySlot.get(slotKey));
    }

    @Nonnull
    public synchronized Optional<CoopOccupancy> findByProfile(
            @Nonnull ProfileId profileId
    ) {
        CoopSlotKey slotKey = byProfile.get(profileId);
        return slotKey == null
                ? Optional.empty()
                : Optional.ofNullable(bySlot.get(slotKey));
    }

    @Nonnull
    public synchronized Map<CoopSlotKey, CoopOccupancy> snapshot() {
        return Map.copyOf(bySlot);
    }

    private void add(CoopOccupancy occupancy) {
        CoopSlotKey previous = byProfile.putIfAbsent(
                occupancy.residency().profileId(),
                occupancy.slot().key()
        );
        if (previous != null && !previous.equals(occupancy.slot().key())) {
            throw new IllegalStateException("coop_projection_profile_conflict");
        }
        CoopOccupancy replaced = bySlot.put(
                occupancy.slot().key(), occupancy
        );
        if (replaced != null
                && !replaced.residency().profileId().equals(
                occupancy.residency().profileId()
        )) {
            byProfile.remove(replaced.residency().profileId());
        }
    }

    private void removeSlot(CoopSlotKey slotKey) {
        CoopOccupancy removed = bySlot.remove(slotKey);
        if (removed != null) {
            byProfile.remove(removed.residency().profileId(), slotKey);
        }
    }
}
