package com.alechilles.alecstamework.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Separates the canonical write directory from every historical directory that
 * may contain immutable persistence import evidence.
 */
public record TameworkDataPathLayout(
        @Nonnull Path targetDirectory,
        @Nonnull Path legacyDirectory,
        @Nonnull Optional<Path> historicalDirectory
) {
    public TameworkDataPathLayout {
        if (targetDirectory == null || legacyDirectory == null
                || historicalDirectory == null) {
            throw new IllegalArgumentException(
                    "Target, legacy, and historical data paths are required"
            );
        }
        targetDirectory = normalize(targetDirectory);
        legacyDirectory = normalize(legacyDirectory);
        historicalDirectory = historicalDirectory.map(
                TameworkDataPathLayout::normalize
        );
    }

    /**
     * Returns de-duplicated source candidates in current, legacy, historical
     * order. Callers may inspect these paths but must never relocate their files.
     */
    @Nonnull
    public List<Path> persistenceSourceDirectories() {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        unique.add(targetDirectory);
        unique.add(legacyDirectory);
        historicalDirectory.ifPresent(unique::add);
        return List.copyOf(new ArrayList<>(unique));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
