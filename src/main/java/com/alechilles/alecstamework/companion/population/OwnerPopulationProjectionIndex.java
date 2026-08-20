package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChange;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/** Rebuildable committed owner counts derived only from canonical lifecycle evidence. */
public final class OwnerPopulationProjectionIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("owner_population_index");

    private final Map<ProfileId, CompanionLifecycle> byProfile =
            new HashMap<>();
    private final Map<OwnerPopulationScope, Long> counts = new HashMap<>();

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    @Nonnull
    public ProjectionSubscription subscription() {
        return ProjectionSubscription.events(Set.of(
                CompanionLifecycleProjectionChangeCodec.EVENT_TYPE
        ));
    }

    @Override
    @Nonnull
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Owner population projection event is required"
            );
        }
        if (!CompanionLifecycleProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        CompanionLifecycleProjectionChange change =
                CompanionLifecycleProjectionChangeCodec.decode(
                        event.payloadVersion(),
                        event.payloadJson()
                );
        CompanionLifecycle after = change.after();
        if (!event.aggregateId().equals(after.profileId().toString())
                || event.aggregateRevision() != after.revision().value()) {
            throw new IllegalArgumentException(
                    "owner_population_event_identity_mismatch"
            );
        }
        CompanionLifecycle current = byProfile.get(after.profileId());
        if (current != null
                && current.revision().value() >= after.revision().value()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        replace(current, after);
        return ProjectionApplyOutcome.APPLIED;
    }

    /** Replaces the entire index from one consistent canonical lifecycle read. */
    public synchronized void rebuild(
            @Nonnull Collection<CompanionLifecycle> lifecycles
    ) {
        if (lifecycles == null) {
            throw new IllegalArgumentException(
                    "Owner population canonical rebuild is required"
            );
        }
        byProfile.clear();
        counts.clear();
        for (CompanionLifecycle lifecycle : List.copyOf(lifecycles)) {
            if (lifecycle == null
                    || byProfile.putIfAbsent(
                    lifecycle.profileId(),
                    lifecycle
            ) != null) {
                throw new IllegalArgumentException(
                        "Canonical lifecycles must have unique profiles"
                );
            }
            add(lifecycle);
        }
    }

    /** Returns the committed count in one owner scope. */
    public synchronized long count(@Nonnull OwnerPopulationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException(
                    "Owner population scope is required"
            );
        }
        return counts.getOrDefault(scope, 0L);
    }

    /** Returns immutable canonical revision evidence for diagnostics and equivalence tests. */
    @Nonnull
    public synchronized Map<ProfileId, CompanionLifecycle> snapshot() {
        return Map.copyOf(byProfile);
    }

    private void replace(
            CompanionLifecycle current,
            CompanionLifecycle after
    ) {
        if (current != null) {
            remove(current);
        }
        byProfile.put(after.profileId(), after);
        add(after);
    }

    private void add(CompanionLifecycle lifecycle) {
        for (OwnerPopulationScope scope : scopes(lifecycle)) {
            counts.merge(scope, 1L, Long::sum);
        }
    }

    private void remove(CompanionLifecycle lifecycle) {
        for (OwnerPopulationScope scope : scopes(lifecycle)) {
            long updated = counts.getOrDefault(scope, 0L) - 1;
            if (updated < 0) {
                throw new IllegalStateException(
                        "owner_population_projection_underflow"
                );
            }
            if (updated == 0) {
                counts.remove(scope);
            } else {
                counts.put(scope, updated);
            }
        }
    }

    private List<OwnerPopulationScope> scopes(
            CompanionLifecycle lifecycle
    ) {
        if (lifecycle.ownerId() == null) {
            return List.of();
        }
        OwnerPopulationScope global =
                OwnerPopulationScope.global(lifecycle.ownerId());
        return lifecycle.ownerWorldKey() == null
                ? List.of(global)
                : List.of(
                        global,
                        OwnerPopulationScope.perWorld(
                                lifecycle.ownerId(),
                                lifecycle.ownerWorldKey()
                        )
                );
    }
}

