package com.alechilles.alecstamework;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Keeps runtime consumers closed before their public API registries. */
final class TameworkShutdownSequence {
    private TameworkShutdownSequence() {
    }

    static void run(
            @Nonnull Runnable closeRuntime,
            @Nonnull Runnable closeRuntimeDependents,
            @Nonnull Runnable closeApi
    ) {
        Objects.requireNonNull(closeRuntime, "closeRuntime").run();
        Objects.requireNonNull(closeRuntimeDependents, "closeRuntimeDependents").run();
        Objects.requireNonNull(closeApi, "closeApi").run();
    }
}
