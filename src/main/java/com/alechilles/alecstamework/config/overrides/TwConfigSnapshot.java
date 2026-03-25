package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable discovery snapshot used by the in-game config editor session.
 */
public final class TwConfigSnapshot {
    private final Path overrideRoot;
    private final String baselineHash;
    private final List<TwConfigAssetDescriptor> descriptors;
    private final Map<String, FamilyView> families;
    private final Map<String, TwConfigAssetDescriptor> byDescriptorKey;

    public TwConfigSnapshot(@Nonnull Path overrideRoot,
                            @Nonnull String baselineHash,
                            @Nonnull List<TwConfigAssetDescriptor> descriptors,
                            @Nonnull Map<String, FamilyView> families) {
        this.overrideRoot = overrideRoot;
        this.baselineHash = baselineHash;
        this.descriptors = List.copyOf(descriptors);
        this.families = Collections.unmodifiableMap(new LinkedHashMap<>(families));
        LinkedHashMap<String, TwConfigAssetDescriptor> index = new LinkedHashMap<>();
        for (TwConfigAssetDescriptor descriptor : this.descriptors) {
            if (descriptor == null) {
                continue;
            }
            index.put(descriptor.descriptorKey(), descriptor);
        }
        this.byDescriptorKey = Collections.unmodifiableMap(index);
    }

    @Nonnull
    public Path getOverrideRoot() {
        return overrideRoot;
    }

    @Nonnull
    public String getBaselineHash() {
        return baselineHash;
    }

    @Nonnull
    public List<TwConfigAssetDescriptor> getDescriptors() {
        return descriptors;
    }

    @Nonnull
    public Map<String, FamilyView> getFamilies() {
        return families;
    }

    @Nullable
    public TwConfigAssetDescriptor getDescriptor(@Nullable String descriptorKey) {
        if (descriptorKey == null || descriptorKey.isBlank()) {
            return null;
        }
        return byDescriptorKey.get(descriptorKey);
    }

    /**
     * Lightweight family metadata for UI dropdowns and badges.
     */
    public record FamilyView(
            @Nonnull String key,
            @Nonnull String label,
            boolean editable,
            boolean knownType
    ) {
    }
}
