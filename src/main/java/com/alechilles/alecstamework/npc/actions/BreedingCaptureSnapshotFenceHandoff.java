package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationResult;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.SnapshotCapture;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.SnapshotHandoff;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Builds a post-cancellation snapshot handoff while failing a retained capture fence closed. */
final class BreedingCaptureSnapshotFenceHandoff {
    private BreedingCaptureSnapshotFenceHandoff() {
    }

    @Nonnull
    static <T> SnapshotHandoff<T> capture(
            @Nonnull Supplier<CancellationResult> cancellation,
            @Nonnull SnapshotCapture<T> snapshotCapture,
            @Nonnull Runnable failedCaptureRelease) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(snapshotCapture, "snapshotCapture");
        Objects.requireNonNull(failedCaptureRelease, "failedCaptureRelease");
        try {
            CancellationResult result = Objects.requireNonNull(
                    cancellation.get(), "cancellation result");
            return new SnapshotHandoff<>(
                    result,
                    result.safeToCapture() ? snapshotCapture.capture() : null
            );
        } catch (RuntimeException | Error failure) {
            failedCaptureRelease.run();
            throw failure;
        }
    }
}
