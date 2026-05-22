package com.alechilles.alecstamework.assets.patches.selftest;

import java.nio.file.Path;

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
}
