package com.alechilles.alecstamework.assets.patches;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Installed asset-pack view used to evaluate optional patch conditions.
 */
public final class AssetPatchConditionContext {
    private final Set<String> installedPacks;

    public AssetPatchConditionContext(@Nonnull String generatedPackId,
                                      @Nonnull Iterable<String> installedPacks) {
        Set<String> packs = new LinkedHashSet<>();
        for (String pack : installedPacks) {
            if (pack == null) {
                continue;
            }
            String normalized = pack.trim();
            if (!normalized.isBlank() && !generatedPackId.equals(normalized)) {
                packs.add(normalized);
            }
        }
        this.installedPacks = Set.copyOf(packs);
    }

    public boolean hasInstalledPack(@Nonnull String packId) {
        return installedPacks.contains(packId);
    }
}
