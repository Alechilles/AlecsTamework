package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/** Rebuildable synchronous lookup derived from canonical extension rows. */
public final class ProfileExtensionProjectionIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("profile_extension_index");

    private final Map<ProfileExtensionKey, Long> revisions =
            new HashMap<>();
    private final Map<ProfileExtensionKey, ProfileExtensionProjectionValue>
            active = new HashMap<>();

    @Override
    @Nonnull
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    public ProjectionSubscription subscription() {
        return ProjectionSubscription.events(Set.of(
                ProfileExtensionMutationEventCodec.EVENT_TYPE
        ));
    }

    @Override
    @Nonnull
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Extension projection event is required"
            );
        }
        if (!ProfileExtensionMutationEventCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        ProfileExtensionMutationOutcome outcome =
                ProfileExtensionMutationEventCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        if (!event.aggregateId().equals(outcome.key().aggregateId())
                || event.aggregateRevision() != outcome.revision()) {
            throw new IllegalArgumentException(
                    "extension_projection_event_identity_mismatch"
            );
        }
        long current = revisions.getOrDefault(outcome.key(), -1L);
        if (current >= outcome.revision()) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        apply(outcome);
        revisions.put(outcome.key(), outcome.revision());
        return ProjectionApplyOutcome.APPLIED;
    }

    /** Replaces all revision and active-value evidence from canonical rows. */
    public synchronized void rebuild(
            @Nonnull Collection<ProfileExtensionData> rows
    ) {
        if (rows == null) {
            throw new IllegalArgumentException(
                    "Canonical extension rows are required"
            );
        }
        revisions.clear();
        active.clear();
        for (ProfileExtensionData row : List.copyOf(rows)) {
            if (row == null || revisions.putIfAbsent(
                    row.key(), row.revision()
            ) != null) {
                throw new IllegalArgumentException(
                        "Extension rows must have unique keys"
                );
            }
            if (!row.deleted()) {
                active.put(
                        row.key(),
                        ProfileExtensionProjectionValue.from(row)
                );
            }
        }
    }

    @Nonnull
    public synchronized Optional<ProfileExtensionProjectionValue> find(
            @Nonnull ProfileExtensionKey key
    ) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Extension projection key is required"
            );
        }
        return Optional.ofNullable(active.get(key));
    }

    /** Returns deterministic active values for one profile namespace. */
    @Nonnull
    public synchronized Map<String, ProfileExtensionProjectionValue>
    namespace(@Nonnull ProfileId profileId, @Nonnull String namespace) {
        if (profileId == null || namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException(
                    "Extension projection scope is required"
            );
        }
        String normalized = namespace.trim();
        LinkedHashMap<String, ProfileExtensionProjectionValue> values =
                new LinkedHashMap<>();
        active.entrySet().stream()
                .filter(entry -> entry.getKey().profileId().equals(profileId)
                        && entry.getKey().namespace().equals(normalized))
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(
                                ProfileExtensionKey::dataKey
                        )
                ))
                .forEach(entry -> values.put(
                        entry.getKey().dataKey(), entry.getValue()
                ));
        return Collections.unmodifiableMap(values);
    }

    private void apply(ProfileExtensionMutationOutcome outcome) {
        switch (outcome.status()) {
            case APPLIED -> active.put(
                    outcome.key(),
                    new ProfileExtensionProjectionValue(
                            outcome.key(),
                            outcome.revision(),
                            outcome.jsonPayload(),
                            outcome.updatedAtMs()
                    )
            );
            case DELETED -> active.remove(outcome.key());
            case UNCHANGED, REVISION_MISMATCH, PROFILE_NOT_FOUND -> {
            }
        }
    }
}
