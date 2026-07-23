package com.alechilles.alecstamework.companion.provisioning;

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

/** Rebuildable lookup of immutable provisioned-profile provenance. */
public final class ProvisioningProjectionIndex
        implements ProjectionConsumer {
    public static final ProjectionConsumerId CONSUMER_ID =
            new ProjectionConsumerId("provisioning_index");

    private final Map<ProfileId, ProvisioningRecord> byProfile =
            new HashMap<>();
    private final Map<ProvisioningOrigin, ProfileId> byOrigin =
            new HashMap<>();

    @Override
    public ProjectionConsumerId consumerId() {
        return CONSUMER_ID;
    }

    @Override
    public synchronized ProjectionApplyOutcome apply(
            @Nonnull ProjectionEvent event
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Provisioning projection event is required"
            );
        }
        if (!ProvisioningRecordChangeCodec.EVENT_TYPE.equals(
                event.eventType()
        )) {
            return ProjectionApplyOutcome.IRRELEVANT;
        }
        ProvisioningRecord record =
                ProvisioningRecordChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
        if (!event.aggregateId().equals(
                record.profileId().toString()
        ) || event.aggregateRevision() != 0) {
            throw new IllegalArgumentException(
                    "provisioning_record_event_mismatch"
            );
        }
        ProvisioningRecord current = byProfile.get(record.profileId());
        if (record.equals(current)) {
            return ProjectionApplyOutcome.ALREADY_APPLIED;
        }
        if (current != null || byOrigin.containsKey(record.origin())) {
            throw new IllegalArgumentException(
                    "provisioning_record_projection_conflict"
            );
        }
        put(record);
        return ProjectionApplyOutcome.APPLIED;
    }

    /** Replaces derived lookup state from immutable canonical records. */
    public synchronized void rebuild(
            @Nonnull Collection<ProvisioningRecord> records
    ) {
        if (records == null) {
            throw new IllegalArgumentException(
                    "Provisioning rebuild records are required"
            );
        }
        byProfile.clear();
        byOrigin.clear();
        for (ProvisioningRecord record : List.copyOf(records)) {
            if (record == null
                    || byProfile.containsKey(record.profileId())
                    || byOrigin.containsKey(record.origin())) {
                throw new IllegalArgumentException(
                        "Provisioning rebuild records must be unique"
                );
            }
            put(record);
        }
    }

    @Nonnull
    public synchronized Optional<ProvisioningRecord> findByProfile(
            @Nonnull ProfileId profileId
    ) {
        if (profileId == null) {
            throw new IllegalArgumentException(
                    "Provisioning profile is required"
            );
        }
        return Optional.ofNullable(byProfile.get(profileId));
    }

    @Nonnull
    public synchronized Optional<ProvisioningRecord> findByOrigin(
            @Nonnull ProvisioningOrigin origin
    ) {
        if (origin == null) {
            throw new IllegalArgumentException(
                    "Provisioning origin is required"
            );
        }
        ProfileId profileId = byOrigin.get(origin);
        return profileId == null
                ? Optional.empty()
                : Optional.ofNullable(byProfile.get(profileId));
    }

    @Nonnull
    public synchronized Map<ProfileId, ProvisioningRecord> snapshot() {
        return Map.copyOf(byProfile);
    }

    private void put(ProvisioningRecord record) {
        byProfile.put(record.profileId(), record);
        byOrigin.put(record.origin(), record.profileId());
    }
}
