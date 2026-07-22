package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Capability-gated query and mutation surface for timed command-roster projections. */
public interface CommandTimedSummoningApi {
    @Nonnull Optional<CommandTimedSummoningView> get(@Nonnull CommandTimedSummoningRequest identity);
    @Nonnull CompletionStage<CommandTimedSummoningResult> summon(@Nonnull CommandTimedSummoningRequest request);
    @Nonnull CompletionStage<CommandTimedSummoningResult> dismiss(@Nonnull CommandTimedSummoningRequest request);
    @Nonnull AutoCloseable subscribe(@Nonnull Consumer<CommandTimedSummoningChangedEvent> listener);

    static CommandTimedSummoningApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    final class UnavailableHolder {
        private static final CommandTimedSummoningApi INSTANCE = new CommandTimedSummoningApi() {
            public Optional<CommandTimedSummoningView> get(CommandTimedSummoningRequest identity) {
                if (identity == null) throw new NullPointerException("identity");
                return Optional.empty();
            }
            public CompletionStage<CommandTimedSummoningResult> summon(CommandTimedSummoningRequest request) {
                return unavailableResult(request);
            }
            public CompletionStage<CommandTimedSummoningResult> dismiss(CommandTimedSummoningRequest request) {
                return unavailableResult(request);
            }
            public AutoCloseable subscribe(Consumer<CommandTimedSummoningChangedEvent> listener) {
                if (listener == null) throw new NullPointerException("listener");
                return () -> { };
            }
            private CompletionStage<CommandTimedSummoningResult> unavailableResult(
                    CommandTimedSummoningRequest request) {
                if (request == null) throw new NullPointerException("request");
                return CompletableFuture.completedFuture(new CommandTimedSummoningResult(
                        CommandTimedSummoningResult.Status.UNAVAILABLE,
                        "command-timed-summoning-unavailable", null));
            }
        };
        private UnavailableHolder() { }
    }
}
