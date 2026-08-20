package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Creates command-page callbacks that recheck live authority before a mutation. */
final class CommandSelectionCallbackGuards {
    private CommandSelectionCallbackGuards() {
    }

    static Consumer<UUID> guardedUuid(@Nonnull BooleanSupplier authority,
                                      @Nonnull Consumer<UUID> callback) {
        return uuid -> {
            if (authority.getAsBoolean()) {
                callback.accept(uuid);
            }
        };
    }

    static Consumer<Boolean> guardedBoolean(@Nonnull BooleanSupplier authority,
                                             @Nonnull Consumer<Boolean> callback) {
        return value -> {
            if (authority.getAsBoolean()) {
                callback.accept(value);
            }
        };
    }

    static Consumer<String> guardedString(@Nonnull BooleanSupplier authority,
                                           @Nonnull Consumer<String> callback) {
        return value -> {
            if (authority.getAsBoolean()) {
                callback.accept(value);
            }
        };
    }

    static BiConsumer<UUID, String> guardedPair(
            @Nonnull BooleanSupplier authority,
            @Nonnull BiConsumer<UUID, String> callback
    ) {
        return (uuid, value) -> {
            if (authority.getAsBoolean()) {
                callback.accept(uuid, value);
            }
        };
    }

    static Runnable guardedAction(@Nonnull BooleanSupplier authority,
                                  @Nonnull Runnable callback) {
        return () -> {
            if (authority.getAsBoolean()) {
                callback.run();
            }
        };
    }
}
