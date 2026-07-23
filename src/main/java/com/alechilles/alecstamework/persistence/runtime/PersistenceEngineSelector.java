package com.alechilles.alecstamework.persistence.runtime;

import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Constructs exactly one process persistence engine from an immutable choice.
 *
 * <p>Factories are intentionally lazy. The unselected implementation performs
 * no class construction, database access, migration, or background startup.</p>
 */
public final class PersistenceEngineSelector {
    private PersistenceEngineSelector() {
    }

    /** Constructs only the selected engine and rejects a null factory result. */
    @Nonnull
    public static <T> T construct(
            @Nonnull PersistenceEngineSelection selection,
            @Nonnull Supplier<? extends T> next,
            @Nonnull Supplier<? extends T> legacy
    ) {
        if (selection == null || next == null || legacy == null) {
            throw new IllegalArgumentException(
                    "Selection and both lazy engine factories are required"
            );
        }
        T selected = switch (selection.mode()) {
            case NEXT -> next.get();
            case LEGACY -> legacy.get();
        };
        if (selected == null) {
            throw new IllegalStateException(
                    "selected_persistence_engine_factory_returned_null"
            );
        }
        return selected;
    }
}
