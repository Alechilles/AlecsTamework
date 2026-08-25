package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Session-scoped producer of detached command UI presentation data. */
public interface CommandUiSessionContributor extends AutoCloseable {
    /**
     * Composes this contributor's namespace from the base snapshot.
     *
     * @param base detached Tamework snapshot
     * @param previous this contributor's last valid contribution, or null
     * @param scope bounded contributor-local invalidation scope
     */
    CommandUiContribution compose(
            CommandUiSnapshot base,
            @Nullable CommandUiContribution previous,
            @Nonnull CommandUiDirtyScope scope
    );

    /** Releases contributor-local state when the command UI session closes. */
    @Override
    default void close() {
    }
}
