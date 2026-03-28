package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Optional observer used to translate committed persistence changes into higher-level runtime events.
 */
public interface PersistenceChangeObserver {
    void onProfileChanged(@Nullable NpcProfileRepository.ProfileRecord before,
                          @Nullable NpcProfileRepository.ProfileRecord after);

    void onCaptureRecorded(@Nonnull CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot,
                           @Nullable NpcProfileRepository.ProfileRecord profile);

    void onDeathRecorded(@Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
                         @Nullable NpcProfileRepository.ProfileRecord profile);

    void onLostRecorded(@Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
                        @Nullable NpcProfileRepository.ProfileRecord profile);
}
