package com.alechilles.alecstamework.assets.patches.selftest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.assets.patches.AssetPatchStatus;

/**
 * Minimal reload surface used by the self-test runner.
 */
interface AssetPatchSelfTestReloadHandle {
    @Nonnull
    AssetPatchStatus reload();

    @Nonnull
    Path generatedPatchCacheRoot();

    default long markHotReloadObservationStart() {
        return 0L;
    }

    @Nonnull
    default Set<String> awaitHotReloadedTargets(@Nonnull Collection<String> targets,
                                                long sinceSequence,
                                                @Nonnull Duration timeout) {
        return Set.of();
    }

    default void pauseBeforeCleanupReload() {
    }
}
