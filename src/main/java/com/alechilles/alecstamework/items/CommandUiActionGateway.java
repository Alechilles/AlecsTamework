package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tamework-owned action handle registry and authority gate for command UI
 * sessions.
 *
 * <p>The gateway stores route, target, and generation data server-side. The
 * public handle contains only a random lookup token. Generic and bonded routes
 * use separate issue methods and cannot be selected by client input.</p>
 */
final class CommandUiActionGateway {
    static final Duration CONFIRMATION_LIFETIME = Duration.ofSeconds(5L);
    private static final int MAX_ACTIVE_HANDLES_PER_SESSION = 256;

    enum Route {
        GENERIC,
        BONDED
    }

    @FunctionalInterface
    interface ActionExecutor {
        @Nonnull
        CompletionStage<CommandUiActionResult> execute(@Nonnull CommandUiAction action);
    }

    @FunctionalInterface
    interface AuthorityCheck {
        boolean allows(@Nonnull CommandUiAction action);
    }

    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> activeSessions = new ConcurrentHashMap<>();

    CommandUiActionGateway() {
    }

    /** Registers a session so handles cannot cross a session boundary. */
    void openSession(@Nonnull UUID sessionId) {
        openSession(sessionId, 0L);
    }

    /** Registers a session and its provider registration generation. */
    void openSession(@Nonnull UUID sessionId, long providerGeneration) {
        if (providerGeneration < 0L) {
            throw new IllegalArgumentException("Provider generation cannot be negative.");
        }
        activeSessions.put(sessionId, providerGeneration);
    }

    /** Issues a handle for one route and current authority generation. */
    @Nonnull
    CommandUiActionHandle issue(
            @Nonnull UUID sessionId,
            @Nonnull Route route,
            @Nonnull CommandUiAction action,
            long generation,
            @Nullable AuthorityCheck authority,
            @Nonnull ActionExecutor executor,
            boolean confirmationRequired
    ) {
        if (generation < 0L) {
            throw new IllegalArgumentException("Action generation cannot be negative.");
        }
        activeSessions.putIfAbsent(sessionId, 0L);
        long providerGeneration = activeSessions.get(sessionId);
        retireOlderGeneration(sessionId, generation);
        CommandUiActionHandle handle = new CommandUiActionHandle(
                UUID.randomUUID().toString());
        Binding binding = new Binding(
                sessionId,
                route,
                action,
                generation,
                providerGeneration,
                authority == null ? ignored -> true : authority,
                executor,
                confirmationRequired || action.confirmationRequired(),
                false,
                0L
        );
        bindings.put(handle.token(), binding);
        trimSession(sessionId);
        return handle;
    }

    @Nonnull
    CommandUiActionHandle issueGeneric(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiAction action,
            long generation,
            @Nullable BooleanSupplier authority,
            @Nonnull Supplier<CompletionStage<CommandUiActionResult>> executor,
            boolean confirmationRequired
    ) {
        return issue(sessionId, Route.GENERIC, action, generation,
                authority == null ? null : ignored -> authority.getAsBoolean(),
                ignored -> complete(executor), confirmationRequired);
    }

    @Nonnull
    CommandUiActionHandle issueBonded(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiAction action,
            long generation,
            @Nullable BooleanSupplier authority,
            @Nonnull Supplier<CompletionStage<CommandUiActionResult>> executor,
            boolean confirmationRequired
    ) {
        return issue(sessionId, Route.BONDED, action, generation,
                authority == null ? null : ignored -> authority.getAsBoolean(),
                ignored -> complete(executor), confirmationRequired);
    }

