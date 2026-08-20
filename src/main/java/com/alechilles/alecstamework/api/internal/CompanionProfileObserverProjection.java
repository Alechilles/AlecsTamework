package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChangeCodec;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Idempotent after-commit bridge from canonical profile evidence to released API events.
 *
 * <p>The payload is deliberately self-contained. This consumer never performs a persistence read,
 * so it cannot deadlock the read executor or observe state newer than the event being delivered.</p>
 */
public final class CompanionProfileObserverProjection implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("public_profile_observer");

    private final Consumer<NpcProfileChangedEvent> listener;
    private final Map<String, Long> appliedRevisions = new HashMap<>();
    private final Map<ProfileId, CompanionProfileProjectionState> profiles =
            new HashMap<>();
    private final Map<NpcAlias, ProfileId> currentAliases = new HashMap<>();
    private final Map<NpcAlias, ProfileId> knownAliases = new HashMap<>();

    public CompanionProfileObserverProjection(
            @Nonnull Consumer<NpcProfileChangedEvent> listener
    ) {
        if (listener == null) {
            throw new IllegalArgumentException("Profile observer listener is required");
        }
        this.listener = listener;
    }

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    @Nonnull
    public ProjectionSubscription subscription() {
        return ProjectionSubscription.events(Set.of(
                CompanionProfileProjectionChangeCodec.EVENT_TYPE
        ));
    }

    @Override
    @Nonnull
    public synchronized ProjectionApplyOutcome apply(@Nonnull ProjectionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Profile projection event is required");
        }
        if (!CompanionProfileProjectionChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        CompanionProfileProjectionChange change =
                CompanionProfileProjectionChangeCodec.decode(
                        event.payloadVersion(),
                        event.payloadJson()
                );
        if (!event.aggregateId().equals(
                CompanionProfileProjectionChangeCodec.aggregateId(change)
        ) || event.aggregateRevision() != change.sourceRevision()) {
            throw new IllegalArgumentException(
                    "profile_projection_event_identity_mismatch"
            );
        }
        long applied = appliedRevisions.getOrDefault(event.aggregateId(), -1L);
        if (applied >= event.aggregateRevision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        validateReplacement(change.after());
        listener.accept(new NpcProfileChangedEvent(
                change.profileId().toString(),
                CompanionProfileApiMapper.diff(change.before(), change.after()),
                change.before() == null
                        ? null
                        : CompanionProfileApiMapper.map(change.before()),
                change.after() == null
                        ? null
                        : CompanionProfileApiMapper.map(change.after()),
                change.changedAtMs()
        ));
        replace(change.before(), change.after());
        appliedRevisions.put(event.aggregateId(), event.aggregateRevision());
        return ProjectionApplyOutcome.APPLIED;
    }

    /** Replaces the non-authoritative lookup from one canonical snapshot. */
    public synchronized void rebuild(
            @Nonnull Collection<CompanionProfileProjectionState> states
    ) {
        rebuild(states, List.of());
    }

    /** Rebuilds profiles and their complete durable runtime-alias lineage. */
    public synchronized void rebuild(
            @Nonnull Collection<CompanionProfileProjectionState> states,
            @Nonnull Collection<CompanionAlias> aliases
    ) {
        if (states == null) {
            throw new IllegalArgumentException(
                    "Canonical profile projection states are required"
            );
        }
        if (aliases == null) {
            throw new IllegalArgumentException(
                    "Canonical profile aliases are required"
            );
        }
        profiles.clear();
        currentAliases.clear();
        knownAliases.clear();
        for (CompanionProfileProjectionState state : List.copyOf(states)) {
            if (state == null
                    || profiles.putIfAbsent(state.profileId(), state) != null) {
                throw new IllegalArgumentException(
                        "Profile projection states must be unique"
                );
            }
            indexAlias(state);
        }
        for (CompanionAlias alias : List.copyOf(aliases)) {
            if (alias == null || !profiles.containsKey(alias.profileId())) {
                throw new IllegalArgumentException(
                        "Profile aliases must reference a projected profile"
                );
            }
            indexKnownAlias(alias.alias(), alias.profileId());
        }
    }

    /** Returns a current projection without touching storage. */
    @Nonnull
    public synchronized Optional<CompanionProfileProjectionState> find(
            @Nonnull ProfileId profileId
    ) {
        if (profileId == null) {
            throw new IllegalArgumentException("Profile ID is required");
        }
        return Optional.ofNullable(profiles.get(profileId));
    }

    /** Resolves only the current projected alias without touching storage. */
    @Nonnull
    public synchronized Optional<CompanionProfileProjectionState> find(
            @Nonnull NpcAlias alias
    ) {
        if (alias == null) {
            throw new IllegalArgumentException("NPC alias is required");
        }
        ProfileId profileId = currentAliases.get(alias);
        return profileId == null
                ? Optional.empty()
                : Optional.ofNullable(profiles.get(profileId));
    }

    /** Resolves one current or retired runtime alias without touching storage. */
    @Nonnull
    public synchronized Optional<CompanionProfileProjectionState>
    findKnownAlias(@Nonnull NpcAlias alias) {
        if (alias == null) {
            throw new IllegalArgumentException("NPC alias is required");
        }
        ProfileId profileId = knownAliases.get(alias);
        return profileId == null
                ? Optional.empty()
                : Optional.ofNullable(profiles.get(profileId));
    }

    /** Returns an immutable snapshot for bounded API iteration. */
    @Nonnull
    public synchronized Map<ProfileId, CompanionProfileProjectionState>
    snapshot() {
        return Map.copyOf(profiles);
    }

    private void replace(
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after
    ) {
        if (before != null && before.currentAlias() != null) {
            currentAliases.remove(before.currentAlias(), before.profileId());
        }
        if (after == null) {
            if (before != null) {
                profiles.remove(before.profileId());
                knownAliases.entrySet().removeIf(
                        entry -> entry.getValue().equals(before.profileId())
                );
            }
            return;
        }
        profiles.put(after.profileId(), after);
        indexAlias(after);
    }

    private void validateReplacement(
            CompanionProfileProjectionState after
    ) {
        if (after == null || after.currentAlias() == null) {
            return;
        }
        ProfileId conflict = currentAliases.get(after.currentAlias());
        ProfileId knownConflict = knownAliases.get(after.currentAlias());
        if ((conflict != null && !conflict.equals(after.profileId()))
                || (knownConflict != null
                && !knownConflict.equals(after.profileId()))) {
            throw new IllegalArgumentException(
                    "Profile projection current alias conflict"
            );
        }
    }

    private void indexAlias(CompanionProfileProjectionState state) {
        if (state.currentAlias() == null) {
            return;
        }
        ProfileId conflict = currentAliases.putIfAbsent(
                state.currentAlias(), state.profileId()
        );
        if (conflict != null && !conflict.equals(state.profileId())) {
            throw new IllegalArgumentException(
                    "Profile projection current alias conflict"
            );
        }
        indexKnownAlias(state.currentAlias(), state.profileId());
    }

    private void indexKnownAlias(NpcAlias alias, ProfileId profileId) {
        ProfileId conflict = knownAliases.putIfAbsent(alias, profileId);
        if (conflict != null && !conflict.equals(profileId)) {
            throw new IllegalArgumentException(
                    "Profile projection known alias conflict"
            );
        }
    }
}
