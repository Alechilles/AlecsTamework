package com.alechilles.alecstamework.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Supplies and changes the active-NPC highlight preference for one command panel. */
public record CommandActiveHighlightBinding(
        @Nonnull Supplier<Boolean> enabledSupplier,
        @Nonnull Consumer<Boolean> setEnabledCallback
) {
}
