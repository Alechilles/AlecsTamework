package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import javax.annotation.Nonnull;

/** Publishes the released capture event after the canonical capture is visible to readers. */
@FunctionalInterface
public interface SpawnerCapturePublishedEventSink {
    void publish(
            @Nonnull CompanionProfileReadModel canonicalProfile,
            @Nonnull SpawnerCapturePublishedEvidence evidence
    );
}
