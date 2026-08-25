package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionRequest;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionContext;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
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

    enum Route {
        GENERIC,
        BONDED,
        CONTRIBUTOR
    }

    enum InputPolicy {
        NONE,
        OPTIONAL_TEXT,
        REQUIRED_TEXT
    }

    private enum Scope {
        MAIN,
        MANAGED
    }

    @FunctionalInterface
    interface ActionExecutor {
        @Nonnull
        CompletionStage<CommandUiActionResult> execute(@Nonnull CommandUiAction action);
    }

    @FunctionalInterface
    interface RequestActionExecutor {
        @Nonnull
        CompletionStage<CommandUiActionResult> execute(
                @Nonnull CommandUiAction action,
                @Nullable String textInput
        );
    }

    @FunctionalInterface
    interface AuthorityCheck {
        boolean allows(@Nonnull CommandUiAction action);
    }

    @FunctionalInterface
    interface ContributorGenerationCheck {
        boolean active();
    }

    @FunctionalInterface
    interface ContributorAuthorityCheck {
        boolean allows(@Nonnull CommandUiContributorActionContext context);
    }

    /** Stable identities retained by one contributor action binding. */
    record ContributorIdentity(
            @Nonnull UUID playerUuid,
            @Nullable String configId,
            @Nullable UUID rowId,
            @Nullable UUID companionId,
            @Nullable String profileId
    ) {
        ContributorIdentity {
            Objects.requireNonNull(playerUuid, "playerUuid");
        }
    }

    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ContributorBinding> contributorBindings =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, SessionBinding> activeSessions =
            new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    CommandUiActionGateway() {
        this(System::nanoTime);
    }

    CommandUiActionGateway(@Nonnull LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
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
        activeSessions.put(sessionId, new SessionBinding(providerGeneration));
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
        return issueRequest(sessionId, route, action, Scope.MAIN, generation,
                authority, (boundAction, ignored) -> executor.execute(boundAction),
                InputPolicy.NONE, 0, confirmationRequired);
    }

    /** Issues a main-snapshot handle with a server-owned input policy. */
    @Nonnull
    CommandUiActionHandle issueRequest(
            @Nonnull UUID sessionId,
            @Nonnull Route route,
            @Nonnull CommandUiAction action,
            long generation,
            @Nullable AuthorityCheck authority,
            @Nonnull RequestActionExecutor executor,
            @Nonnull InputPolicy inputPolicy,
            int maximumInputLength,
            boolean confirmationRequired
    ) {
        return issueRequest(sessionId, route, action, Scope.MAIN, generation,
                authority, executor, inputPolicy, maximumInputLength,
                confirmationRequired);
    }

    /** Issues a managed-flow handle with an independent generation. */
    @Nonnull
    CommandUiActionHandle issueManaged(
            @Nonnull UUID sessionId,
            @Nonnull Route route,
            @Nonnull CommandUiAction action,
            long generation,
            @Nullable AuthorityCheck authority,
            @Nonnull RequestActionExecutor executor,
            @Nonnull InputPolicy inputPolicy,
            int maximumInputLength,
            boolean confirmationRequired
    ) {
        SessionBinding session = activeSessions.get(sessionId);
        if (session == null || generation <= 0L
                || session.managedGeneration.get() != generation) {
            throw new IllegalStateException(
                    "Managed flow generation is not active.");
        }
        return issueRequest(sessionId, route, action, Scope.MANAGED, generation,
                authority, executor, inputPolicy, maximumInputLength,
                confirmationRequired);
    }

    @Nonnull
    private CommandUiActionHandle issueRequest(
            @Nonnull UUID sessionId,
            @Nonnull Route route,
            @Nonnull CommandUiAction action,
            @Nonnull Scope scope,
            long generation,
            @Nullable AuthorityCheck authority,
            @Nonnull RequestActionExecutor executor,
            @Nonnull InputPolicy inputPolicy,
            int maximumInputLength,
            boolean confirmationRequired
    ) {
        if (generation < 0L) {
            throw new IllegalArgumentException("Action generation cannot be negative.");
        }
        validateInputPolicy(inputPolicy, maximumInputLength);
        activeSessions.putIfAbsent(sessionId, new SessionBinding(0L));
        long providerGeneration = activeSessions.get(sessionId).providerGeneration;
        if (scope == Scope.MAIN) retireOlderGeneration(sessionId, generation);
        CommandUiActionHandle handle = new CommandUiActionHandle(
                UUID.randomUUID().toString());
        Binding binding = new Binding(
                sessionId,
                route,
                action,
                scope,
                generation,
                providerGeneration,
                authority == null ? ignored -> true : authority,
                executor,
                inputPolicy,
                maximumInputLength,
                confirmationRequired || action.confirmationRequired(),
                false,
                0L,
                null
        );
        bindings.put(handle.token(), binding);
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

    /**
     * Issues one contributor-owned handle through the same session gateway as
     * built-in actions. Invisible and disabled definitions do not receive a
     * handle.
     */
    @Nullable
    CommandUiActionHandle issueContributor(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiContributorActionBinding binding,
            long actionGeneration,
            @Nonnull ContributorIdentity identity,
            @Nonnull ContributorGenerationCheck generationCheck,
            @Nullable ContributorAuthorityCheck authority
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(generationCheck, "generationCheck");
        if (actionGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Action generation cannot be negative.");
        }
        if (!binding.action().visible() || !binding.action().enabled()) {
            return null;
        }
        SessionBinding session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Command UI session is not active.");
        }
        CommandUiActionHandle handle = new CommandUiActionHandle(
                UUID.randomUUID().toString());
        contributorBindings.put(handle.token(), new ContributorBinding(
                sessionId, binding, actionGeneration, session.providerGeneration,
                identity, generationCheck,
                authority == null ? ignored -> true : authority,
                binding.action().confirmationRequired(), false, 0L, null, null,
                new AtomicBoolean(), new AtomicBoolean()));
        return handle;
    }

    /** Invokes a handle after session, generation, expiry, and authority checks. */
    @Nonnull
    CompletionStage<CommandUiActionResult> invoke(
            @Nonnull UUID sessionId,
            @Nullable CommandUiActionRequest request,
            long currentGeneration,
            @Nullable Route expectedRoute
    ) {
        SessionBinding session = activeSessions.get(sessionId);
        if (session == null || request == null) {
            return completed(CommandUiActionResult.stale(
                    "command UI action handle is not valid"));
        }
        CommandUiActionHandle handle = request.handle();
        Binding binding = bindings.get(handle.token());
        if (binding == null) {
            ContributorBinding contributor = contributorBindings.get(handle.token());
            if (contributor == null) {
                return completed(CommandUiActionResult.stale(
                        "command UI action handle is stale"));
            }
            return invokeContributor(sessionId, session, request, contributor,
                    currentGeneration);
        }
        if (!sessionId.equals(binding.sessionId)) {
            return completed(CommandUiActionResult.denied(
                    "command UI action handle belongs to another session"));
        }
        if (expectedRoute != null && binding.route != expectedRoute) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.denied(
                    "command UI action route is not valid for this session"));
        }
        long activeGeneration = binding.scope == Scope.MAIN
                ? currentGeneration : session.managedGeneration.get();
        if (binding.generation != activeGeneration) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI action generation is stale"));
        }
        if (binding.providerGeneration != session.providerGeneration) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI provider generation is stale"));
        }
        if (binding.confirmationToken && binding.expiresAtNanos < nanoTime.getAsLong()) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "confirmation handle expired"));
        }
        InputValidation input = validateInput(binding, request.input());
        if (!input.valid) {
            return completed(CommandUiActionResult.denied(input.message));
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
        if (binding.confirmationToken
                && !binding.confirmationFamilyConsumed.compareAndSet(false, true)) {
            bindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI confirmation was already used"));
        }

        if (binding.requiresConfirmation && !binding.confirmationToken) {
            binding.consumed.set(false);
            CommandUiActionHandle confirmation = issue(
                    binding.sessionId,
                    binding.route,
                    binding.action,
                    binding.scope,
                    binding.generation,
                    binding.providerGeneration,
                    binding.authority,
                    (action, ignored) -> binding.executor.execute(
                            action, input.value),
                    InputPolicy.NONE,
                    0,
                    false,
                    true,
                    nanoTime.getAsLong() + CONFIRMATION_LIFETIME.toNanos(),
                    handle.token(),
                    binding.confirmationFamilyConsumed
            );
            return completed(CommandUiActionResult.confirmationRequired(
                    confirmation, "confirmation required", null));
        }
        retireConfirmationFamily(handle.token(), binding);
        try {
            CompletionStage<CommandUiActionResult> stage = binding.executor.execute(
                    binding.action, input.value);
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

    @Nonnull
    private CompletionStage<CommandUiActionResult> invokeContributor(
            @Nonnull UUID sessionId,
            @Nonnull SessionBinding session,
            @Nonnull CommandUiActionRequest request,
            @Nonnull ContributorBinding binding,
            long currentGeneration
    ) {
        CommandUiActionHandle handle = request.handle();
        if (!sessionId.equals(binding.sessionId)) {
            return completed(CommandUiActionResult.denied(
                    "command UI action handle belongs to another session"));
        }
        if (binding.actionGeneration != currentGeneration) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI action generation is stale"));
        }
        if (binding.providerGeneration != session.providerGeneration) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI provider generation is stale"));
        }
        if (binding.confirmationToken
                && binding.expiresAtNanos < nanoTime.getAsLong()) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "confirmation handle expired"));
        }
        if (binding.confirmationToken && request.input() != null) {
            return completed(CommandUiActionResult.denied(
                    "confirmation handle does not accept new input"));
        }
        CommandUiValue supplied = binding.confirmationToken
                ? binding.confirmationInput : request.input();
        ContributorInputValidation input = validateContributorInput(binding,
                supplied);
        if (!input.valid) {
            return completed(CommandUiActionResult.denied(input.message));
        }
        if (!isContributorActive(binding)) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI contributor generation is stale"));
        }
        CommandUiContributorActionContext context = binding.context(
                input.value, binding.confirmationToken);
        if (!allows(binding, context)) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.denied(
                    "current contributor authority denied the action"));
        }
        if (!binding.consumed.compareAndSet(false, true)) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI action handle was already used"));
        }
        if (binding.confirmationToken
                && !binding.confirmationFamilyConsumed.compareAndSet(false, true)) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI confirmation was already used"));
        }

        if (binding.requiresConfirmation && !binding.confirmationToken) {
            binding.consumed.set(false);
            CommandUiActionHandle confirmation = issueContributorConfirmation(
                    binding, input.value, nanoTime.getAsLong(), handle.token());
            return completed(CommandUiActionResult.confirmationRequired(
                    confirmation, "confirmation required", null));
        }

        // This is deliberately adjacent to handler invocation. A registration
        // can disappear after the first validation and before this point.
        if (!isContributorActive(binding)) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.stale(
                    "command UI contributor generation is stale"));
        }
        CommandUiContributorActionContext executionContext = binding.context(
                input.value, binding.confirmationToken);
        if (!allows(binding, executionContext)) {
            contributorBindings.remove(handle.token(), binding);
            return completed(CommandUiActionResult.denied(
                    "current contributor authority denied the action"));
        }
        return executeContributor(handle.token(), binding, executionContext);
    }

    @Nonnull
    private CommandUiActionHandle issueContributorConfirmation(
            @Nonnull ContributorBinding binding,
            @Nullable CommandUiValue input,
            long nowNanos,
            @Nonnull String initiatingToken
    ) {
        CommandUiActionHandle confirmation = new CommandUiActionHandle(
                UUID.randomUUID().toString());
        contributorBindings.put(confirmation.token(), new ContributorBinding(
                binding.sessionId, binding.actionBinding, binding.actionGeneration,
                binding.providerGeneration, binding.identity,
                binding.generationCheck, binding.authority, false,
                true, nowNanos + CONFIRMATION_LIFETIME.toNanos(), initiatingToken,
                input,
                binding.confirmationFamilyConsumed, new AtomicBoolean()));
        return confirmation;
    }

    @Nonnull
    private CompletionStage<CommandUiActionResult> executeContributor(
            @Nonnull String token,
            @Nonnull ContributorBinding binding,
            @Nonnull CommandUiContributorActionContext context
    ) {
        retireContributorConfirmationFamily(token, binding);
        try {
            CompletionStage<CommandUiActionResult> stage =
                    binding.actionBinding.handler().handle(context);
            if (stage == null) {
                contributorBindings.remove(token, binding);
                return completed(CommandUiActionResult.failed(
                        "action returned no result"));
            }
            CompletableFuture<CommandUiActionResult> future = stage.toCompletableFuture();
            if (!future.isDone()) {
                contributorBindings.remove(token, binding);
                return completed(CommandUiActionResult.failed(
                        "contributor action must complete on the world thread"));
            }
            CommandUiActionResult result;
            try {
                result = future.join();
            } catch (RuntimeException failure) {
                result = null;
            }
            contributorBindings.remove(token, binding);
            return result == null
                    ? completed(CommandUiActionResult.failed(
                            "action returned no result"))
                    : completed(result);
        } catch (RuntimeException | LinkageError failure) {
            contributorBindings.remove(token, binding);
            return completed(CommandUiActionResult.failed(
                    "action execution failed"));
        }
    }

    private boolean isContributorActive(@Nonnull ContributorBinding binding) {
        try {
            return binding.generationCheck.active();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private boolean allows(
            @Nonnull ContributorBinding binding,
            @Nonnull CommandUiContributorActionContext context
    ) {
        try {
            return binding.authority.allows(context);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nonnull
    private static ContributorInputValidation validateContributorInput(
            @Nonnull ContributorBinding binding,
            @Nullable CommandUiValue input
    ) {
        CommandUiContributorAction.InputPolicy policy =
                binding.actionBinding.action().inputPolicy();
        if (policy == com.alechilles.alecstamework.api.commandui
                .CommandUiContributorAction.InputPolicy.NONE && input != null) {
            return ContributorInputValidation.invalid(
                    "command UI contributor action does not accept input");
        }
        if (policy == com.alechilles.alecstamework.api.commandui
                .CommandUiContributorAction.InputPolicy.REQUIRED && input == null) {
            return ContributorInputValidation.invalid(
                    "command UI contributor action requires input");
        }
        CommandUiValueBounds.Validation bounds =
                CommandUiValueBounds.validate(input);
        return bounds.valid()
                ? ContributorInputValidation.valid(input)
                : ContributorInputValidation.invalid(bounds.message());
    }

    /** Retires handles that cannot be valid after an authority generation change. */
    void advanceGeneration(@Nonnull UUID sessionId, long generation) {
        if (generation < 0L) {
            throw new IllegalArgumentException("Action generation cannot be negative.");
        }
        bindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().scope == Scope.MAIN
                        && entry.getValue().generation < generation);
        contributorBindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().actionGeneration < generation);
    }

    /** Starts a managed flow and invalidates handles from its prior view. */
    long beginManagedFlow(@Nonnull UUID sessionId) {
        SessionBinding session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Command UI session is not active.");
        }
        long generation = session.managedGeneration.incrementAndGet();
        bindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().scope == Scope.MANAGED
                        && entry.getValue().generation < generation);
        return generation;
    }

    /** Invalidates all handles for one session and rejects later invocations. */
    void closeSession(@Nonnull UUID sessionId) {
        activeSessions.remove(sessionId);
        bindings.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().sessionId));
        contributorBindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId));
    }

    /** Invalidates every issued handle. Used by runtime shutdown. */
    void close() {
        activeSessions.clear();
        bindings.clear();
        contributorBindings.clear();
    }

    int activeHandleCount() {
        return bindings.size() + contributorBindings.size();
    }

    boolean sessionActive(@Nonnull UUID sessionId) {
        return activeSessions.containsKey(sessionId);
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
            Scope scope,
            long generation,
            long providerGeneration,
            AuthorityCheck authority,
            RequestActionExecutor executor,
            InputPolicy inputPolicy,
            int maximumInputLength,
            boolean confirmationRequired,
            boolean confirmationToken,
            long expiresAtNanos,
            @Nullable String initiatingToken,
            AtomicBoolean confirmationFamilyConsumed
    ) {
        CommandUiActionHandle handle = new CommandUiActionHandle(
                UUID.randomUUID().toString());
        bindings.put(handle.token(), new Binding(
                sessionId, route, action, scope, generation, providerGeneration,
                authority, executor, inputPolicy, maximumInputLength,
                confirmationRequired,
                confirmationToken, expiresAtNanos, initiatingToken,
                confirmationFamilyConsumed, new AtomicBoolean()));
        return handle;
    }

    private void retireConfirmationFamily(String token, Binding binding) {
        String initiatingToken = binding.initiatingToken;
        if (!binding.confirmationToken || initiatingToken == null) return;
        bindings.remove(initiatingToken);
        bindings.entrySet().removeIf(entry -> !token.equals(entry.getKey())
                && initiatingToken.equals(entry.getValue().initiatingToken));
    }

    private void retireContributorConfirmationFamily(
            String token,
            ContributorBinding binding
    ) {
        String initiatingToken = binding.initiatingToken;
        if (!binding.confirmationToken || initiatingToken == null) return;
        contributorBindings.remove(initiatingToken);
        contributorBindings.entrySet().removeIf(entry -> !token.equals(entry.getKey())
                && initiatingToken.equals(entry.getValue().initiatingToken));
    }

    private void retireOlderGeneration(UUID sessionId, long generation) {
        bindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().scope == Scope.MAIN
                        && entry.getValue().generation < generation);
        contributorBindings.entrySet().removeIf(entry ->
                sessionId.equals(entry.getValue().sessionId)
                        && entry.getValue().actionGeneration < generation);
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

    private static void validateInputPolicy(
            @Nonnull InputPolicy inputPolicy,
            int maximumInputLength
    ) {
        if (inputPolicy == InputPolicy.NONE && maximumInputLength != 0) {
            throw new IllegalArgumentException(
                    "Handle-only actions must use a zero input limit.");
        }
        if (inputPolicy != InputPolicy.NONE && maximumInputLength <= 0) {
            throw new IllegalArgumentException(
                    "Text actions must use a positive input limit.");
        }
    }

    @Nonnull
    private static InputValidation validateInput(
            @Nonnull Binding binding,
            @Nullable CommandUiValue supplied
    ) {
        if (binding.inputPolicy == InputPolicy.NONE) {
            return supplied == null
                    ? InputValidation.valid(null)
                    : InputValidation.invalid(
                            "command UI action does not accept input");
        }
        if (supplied != null && supplied.type() != CommandUiValue.Type.STRING) {
            return InputValidation.invalid(
                    "command UI action accepts string input only");
        }
        String normalized = supplied == null
                ? null : supplied.stringValue().trim();
        if (binding.inputPolicy == InputPolicy.REQUIRED_TEXT
                && (normalized == null || normalized.isEmpty())) {
            return InputValidation.invalid(
                    "command UI action requires text input");
        }
        if (normalized != null
                && normalized.length() > binding.maximumInputLength) {
            return InputValidation.invalid(
                    "command UI action text input is too long");
        }
        return InputValidation.valid(normalized);
    }

    private record Binding(
            UUID sessionId,
            Route route,
            CommandUiAction action,
            Scope scope,
            long generation,
            long providerGeneration,
            AuthorityCheck authority,
            RequestActionExecutor executor,
            InputPolicy inputPolicy,
            int maximumInputLength,
            boolean requiresConfirmation,
            boolean confirmationToken,
            long expiresAtNanos,
            @Nullable String initiatingToken,
            AtomicBoolean confirmationFamilyConsumed,
            AtomicBoolean consumed
    ) {
        private Binding(
                UUID sessionId,
                Route route,
                CommandUiAction action,
                Scope scope,
                long generation,
                long providerGeneration,
                AuthorityCheck authority,
                RequestActionExecutor executor,
                InputPolicy inputPolicy,
                int maximumInputLength,
                boolean requiresConfirmation,
                boolean confirmationToken,
                long expiresAtNanos,
                @Nullable String initiatingToken
        ) {
            this(sessionId, route, action, scope, generation,
                    providerGeneration, authority, executor, inputPolicy,
                    maximumInputLength,
                    requiresConfirmation, confirmationToken, expiresAtNanos,
                    initiatingToken, new AtomicBoolean(), new AtomicBoolean());
        }
    }

    private record ContributorBinding(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiContributorActionBinding actionBinding,
            long actionGeneration,
            long providerGeneration,
            @Nonnull ContributorIdentity identity,
            @Nonnull ContributorGenerationCheck generationCheck,
            @Nonnull ContributorAuthorityCheck authority,
            boolean requiresConfirmation,
            boolean confirmationToken,
            long expiresAtNanos,
            @Nullable String initiatingToken,
            @Nullable CommandUiValue confirmationInput,
            @Nonnull AtomicBoolean confirmationFamilyConsumed,
            @Nonnull AtomicBoolean consumed
    ) {
        private ContributorBinding {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(actionBinding, "actionBinding");
            if (actionGeneration < 0L) {
                throw new IllegalArgumentException(
                        "Action generation cannot be negative.");
            }
            if (providerGeneration < 0L) {
                throw new IllegalArgumentException(
                        "Provider generation cannot be negative.");
            }
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(generationCheck, "generationCheck");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(confirmationFamilyConsumed,
                    "confirmationFamilyConsumed");
            Objects.requireNonNull(consumed, "consumed");
        }

        @Nonnull
        CommandUiContributorActionContext context(
                @Nullable CommandUiValue input,
                boolean confirmed
        ) {
            boolean row = actionBinding.scope()
                    == CommandUiContributorAction.Scope.ROW;
            return new CommandUiContributorActionContext(
                    sessionId, identity.playerUuid(), identity.configId(),
                    row ? actionBinding.rowId() : null,
                    row ? identity.companionId() : null,
                    row ? identity.profileId() : null,
                    input, confirmed);
        }
    }

    private record SessionBinding(
            long providerGeneration,
            AtomicLong managedGeneration
    ) {
        private SessionBinding(long providerGeneration) {
            this(providerGeneration, new AtomicLong());
        }
    }

    private record InputValidation(
            boolean valid,
            @Nullable String value,
            String message
    ) {
        private static InputValidation valid(@Nullable String value) {
            return new InputValidation(true, value, "");
        }

        private static InputValidation invalid(String message) {
            return new InputValidation(false, null, message);
        }
    }

    private record ContributorInputValidation(
            boolean valid,
            @Nullable CommandUiValue value,
            @Nonnull String message
    ) {
        private static ContributorInputValidation valid(
                @Nullable CommandUiValue value
        ) {
            return new ContributorInputValidation(true, value, "");
        }

        private static ContributorInputValidation invalid(String message) {
            return new ContributorInputValidation(false, null, message);
        }
    }
}
