package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Internal boundary that schedules all session authority work on one world. */
@FunctionalInterface
interface CommandUiWorldDispatcher {
    <T> CompletionStage<T> dispatch(Supplier<T> operation);

    static CommandUiWorldDispatcher direct() {
        return new CommandUiWorldDispatcher() {
            @Override
            public <T> CompletionStage<T> dispatch(Supplier<T> operation) {
                Objects.requireNonNull(operation, "operation");
                try {
                    return CompletableFuture.completedFuture(operation.get());
                } catch (RuntimeException | LinkageError failure) {
                    return failed(failure);
                }
            }
        };
    }

    static CommandUiWorldDispatcher executor(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return new CommandUiWorldDispatcher() {
            @Override
            public <T> CompletionStage<T> dispatch(Supplier<T> operation) {
                Objects.requireNonNull(operation, "operation");
                CompletableFuture<T> result = new CompletableFuture<>();
                try {
                    executor.execute(() -> {
                        try {
                            result.complete(operation.get());
                        } catch (RuntimeException | LinkageError failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                } catch (RuntimeException | LinkageError failure) {
                    result.completeExceptionally(failure);
                }
                return result;
            }
        };
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }
}
