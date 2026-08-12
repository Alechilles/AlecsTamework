package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.Objects;
import javax.annotation.Nullable;

/** Applies canonical identity fences to one world-thread entity capture. */
public final class CompanionEntityCheckpointAuthor {
    private final CompanionEntityCheckpointCodec codec;

    public CompanionEntityCheckpointAuthor(
            CompanionEntityCheckpointCodec codec
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Returns an exact checkpoint or null when current evidence disagrees. */
    @Nullable
    public CompanionEntityCheckpoint author(
            CompanionProfileReadModel profile,
            CompanionEntityCheckpointCapture capture
    ) {
        if (profile == null || capture == null) {
            return null;
        }
        CompanionAlias alias = profile.currentAlias();
        if (alias == null || alias.state() != CompanionAlias.State.CURRENT
                || !alias.alias().equals(capture.alias())
                || profile.lifecycle().ownerId() == null
                || !profile.lifecycle().ownerId().equals(capture.ownerId())
                || profile.lifecycle().activeOperationId() != null
                || profile.lifecycle().quarantineIncidentId() != null
                || !liveState(profile.lifecycle().state())) {
            return null;
        }
        return CompanionEntityCheckpoint.create(
                profile.identity().profileId(),
                alias.alias(),
                alias.generation(),
                capture.ownerId(),
                profile.lifecycle().revision(),
                profile.lifecycle().lastReconciledGeneration(),
                capture.worldKey(),
                capture.x(),
                capture.y(),
                capture.z(),
                capture.boundary(),
                capture.capturedAtMs(),
                capture.holder(),
                codec
        );
    }

    private static boolean liveState(LifecycleState state) {
        return state == LifecycleState.ACTIVE
                || state == LifecycleState.UNLOADED;
    }
}
