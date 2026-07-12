package com.alechilles.alecstamework.items;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Runs every post-admission cull side effect without allowing one failure to strand the callback. */
final class CommandOwnerCullContinuation {
    private final FailureSink failureSink;

    CommandOwnerCullContinuation(@Nonnull FailureSink failureSink) {
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    void run(@Nonnull Step... steps) {
        Objects.requireNonNull(steps, "steps");
        for (Step step : steps) {
            if (step == null) {
                continue;
            }
            try {
                step.action().run();
            } catch (RuntimeException | LinkageError failure) {
                reportFailure(step.name(), failure);
            }
        }
    }

    private void reportFailure(@Nonnull String action, @Nonnull Throwable failure) {
        try {
            failureSink.onFailure(action, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // A diagnostic callback must never abort the remaining post-admission work.
        }
    }

    record Step(@Nonnull String name, @Nonnull Runnable action) {
        Step {
            name = Objects.requireNonNull(name, "name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            action = Objects.requireNonNull(action, "action");
        }
    }

    @FunctionalInterface
    interface FailureSink {
        void onFailure(@Nonnull String action, @Nonnull Throwable failure);
    }
}
