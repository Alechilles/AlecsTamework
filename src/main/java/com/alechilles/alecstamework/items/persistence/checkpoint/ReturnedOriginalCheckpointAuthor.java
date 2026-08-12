package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.Objects;
import javax.annotation.Nullable;

/** Prefers an exact returned retired body when its replacement is absent. */
public final class ReturnedOriginalCheckpointAuthor {
    private final CompanionEntityCheckpointCodec codec;

    public ReturnedOriginalCheckpointAuthor(
            CompanionEntityCheckpointCodec codec
    ) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Returns a current-alias checkpoint or null for conflicting evidence. */
    @Nullable
    public CompanionEntityCheckpoint author(
            CompanionProfileReadModel profile,
            CompanionAlias sourceAlias,
            CompanionEntityCheckpointCapture capture,
            boolean currentAliasSafeToReplace
    ) {
        if (profile == null || sourceAlias == null || capture == null
                || !currentAliasSafeToReplace) {
            return null;
        }
        CompanionAlias current = profile.currentAlias();
        LifecycleState state = profile.lifecycle().state();
        if (current == null
                || current.state() != CompanionAlias.State.CURRENT
                || sourceAlias.state() != CompanionAlias.State.RETIRED
                || !sourceAlias.profileId().equals(
                        profile.identity().profileId()
                )
                || !sourceAlias.alias().equals(capture.alias())
                || current.alias().equals(sourceAlias.alias())
                || profile.lifecycle().ownerId() == null
                || !profile.lifecycle().ownerId().equals(capture.ownerId())
                || profile.lifecycle().activeOperationId() != null
                || profile.lifecycle().quarantineIncidentId() != null
                || (state != LifecycleState.ACTIVE
                        && state != LifecycleState.UNLOADED)) {
            return null;
        }
        return CompanionEntityCheckpoint.createReturnedOriginal(
                profile.identity().profileId(),
                current.alias(),
                sourceAlias.alias(),
                current.generation(),
                capture.ownerId(),
                profile.lifecycle().revision(),
                profile.lifecycle().lastReconciledGeneration(),
                capture.worldKey(),
                capture.x(),
                capture.y(),
                capture.z(),
                capture.capturedAtMs(),
                capture.holder(),
                codec
        );
    }
}
