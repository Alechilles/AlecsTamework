package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiCustomFlowView;
import com.alechilles.alecstamework.api.commandui.CommandUiFlowOperation;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns one contributor custom flow for a command UI session.
 *
 * <p>The manager keeps the flow envelope detached and obtains every executable
 * action from the current server binding state. A custom flow cannot carry a
 * handler or make a contributor-supplied handle executable.</p>
 */
final class CommandUiContributorFlowManager
        implements CommandUiActionGateway.ActionResultProcessor, AutoCloseable {
    private final CommandUiSessionImpl session;
    private final CommandUiSessionFactory.ContributorBindingState bindings;
    private final CommandUiContributorRegistry registry;
    private final Object lock = new Object();
    @Nullable
    private ActiveFlow active;
    @Nullable
    private AutoCloseable unregisterSubscription;
    private boolean closed;

    CommandUiContributorFlowManager(
            @Nonnull CommandUiSessionImpl session,
            @Nonnull CommandUiSessionFactory.ContributorBindingState bindings,
            @Nonnull CommandUiContributorRegistry registry
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Applies a custom-flow operation after its contributor handler returns. */
    @Nonnull
    @Override
    public CommandUiActionResult process(
            @Nonnull CommandUiActionResult result,
            @Nonnull CommandUiActionGateway.ContributorResultSource source
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(source, "source");
        synchronized (lock) {
            if (closed) return CommandUiActionResult.stale(
                    "command UI custom flow is closed");
            if (source.managedAction() && sourceMatchesActive(source)
                    && result.status() == CommandUiActionStatus.DENIED) {
                closeLocked();
                return result;
            }
            CommandUiFlowOperation operation = result.flowOperation();
            if (operation == null) {
                if (source.managedAction() && sourceMatchesActive(source)
                        && result.refreshSnapshot()) closeLocked();
                return result;
            }
            if (operation == CommandUiFlowOperation.CLOSE) {
                if (!source.managedAction() || !sourceMatchesActive(source)) {
                    return conflict(
                            "custom flow close action does not own the active flow");
                }
                closeLocked();
                return result;
            }
            if (!(result.flowView() instanceof CommandUiCustomFlowView flow)) {
                return conflict(
                        "contributor flow operations require a custom flow view");
            }
            return switch (operation) {
                case OPEN -> openLocked(result, flow, source);
                case REPLACE -> replaceLocked(result, flow, source);
                case UPDATE -> updateLocked(result, flow, source);
                case CLOSE -> result;
            };
        }
    }

    /** Authority loss on a managed action closes the flow immediately. */
    @Override
    public void managedAuthorityLost() {
        synchronized (lock) {
            if (!closed) closeLocked();
        }
    }

    /** Returns whether this session currently owns a custom flow. */
    boolean hasActiveFlow() {
        synchronized (lock) {
            return active != null;
        }
    }

    /** Returns the current bound custom flow for focused lifecycle tests. */
    @Nullable
    CommandUiCustomFlowView activeFlow() {
        synchronized (lock) {
            return active == null ? null : active.view;
        }
    }

    /** Releases custom ownership before a built-in managed flow starts. */
    void replacedByBuiltIn() {
        synchronized (lock) {
            closeSubscription();
            active = null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            closeLocked();
        }
    }

    @Nonnull
    private CommandUiActionResult openLocked(
            @Nonnull CommandUiActionResult result,
            @Nonnull CommandUiCustomFlowView flow,
            @Nonnull CommandUiActionGateway.ContributorResultSource source
    ) {
        if (active != null) {
            return conflict("a custom command UI flow is already active");
        }
        if (source.managedAction() || !sourceMatchesFlow(source, flow)) {
            return conflict(
                    "custom flow open action does not own the requested flow");
        }
        if (!validOwner(flow)) return staleOwner();
        try {
            session.beginContributorFlow(this);
            CommandUiSessionFactory.ContributorBindingState.ManagedFlowActions actions =
                    bind(flow, Map.of());
            setSubscription(flow);
            CommandUiCustomFlowView bound = copy(flow, actions.views());
            active = new ActiveFlow(bound, actions.handles());
            return copyResult(result, bound, CommandUiFlowOperation.OPEN);
        } catch (RuntimeException | LinkageError failure) {
            session.closeContributorFlow(this);
            closeSubscription();
            return CommandUiActionResult.failed(
                    "command UI custom flow could not be opened");
        }
    }

    @Nonnull
    private CommandUiActionResult replaceLocked(
            @Nonnull CommandUiActionResult result,
            @Nonnull CommandUiCustomFlowView flow,
            @Nonnull CommandUiActionGateway.ContributorResultSource source
    ) {
        if (!source.managedAction() || !sourceMatchesActive(source)
                || !matchesActive(flow)) {
            return CommandUiActionResult.stale(
                    "command UI custom flow instance is stale");
        }
        if (flow.revision() <= active.view.revision()
                || flow.actionGeneration() < active.view.actionGeneration()) {
            return CommandUiActionResult.stale(
                    "command UI custom flow revision is stale");
        }
        return replaceBoundFlow(result, flow);
    }

    @Nonnull
    private CommandUiActionResult updateLocked(
            @Nonnull CommandUiActionResult result,
            @Nonnull CommandUiCustomFlowView flow,
            @Nonnull CommandUiActionGateway.ContributorResultSource source
    ) {
        if (!source.managedAction() || !sourceMatchesActive(source)
                || !matchesActive(flow)) {
            return CommandUiActionResult.stale(
                    "command UI custom flow instance is stale");
        }
        if (flow.revision() < active.view.revision()
                || flow.actionGeneration() < active.view.actionGeneration()) {
            return CommandUiActionResult.stale(
                    "command UI custom flow revision is stale");
        }
        Set<String> requestedIds = flow.actions().keySet();
        boolean actionIdsChanged = !requestedIds.equals(
                active.view.actions().keySet());
        if (actionIdsChanged
                && flow.revision() == active.view.revision()
                && flow.actionGeneration()
                == active.view.actionGeneration()) {
            return conflict("custom flow action surface changed without a new generation");
        }
        boolean surfaceChanged = source.confirmationToken()
                || actionIdsChanged
                || flow.revision() != active.view.revision()
                || flow.actionGeneration() != active.view.actionGeneration();
        if (!surfaceChanged) {
            CommandUiSessionFactory.ContributorBindingState.ManagedFlowActions actions =
                    bind(flow, active.handles);
            if (actions.handles().keySet().equals(active.handles.keySet())) {
                CommandUiCustomFlowView bound = copy(flow, actions.views());
                active = new ActiveFlow(bound, actions.handles());
                return copyResult(result, bound, CommandUiFlowOperation.UPDATE);
            }
        }
        return replaceBoundFlow(result, flow);
    }

    @Nonnull
    private CommandUiActionResult replaceBoundFlow(
            @Nonnull CommandUiActionResult result,
            @Nonnull CommandUiCustomFlowView flow
    ) {
        if (!validOwner(flow)) return staleOwner();
        try {
            session.beginContributorFlow(this);
            CommandUiSessionFactory.ContributorBindingState.ManagedFlowActions actions =
                    bind(flow, Map.of());
            CommandUiCustomFlowView bound = copy(flow, actions.views());
            active = new ActiveFlow(bound, actions.handles());
            return copyResult(result, bound, result.flowOperation());
        } catch (RuntimeException | LinkageError failure) {
            closeLocked();
            return CommandUiActionResult.failed(
                    "command UI custom flow could not be updated");
        }
    }

    @Nonnull
    private CommandUiSessionFactory.ContributorBindingState.ManagedFlowActions bind(
            @Nonnull CommandUiCustomFlowView flow,
            @Nonnull Map<String, CommandUiActionHandle> existing
    ) {
        return bindings.bindManagedFlowActions(flow.ownerContributorId(),
                flow.ownerGeneration(), flow.actions().keySet(), existing);
    }

    private boolean matchesActive(@Nonnull CommandUiCustomFlowView flow) {
        return active != null
                && active.view.flowInstanceId().equals(flow.flowInstanceId())
                && active.view.flowType().equals(flow.flowType())
                && active.view.ownerContributorId().equals(flow.ownerContributorId())
                && active.view.ownerGeneration() == flow.ownerGeneration()
                && validOwner(flow);
    }

    private boolean sourceMatchesActive(
            @Nonnull CommandUiActionGateway.ContributorResultSource source
    ) {
        return active != null
                && active.view.ownerContributorId().equals(
                        source.contributorId())
                && active.view.ownerGeneration()
                == source.contributorGeneration();
    }

    private static boolean sourceMatchesFlow(
            @Nonnull CommandUiActionGateway.ContributorResultSource source,
            @Nonnull CommandUiCustomFlowView flow
    ) {
        return flow.ownerContributorId().equals(source.contributorId())
                && flow.ownerGeneration() == source.contributorGeneration();
    }

    private boolean validOwner(@Nonnull CommandUiCustomFlowView flow) {
        return registry.isActive(flow.ownerContributorId(), flow.ownerGeneration())
                && bindings.hasContributor(flow.ownerContributorId(),
                flow.ownerGeneration());
    }

    @Nonnull
    private CommandUiActionResult staleOwner() {
        return CommandUiActionResult.stale(
                "command UI custom flow contributor generation is stale");
    }

    @Nonnull
    private static CommandUiActionResult conflict(@Nonnull String message) {
        return CommandUiActionResult.conflict(message);
    }

    private void setSubscription(@Nonnull CommandUiCustomFlowView flow) {
        closeSubscription();
        CommandUiContributorRegistry.ExactSubscription subscription =
                registry.subscribeExactUnregister(flow.ownerContributorId(),
                        flow.ownerGeneration(),
                        (id, generation) -> {
                            if (flow.ownerContributorId().equals(id)
                                    && flow.ownerGeneration() == generation) {
                                synchronized (lock) {
                                    if (!closed) closeLocked();
                                }
                            }
                        });
        if (!subscription.active()) {
            throw new IllegalStateException(
                    "custom flow contributor registration is no longer active");
        }
        unregisterSubscription = subscription.handle();
    }

    private void closeLocked() {
        closeSubscription();
        active = null;
        session.closeContributorFlow(this);
    }

    private void closeSubscription() {
        AutoCloseable subscription = unregisterSubscription;
        unregisterSubscription = null;
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception | LinkageError ignored) {
            // Flow handles are still retired when a lifecycle listener fails.
        }
    }

    @Nonnull
    private static CommandUiCustomFlowView copy(
            @Nonnull CommandUiCustomFlowView source,
            @Nonnull Map<String, com.alechilles.alecstamework.api.commandui.CommandUiActionView> actions
    ) {
        return new CommandUiCustomFlowView(source.flowInstanceId(),
                source.flowType(), source.ownerContributorId(),
                source.ownerGeneration(), source.revision(),
                source.actionGeneration(), source.data(), actions);
    }

    @Nonnull
    private static CommandUiActionResult copyResult(
            @Nonnull CommandUiActionResult source,
            @Nonnull CommandUiCustomFlowView view,
            @Nonnull CommandUiFlowOperation operation
    ) {
        return new CommandUiActionResult(source.status(), source.message(),
                source.confirmationHandle(), source.confirmationView(),
                source.metadata(), view, operation, source.refreshSnapshot());
    }

    private record ActiveFlow(
            @Nonnull CommandUiCustomFlowView view,
            @Nonnull Map<String, CommandUiActionHandle> handles
    ) {
        ActiveFlow {
            handles = Map.copyOf(new LinkedHashMap<>(handles));
        }
    }
}
