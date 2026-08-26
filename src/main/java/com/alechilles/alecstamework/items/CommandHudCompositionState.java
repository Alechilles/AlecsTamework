package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDirtySink;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Mutable session-local state for one exact HUD contributor binding. */
final class CommandHudCompositionState<B> {
    @FunctionalInterface
    interface DirtyCallback<B> {
        void mark(@Nonnull CommandHudCompositionState<B> state,
                  @Nonnull CommandHudDirtyScope scope);
    }

    @Nonnull
    final CommandHudCompositionBinding<B> binding;
    @Nonnull
    final CommandHudDirtyState dirty = new CommandHudDirtyState();
    @Nonnull
    final CommandHudContributorDirtySink sink;
    @Nullable
    CommandHudContributorSession<B> contributor;
    @Nullable
    CommandHudContribution lastValidContribution;
    @Nullable
    CommandHudContribution lastPublishedContribution;
    @Nullable
    String failure;
    boolean registrationLost;
    boolean unavailablePublished;
    @Nullable
    AutoCloseable unregisterSubscription;

    CommandHudCompositionState(
            @Nonnull CommandHudCompositionBinding<B> binding,
            @Nonnull DirtyCallback<B> dirtyCallback
    ) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.sink = new DirtySink<>(this, dirtyCallback);
    }

    private static final class DirtySink<B> implements CommandHudContributorDirtySink {
        private final CommandHudCompositionState<B> state;
        private final DirtyCallback<B> callback;

        private DirtySink(
                CommandHudCompositionState<B> state,
                DirtyCallback<B> callback
        ) {
            this.state = state;
            this.callback = callback;
        }

        @Override
        public void markPathsDirty(@Nonnull java.util.Set<String> paths) {
            callback.mark(state, CommandHudDirtyScope.paths(
                    Objects.requireNonNull(paths, "paths")));
        }

        @Override
        public void markAllDirty() {
            callback.mark(state, CommandHudDirtyScope.full());
        }
    }
}
