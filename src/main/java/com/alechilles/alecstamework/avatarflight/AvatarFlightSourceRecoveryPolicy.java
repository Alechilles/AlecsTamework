package com.alechilles.alecstamework.avatarflight;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Defines when a source NPC remains under the authority of its mount lifecycle. */
final class AvatarFlightSourceRecoveryPolicy {
    private AvatarFlightSourceRecoveryPolicy() {
    }

    static boolean isLifecycleOwned(
            @Nullable AvatarFlightMountSessionComponent session,
            @Nonnull AvatarFlightSourceComponent source,
            @Nonnull String sourceUuid
    ) {
        return session != null
                && sourceUuid.equals(session.getSourceNpcUuid())
                && AvatarFlightRuntimeEpoch.isCurrent(source.getRuntimeEpoch())
                && AvatarFlightRuntimeEpoch.isCurrent(session.getRuntimeEpoch());
    }
}
