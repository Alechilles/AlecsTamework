package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Path;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable metadata describing a discoverable Tamework config asset entry.
 */
public record TwConfigAssetDescriptor(
        @Nonnull TwConfigFamily family,
        @Nonnull String familyKey,
        @Nonnull String familyDisplayName,
        @Nonnull String storePath,
        @Nonnull String assetId,
        @Nonnull String sourcePackKey,
        @Nullable String parentAssetId,
        @Nullable Path sourcePath,
        @Nonnull Path relativeServerPath,
        boolean editable,
        boolean knownType,
        boolean localOnly
) {
    @Nonnull
    public String descriptorKey() {
        return familyKey.toLowerCase(Locale.ROOT)
                + "|"
                + sourcePackKey.toLowerCase(Locale.ROOT)
                + "|"
                + assetId.toLowerCase(Locale.ROOT);
    }
}
