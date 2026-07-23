package com.alechilles.alecstamework.persistence.legacy;

import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceChangeObserver;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Temporary observer bridge isolated entirely inside the legacy package. */
public final class LegacyPersistenceEventBridge
        implements PersistenceChangeObserver {
    private final TameworkEventBus events;

    public LegacyPersistenceEventBridge(@Nonnull TameworkEventBus events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void onProfileChanged(
            @Nullable NpcProfileRepository.ProfileRecord before,
            @Nullable NpcProfileRepository.ProfileRecord after
    ) {
        events.publishProfileChanged(
                before == null ? null
                        : LegacyNpcProfilesApi.mapProfile(before),
                after == null ? null
                        : LegacyNpcProfilesApi.mapProfile(after),
                System.currentTimeMillis()
        );
    }

    @Override
    public void onCaptureRecorded(
            @Nonnull CommandLinkedNpcCaptureService
                    .CapturedLinkedNpcSnapshot snapshot,
            @Nullable NpcProfileRepository.ProfileRecord profile
    ) {
        events.publishCaptureRecorded(
                snapshot,
                profile == null ? null
                        : LegacyNpcProfilesApi.mapProfile(profile)
        );
    }

    @Override
    public void onDeathRecorded(
            @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
            @Nullable NpcProfileRepository.ProfileRecord profile
    ) {
        events.publishDeathRecorded(
                snapshot,
                profile == null ? null
                        : LegacyNpcProfilesApi.mapProfile(profile)
        );
    }

    @Override
    public void onLostRecorded(
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
            @Nullable NpcProfileRepository.ProfileRecord profile
    ) {
        events.publishLostRecorded(
                snapshot,
                profile == null ? null
                        : LegacyNpcProfilesApi.mapProfile(profile)
        );
    }
}
