package com.alechilles.alecstamework.items;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Small validity helpers kept outside the session lifecycle owner. */
final class CommandHudCompositionLifecycleSupport {
    private CommandHudCompositionLifecycleSupport() {
    }

    static boolean isCurrent(
            boolean open,
            long lifecycleVersion,
            long version,
            boolean custom,
            @Nonnull BooleanSupplier rendererActive,
            @Nonnull BooleanSupplier contributorsCurrent
    ) {
        return open && lifecycleVersion == version
                && (!custom || rendererActive.getAsBoolean())
                && contributorsCurrent.getAsBoolean();
    }

    static <B> boolean retryAfterOptionalInvalidation(
            boolean open,
            long lifecycleVersion,
            long version,
            boolean requiredFailure,
            @Nonnull BooleanSupplier rendererActive,
            @Nonnull Iterable<CommandHudCompositionState<B>> states
    ) {
        if (!open || requiredFailure || lifecycleVersion == version
                || !rendererActive.getAsBoolean()) return false;
        for (CommandHudCompositionState<B> state : states) {
            if (state.registrationLost && !state.binding.required()) return true;
        }
        return false;
    }

    static <U> void publish(
            @Nonnull Object lock,
            @Nonnull U update,
            long version,
            @Nonnull BooleanSupplier current,
            @Nonnull Consumer<U> publisher
    ) {
        synchronized (lock) {
            if (!current.getAsBoolean()) return;
            try {
                publisher.accept(update);
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }
}
