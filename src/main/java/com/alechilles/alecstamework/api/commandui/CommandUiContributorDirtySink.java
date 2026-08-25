package com.alechilles.alecstamework.api.commandui;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Guarded invalidation surface for one session contributor. */
public interface CommandUiContributorDirtySink {
    /** Marks the complete contributor namespace dirty. */
    void markAllDirty();

    /** Marks only page-level contributor data dirty. */
    void markPageDirty();

    /** Marks contributor-local paths dirty. */
    void markPathsDirty(@Nonnull Set<String> paths);

    /** Marks selected existing companion rows dirty. */
    void markRowsDirty(@Nonnull Set<UUID> rowIds);

    /** Returns a no-op sink for adapters that cannot publish invalidations. */
    @Nonnull
    static CommandUiContributorDirtySink noop() {
        return NoOpHolder.INSTANCE;
    }

    /** Shared stateless no-op implementation. */
    final class NoOpHolder {
        private static final CommandUiContributorDirtySink INSTANCE = new CommandUiContributorDirtySink() {
            @Override
            public void markAllDirty() {
            }

            @Override
            public void markPageDirty() {
            }

            @Override
            public void markPathsDirty(Set<String> paths) {
            }

            @Override
            public void markRowsDirty(Set<UUID> rowIds) {
            }
        };

        private NoOpHolder() {
        }
    }
}
