package com.alechilles.alecstamework.api.commandhud;

import java.util.Set;
import javax.annotation.Nonnull;

/** Guarded invalidation surface for one command HUD session contributor. */
public interface CommandHudContributorDirtySink {
    /** Marks contributor-local paths dirty. */
    void markPathsDirty(@Nonnull Set<String> paths);

    /** Marks the complete contributor namespace dirty. */
    void markAllDirty();

    /** Returns a no-op sink for detached or unavailable adapters. */
    @Nonnull
    static CommandHudContributorDirtySink noop() {
        return NoOpHolder.INSTANCE;
    }

    /** Shared stateless no-op implementation. */
    final class NoOpHolder {
        private static final CommandHudContributorDirtySink INSTANCE =
                new CommandHudContributorDirtySink() {
                    @Override
                    public void markPathsDirty(Set<String> paths) {
                    }

                    @Override
                    public void markAllDirty() {
                    }
                };

        private NoOpHolder() {
        }
    }
}
