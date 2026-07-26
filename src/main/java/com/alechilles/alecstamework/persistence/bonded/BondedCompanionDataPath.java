package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import java.nio.file.Path;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Resolves the standalone bonded database inside Tamework's universe directory. */
public final class BondedCompanionDataPath {
    public static final String FILE_NAME = "bonded-companions.sqlite";

    private BondedCompanionDataPath() {
    }

    /** Returns the normalized standalone bonded database path. */
    @Nonnull
    public static Path resolve(@Nonnull TameworkDataPathLayout layout) {
        return Objects.requireNonNull(layout, "layout")
                .targetDirectory()
                .resolve(FILE_NAME)
                .toAbsolutePath()
                .normalize();
    }
}
