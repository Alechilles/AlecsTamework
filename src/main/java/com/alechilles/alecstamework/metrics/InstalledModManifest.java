package com.alechilles.alecstamework.metrics;

import java.nio.file.Path;

/**
 * Parsed manifest metadata for a mod discovered on disk.
 */
public record InstalledModManifest(
        String group,
        String name,
        String version,
        boolean dependsOnTamework,
        Path sourcePath,
        long sourceLastModifiedMillis
) {
    public String modId() {
        return group + ":" + name;
    }
}