    /** Convenience synchronous executor adapter. */
    @Nonnull
    CommandUiActionHandle issueGeneric(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiAction action,
            long generation,
            @Nullable BooleanSupplier authority,
            @Nonnull Runnable executor,
            boolean confirmationRequired
    ) {
        return issueGeneric(sessionId, action, generation, authority,
                () -> {
                    executor.run();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, confirmationRequired);
    }

    /** Invokes a handle after session, generation, expiry, and authority checks. */
    @Nonnull
    CompletionStage<CommandUiActionResult> invoke(
            @Nonnull UUID sessionId,
            @Nullable CommandUiActionHandle handle,
            long currentGeneration,
            @Nonnull Route expectedRoute
    ) {
        Long providerGeneration = activeSessions.get(sessionId);
        if (providerGeneration == null || handle == null) {
            return completed(CommandUiActionResult.stale(
                    "command UI action handle is not valid"));
        }
        Binding binding = bindings.get(handle.token());
        if (binding == null) {
            return completed(CommandUiActionResult.stale(
                    "command UI action handle is stale"));
        }
        if (!sessionId.equals(binding.sessionId)) {
            return completed(CommandUiActionResult.denied(
                    "command UI action handle belongs to another session"));
        }
        if (binding.route != expectedRoute) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.denied(
                    "command UI action route is not valid for this session"));
        }
        if (binding.generation != currentGeneration) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI action generation is stale"));
        }
        if (binding.providerGeneration != providerGeneration) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI provider generation is stale"));
        }
        if (binding.confirmationToken && binding.expiresAtNanos < System.nanoTime()) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "confirmation handle expired"));
        }
        if (!allows(binding)) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.denied(
                    "current command authority denied the action"));
        }
        if (!binding.consumed.compareAndSet(false, true)) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI action handle was already used"));
        }

        if (binding.requiresConfirmation && !binding.confirmationToken) {
            bindings.remove(handle.token(), binding);
            CommandUiActionHandle confirmation = issue(
                    binding.sessionId,
                    binding.route,
                    binding.action,
                    binding.generation,
                    binding.providerGeneration,
                    binding.authority,
                    binding.executor,
                    false,
                    true,
                    System.nanoTime() + CONFIRMATION_LIFETIME.toNanos()
            );
            return completed(CommandUiActionResult.confirmationRequired(
                    confirmation, "confirmation required", null));
        }
        try {
            CompletionStage<CommandUiActionResult> stage = binding.executor.execute(
                    binding.action);
            if (stage == null) {
                bindings.remove(handle.token(), binding);
                return completed(CommandUiActionResult.failed(
                        "action returned no result"));
            }
            return stage.whenComplete((ignored, failure) ->
                    bindings.remove(handle.token(), binding));
        } catch (RuntimeException | LinkageError failure) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.failed("action execution failed"));
        }
    }

    /** Retires handles that cannot be valid after an authority generation change. */
    void advanceGeneration(@Nonnull UUID sessionId, long generation) {
        if (generation < 0L) {
            throw new IllegalArgumentException("Action generation cannot be negative.");
        }
        bindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().generation < generation);
    }

    /** Invalidates all handles for one session and rejects later invocations. */
    void closeSession(@Nonnull UUID sessionId) {
        activeSessions.remove(sessionId);
        bindings.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().sessionId));
    }

    /** Invalidates every issued handle. Used by runtime shutdown. */
    void close() {
        activeSessions.clear();
        bindings.clear();
    }

    int activeHandleCount() {
        return bindings.size();
    }

    private boolean allows(Binding binding) {
        try {
            return binding.authority.allows(binding.action);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nonnull
    private CommandUiActionHandle issue(
            UUID sessionId,
            Route route,
            CommandUiAction action,
            long generation,
            long providerGeneration,
            AuthorityCheck authority,
            ActionExecutor executor,
            boolean confirmationRequired,
            boolean confirmationToken,
            long expiresAtNanos
    ) {
        CommandUiActionHandle handle = new CommandUiActionHandle(
                UUID.randomUUID().toString());
        bindings.put(handle.token(), new Binding(
                sessionId, route, action, generation, providerGeneration,
                authority, executor, confirmationRequired,
                confirmationToken, expiresAtNanos));
        trimSession(sessionId);
        return handle;
    }

    /** Keeps the registry proportional to the current page rather than its lifetime. */
    private void trimSession(UUID sessionId) {
        while (countSessionHandles(sessionId) > MAX_ACTIVE_HANDLES_PER_SESSION) {
            String oldestToken = null;
            long oldestIssuedAt = Long.MAX_VALUE;
            for (var entry : bindings.entrySet()) {
                Binding binding = entry.getValue();
                if (sessionId.equals(binding.sessionId)
                        && binding.issuedAtNanos < oldestIssuedAt) {
                    oldestToken = entry.getKey();
                    oldestIssuedAt = binding.issuedAtNanos;
                }
            }
            if (oldestToken == null || bindings.remove(oldestToken) == null) {
                return;
            }
        }
    }

    private int countSessionHandles(UUID sessionId) {
        int count = 0;
        for (Binding binding : bindings.values()) {
            if (sessionId.equals(binding.sessionId)) count++;
        }
        return count;
    }

    private void retireOlderGeneration(UUID sessionId, long generation) {
        bindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().generation < generation);
    }

    @Nonnull
    private static CompletionStage<CommandUiActionResult> complete(
            @Nonnull Supplier<CompletionStage<CommandUiActionResult>> source
    ) {
        try {
            CompletionStage<CommandUiActionResult> result = source.get();
            return result == null ? completed(CommandUiActionResult.failed(
                    "action returned no result")) : result;
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed("action execution failed"));
        }
    }

    @Nonnull
    private static CompletionStage<CommandUiActionResult> completed(
            @Nonnull CommandUiActionResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    private record Binding(
            UUID sessionId,
            Route route,
            CommandUiAction action,
            long generation,
            long providerGeneration,
            AuthorityCheck authority,
            ActionExecutor executor,
            boolean requiresConfirmation,
            boolean confirmationToken,
            long expiresAtNanos,
            long issuedAtNanos,
            AtomicBoolean consumed
    ) {
        private Binding(
                UUID sessionId,
                Route route,
                CommandUiAction action,
                long generation,
                long providerGeneration,
                AuthorityCheck authority,
                ActionExecutor executor,
                boolean requiresConfirmation,
                boolean confirmationToken,
                long expiresAtNanos
        ) {
            this(sessionId, route, action, generation, providerGeneration,
                    authority, executor,
                    requiresConfirmation, confirmationToken, expiresAtNanos,
                    System.nanoTime(),
                    new AtomicBoolean());
        }
    }
}
