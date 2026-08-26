package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorCreateContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Creates the contributor state owned by one composition lifecycle. */
final class CommandHudCompositionStateFactory {
    private CommandHudCompositionStateFactory() { }

    @Nonnull
    static <B> List<CommandHudCompositionState<B>> create(
            @Nonnull CommandHudOpenContext openContext,
            @Nonnull List<CommandHudCompositionBinding<B>> bindings,
            @Nonnull CommandHudCompositionState.DirtyCallback<B> dirtyCallback
    ) {
        Objects.requireNonNull(openContext, "openContext");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(dirtyCallback, "dirtyCallback");
        List<CommandHudCompositionState<B>> created = new ArrayList<>(bindings.size());
        Set<CommandHudContributorId> ids = new HashSet<>();
        try {
            for (CommandHudCompositionBinding<B> binding : bindings) {
                Objects.requireNonNull(binding, "binding");
                if (!ids.add(binding.id())) throw new IllegalArgumentException(
                        "HUD contributor IDs must be unique.");
                CommandHudCompositionState<B> state = new CommandHudCompositionState<>(
                        binding, dirtyCallback);
                try {
                    state.contributor = binding.factory().create(new CommandHudContributorCreateContext(
                            openContext, binding.id(), binding.generation(), state.sink));
                    if (state.contributor == null) state.failure = "contributor factory returned null";
                } catch (RuntimeException | LinkageError failure) {
                    state.failure = failure.getClass().getSimpleName();
                }
                state.dirty.markAll();
                created.add(state);
            }
        } catch (RuntimeException | LinkageError failure) {
            CommandHudCompositionCleanup.closeStates(created);
            throw failure;
        }
        return List.copyOf(created);
    }
}
