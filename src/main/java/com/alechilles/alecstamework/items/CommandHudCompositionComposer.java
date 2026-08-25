package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared, bounded contributor composition for both HUD surfaces. */
final class CommandHudCompositionComposer<B, V, U> {
    private final CommandHudCompositionSupport.SurfaceAdapter<B, V, U> adapter;
    private final List<CommandHudCompositionState<B>> states;
    private final Map<CommandHudContributorId, CommandHudContribution> compatibility;
    private final boolean custom;
    private final BooleanSupplier rendererActive;
    private final UUID sessionId;
    @Nullable
    private final CommandHudDiagnosticsService diagnostics;
    @Nullable
    private final CommandHudTimingWarnings timingWarnings;

    CommandHudCompositionComposer(
            @Nonnull CommandHudCompositionSupport.SurfaceAdapter<B, V, U> adapter,
            @Nonnull List<CommandHudCompositionState<B>> states,
            @Nonnull Map<CommandHudContributorId, CommandHudContribution> compatibility,
            boolean custom,
            @Nonnull BooleanSupplier rendererActive,
            @Nonnull UUID sessionId,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.states = List.copyOf(Objects.requireNonNull(states, "states"));
        this.compatibility = Map.copyOf(Objects.requireNonNull(compatibility,
                "compatibility"));
        this.custom = custom;
        this.rendererActive = Objects.requireNonNull(rendererActive, "rendererActive");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.diagnostics = diagnostics;
        this.timingWarnings = timingWarnings;
    }

    boolean hasDirty() {
        if (custom && !activeRenderer()) return true;
        for (CommandHudCompositionState<B> state : states) {
            if (state.dirty.dirty() || !isActive(state)) return true;
        }
        return false;
    }

    @Nonnull
    CommandHudCompositionSupport.SurfaceAdapter<B, V, U> adapter() {
        return adapter;
    }

    @Nonnull
    Composition<V> compose(@Nonnull B base, boolean initial) {
        if (custom && !activeRenderer()) {
            throw new RendererUnavailableFailure("renderer registration is no longer active");
        }
        Map<CommandHudContributorId, CommandHudContribution> values =
                new LinkedHashMap<>(compatibility);
        CommandHudCompositionSupport.ChangeData changes =
                new CommandHudCompositionSupport.ChangeData(initial);
        for (CommandHudCompositionState<B> state : states) {
            if (!initial && !state.dirty.dirty() && isActive(state)) {
                if (state.lastPublishedContribution != null) {
                    values.put(state.binding.id(), state.lastPublishedContribution);
                }
                continue;
            }
            CommandHudDirtyScope scope = initial
                    ? initialScope(state) : state.dirty.take();
            if (!isActive(state)) {
                if (state.binding.required()) {
                    throw required(state, "contributor registration is no longer active");
                }
                state.lastValidContribution = null;
                state.lastPublishedContribution = CommandHudContribution.unavailable(
                        state.binding.id(), "optional contributor registration is unavailable");
                values.put(state.binding.id(), state.lastPublishedContribution);
                changes.add(state.binding.id(), CommandHudDirtyScope.full());
                continue;
            }
            CommandHudContribution contribution = composeContributor(state, base, scope);
            if (contribution != null) {
                state.lastPublishedContribution = contribution;
                values.put(state.binding.id(), contribution);
            }
            changes.add(state.binding.id(), scope);
        }
        return new Composition<>(adapter.view(base, values), changes);
    }

    @Nonnull
    private static CommandHudDirtyScope initialScope(
            @Nonnull CommandHudCompositionState<?> state
    ) {
        state.dirty.clear();
        return CommandHudDirtyScope.full();
    }

    private boolean activeRenderer() {
        try {
            return rendererActive.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private boolean isActive(@Nonnull CommandHudCompositionState<B> state) {
        if (state.registrationLost) return false;
        try {
            return state.binding.active().getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            state.failure = failure.getClass().getSimpleName();
            return false;
        }
    }

    @Nullable
    private CommandHudContribution composeContributor(
            @Nonnull CommandHudCompositionState<B> state,
            @Nonnull B base,
            @Nonnull CommandHudDirtyScope scope
    ) {
        state.failure = null;
        long started = diagnostics == null
                ? timingWarnings == null ? 0L : timingWarnings.start()
                : diagnostics.compositionStarted();
        CommandHudContribution result = null;
        try {
            if (state.contributor == null) {
                state.failure = "contributor factory returned null";
                return compositionFailure(state);
            }
            CommandHudContribution contribution = state.contributor.compose(
                    base, state.lastValidContribution, scope);
            if (contribution == null
                    || !state.binding.id().equals(contribution.contributorId())) {
                state.failure = contribution == null
                        ? "contributor returned null"
                        : "contributor returned a different contributor ID";
                return compositionFailure(state);
            }
            CommandHudValueBounds.Validation bounds =
                    CommandHudValueBounds.validateContribution(contribution);
            if (!bounds.valid()) {
                state.failure = bounds.message();
                return compositionFailure(state);
            }
            state.lastValidContribution = contribution;
            result = contribution;
            return result;
        } catch (RuntimeException | LinkageError failure) {
            state.failure = failure.getClass().getSimpleName();
            return compositionFailure(state);
        } finally {
            finishTiming(state, started, result);
        }
    }

    @Nullable
    private CommandHudContribution compositionFailure(
            @Nonnull CommandHudCompositionState<B> state
    ) {
        if (state.binding.required()) throw required(state,
                state.failure == null ? "required contributor failed" : state.failure);
        return CommandHudContribution.failed(state.binding.id(), state.failure);
    }

    private CommandHudCompositionSession.RequiredCompositionFailure required(
            @Nonnull CommandHudCompositionState<B> state,
            @Nonnull String reason
    ) {
        return new CommandHudCompositionSession.RequiredCompositionFailure(
                new CommandHudCompositionSession.RequiredFailure(state.binding.id(), reason));
    }

    private void finishTiming(
            @Nonnull CommandHudCompositionState<B> state,
            long started,
            @Nullable CommandHudContribution result
    ) {
        String status = result == null
                ? state.binding.required() ? "REQUIRED_FAILED" : "FAILED"
                : result.status().name();
        if (diagnostics != null) {
            diagnostics.compositionFinished(sessionId, state.binding.id().value(),
                    state.binding.generation(), started, status, state.failure);
        } else if (timingWarnings != null) {
            timingWarnings.finish(state.binding.id().value(),
                    state.binding.generation(), started);
        }
    }

    static final class RendererUnavailableFailure extends RuntimeException {
        RendererUnavailableFailure(@Nonnull String reason) {
            super(Objects.requireNonNull(reason, "reason"));
        }
    }

    record Composition<V>(
            @Nonnull V view,
            @Nonnull CommandHudCompositionSupport.ChangeData changeData
    ) {
    }
}
