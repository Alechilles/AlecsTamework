package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Internal Task 3 seam for constructing and binding one command UI session. */
final class CommandUiSessionFactory {
    private final CommandUiActionGateway gateway;
    private final CommandSelectionPageService actions;

    CommandUiSessionFactory(
            @Nonnull CommandUiActionGateway gateway,
            @Nonnull CommandSelectionPageService actions
    ) {
        this.gateway = gateway;
        this.actions = actions;
    }

    @Nonnull
    CreatedSession createGeneric(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> visibleActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                providerGeneration, CommandUiSessionImpl.Mode.GENERIC,
                refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandSelectionPageService.GenericUiActionBinding action : visibleActions) {
            handles.add(actions.bindGenericUiAction(session, action));
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    @Nonnull
    CreatedSession createBonded(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> visibleActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                providerGeneration, CommandUiSessionImpl.Mode.BONDED,
                refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandSelectionPageService.BondedUiActionBinding action : visibleActions) {
            CommandUiActionHandle handle = actions.bindBondedUiAction(session, action);
            if (handle != null) handles.add(handle);
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    /** Creates a menu that exposes separately authorized generic and bonded actions. */
    @Nonnull
    CreatedSession createMixed(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> genericActions,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> bondedActions
    ) {
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, snapshot, gateway, dispatcher,
                providerGeneration, CommandUiSessionImpl.Mode.MIXED,
                refresh, close, submitter);
        java.util.ArrayList<CommandUiActionHandle> handles = new java.util.ArrayList<>();
        for (CommandSelectionPageService.GenericUiActionBinding action : genericActions) {
            handles.add(actions.bindGenericUiAction(session, action));
        }
        for (CommandSelectionPageService.BondedUiActionBinding action : bondedActions) {
            CommandUiActionHandle handle = actions.bindBondedUiAction(session, action);
            if (handle != null) handles.add(handle);
        }
        return new CreatedSession(session, List.copyOf(handles));
    }

    /** Creates a mixed session and binds the selected contributor actions. */
    @Nonnull
    CreatedSession createMixed(
            @Nonnull UUID sessionId,
            @Nonnull CommandUiSnapshot snapshot,
            long providerGeneration,
            @Nonnull CommandUiWorldDispatcher dispatcher,
            @Nullable Runnable refresh,
            @Nullable Consumer<CommandUiCloseReason> close,
            @Nullable CommandUiSessionImpl.PartialUpdateSubmitter submitter,
            @Nonnull List<CommandSelectionPageService.GenericUiActionBinding> genericActions,
            @Nonnull List<CommandSelectionPageService.BondedUiActionBinding> bondedActions,
            @Nonnull UUID playerUuid,
            @Nullable String configId,
            @Nonnull List<CommandUiContributorActionBinding> contributorBindings,
            @Nonnull CommandUiContributorRegistry contributorRegistry,
            @Nonnull BooleanSupplier rendererGenerationCheck
    ) {
        CreatedSession created = createMixed(sessionId, snapshot,
                providerGeneration, dispatcher, refresh, close, submitter,
                genericActions, bondedActions);
        if (contributorBindings.isEmpty()) return created;
        ContributorBindingState state = new ContributorBindingState(
                created.session(), playerUuid, configId, contributorRegistry,
                contributorBindings, rendererGenerationCheck);
        created.session().installContributorFlowManager(
                new CommandUiContributorFlowManager(
                        created.session(), state, contributorRegistry));
        return new CreatedSession(created.session(), created.handles(), state);
    }

    record CreatedSession(
            @Nonnull CommandUiSessionImpl session,
            @Nonnull List<CommandUiActionHandle> handles,
            @Nullable ContributorBindingState contributorState
    ) {
        CreatedSession(
                @Nonnull CommandUiSessionImpl session,
                @Nonnull List<CommandUiActionHandle> handles
        ) {
            this(session, handles, null);
        }

        CreatedSession {
            handles = List.copyOf(handles);
        }
    }

    /** Owns contributor handles and preserves them across presentation refreshes. */
    static final class ContributorBindingState {
        private final CommandUiSessionImpl session;
        private final UUID playerUuid;
        private final String configId;
        private final CommandUiContributorRegistry registry;
        private final BooleanSupplier rendererGenerationCheck;
        private List<CommandUiActionCatalog.ContributorActionHandle> handles;
        private List<CommandUiContributorActionBinding> bindings;
        private long actionGeneration;

        private ContributorBindingState(
                @Nonnull CommandUiSessionImpl session,
                @Nonnull UUID playerUuid,
                @Nullable String configId,
                @Nonnull CommandUiContributorRegistry registry,
                @Nonnull List<CommandUiContributorActionBinding> bindings,
                @Nonnull BooleanSupplier rendererGenerationCheck
        ) {
            this.session = session;
            this.playerUuid = playerUuid;
            this.configId = configId;
            this.registry = registry;
            this.rendererGenerationCheck = java.util.Objects.requireNonNull(
                    rendererGenerationCheck, "rendererGenerationCheck");
            this.bindings = List.copyOf(bindings);
            this.actionGeneration = session.snapshot().actionGeneration();
            this.handles = issue(session.snapshot(), this.bindings,
                    actionGeneration);
        }

        @Nonnull
        synchronized CommandUiSnapshot attachInitial(
                @Nonnull CommandUiSnapshot snapshot
        ) {
            return CommandUiActionCatalog.attachContributorActions(snapshot,
                    handles);
        }

        @Nonnull
        synchronized CommandUiSnapshot reconcile(
                @Nonnull CommandUiSnapshot snapshot,
                @Nonnull CommandUiSnapshot previous,
                @Nonnull List<CommandUiContributorActionBinding> currentBindings
        ) {
            List<CommandUiContributorActionBinding> current =
                    List.copyOf(currentBindings);
            boolean sameSurface = CommandUiActionCatalog.contributorActionsMatch(
                    handles, current)
                    && renderableMatches(previous, snapshot, current);
            boolean reusable = true;
            if (sameSurface
                    && snapshot.actionGeneration() == actionGeneration) {
                Map<BindingKey, CommandUiActionHandle> existing = new LinkedHashMap<>();
                for (CommandUiActionCatalog.ContributorActionHandle entry : handles) {
                    existing.put(BindingKey.of(entry.binding()), entry.handle());
                }
                List<CommandUiActionCatalog.ContributorActionHandle> refreshed =
                        new ArrayList<>(current.size());
                for (CommandUiContributorActionBinding binding : current) {
                    CommandUiActionHandle handle = existing.get(
                            BindingKey.of(binding));
                    if (handle != null && !session.refreshContributor(handle,
                            binding, rendererGenerationCheck::getAsBoolean,
                            generationCheck(binding))) {
                        reusable = false;
                        break;
                    }
                    refreshed.add(
                            new CommandUiActionCatalog.ContributorActionHandle(
                                    binding, handle));
                }
                if (reusable) {
                    handles = List.copyOf(refreshed);
                    bindings = current;
                    return CommandUiActionCatalog.attachContributorActions(
                            snapshot, handles);
                }
            }
            long generation = snapshot.actionGeneration();
            if ((!sameSurface || !reusable) && generation <= actionGeneration) {
                generation = Math.max(previous.actionGeneration(),
                        actionGeneration) + 1L;
            } else {
                generation = Math.max(generation, actionGeneration);
            }
            handles = issue(snapshot, current, generation);
            bindings = current;
            actionGeneration = generation;
            return CommandUiActionCatalog.attachContributorActions(
                    snapshot.withActionGeneration(generation), handles);
        }

        /**
         * Binds the exact current FLOW definitions to managed-flow handles.
         * Caller-supplied flow handles are never consulted.
         */
        @Nonnull
        synchronized ManagedFlowActions bindManagedFlowActions(
                @Nonnull CommandUiContributorId contributorId,
                long contributorGeneration,
                @Nonnull Set<String> effectiveIds,
                @Nonnull Map<String, CommandUiActionHandle> existing
        ) {
            Map<String, CommandUiActionHandle> issued = new LinkedHashMap<>();
            Map<String, CommandUiActionView> views = new LinkedHashMap<>();
            for (CommandUiContributorActionBinding binding : bindings) {
                if (binding.scope() != CommandUiContributorAction.Scope.FLOW
                        || !contributorId.equals(binding.contributorId())
                        || contributorGeneration != binding.contributorGeneration()
                        || !effectiveIds.contains(binding.effectiveId())) {
                    continue;
                }
                CommandUiActionHandle handle = existing.get(binding.effectiveId());
                if (handle == null || !session.refreshManagedContributor(
                        handle, binding, rendererGenerationCheck::getAsBoolean,
                        generationCheck(binding))) {
                    handle = session.issueManagedContributor(binding,
                            new CommandUiActionGateway.ContributorIdentity(
                                    playerUuid, configId, null, null, null),
                            rendererGenerationCheck::getAsBoolean,
                            generationCheck(binding));
                }
                CommandUiActionView view = binding.view(handle);
                if (view == null) continue;
                if (handle != null) {
                    issued.put(binding.effectiveId(), handle);
                }
                views.put(binding.effectiveId(), view);
            }
            if (!views.keySet().equals(effectiveIds)) {
                throw new IllegalArgumentException(
                        "Custom flow requested an unknown or hidden action.");
            }
            return new ManagedFlowActions(issued, views);
        }

        /** Returns whether the exact contributor generation is in this state. */
        synchronized boolean hasContributor(
                @Nonnull CommandUiContributorId contributorId,
                long contributorGeneration
        ) {
            return bindings.stream().anyMatch(binding ->
                    contributorId.equals(binding.contributorId())
                            && contributorGeneration
                            == binding.contributorGeneration());
        }

        private List<CommandUiActionCatalog.ContributorActionHandle> issue(
                @Nonnull CommandUiSnapshot snapshot,
                @Nonnull List<CommandUiContributorActionBinding> source,
                long actionGeneration
        ) {
            List<CommandUiActionCatalog.ContributorActionHandle> issued =
                    new ArrayList<>(source.size());
            for (CommandUiContributorActionBinding binding : source) {
                CommandUiCompanionRow row = snapshot.companionRow(binding.rowId());
                boolean rowVisible = binding.scope()
                        != CommandUiContributorAction.Scope.ROW || row != null;
                CommandUiActionHandle handle = null;
                if (rowVisible && binding.scope()
                        != CommandUiContributorAction.Scope.FLOW) {
                    CommandUiActionGateway.ContributorIdentity identity =
                            new CommandUiActionGateway.ContributorIdentity(
                                    playerUuid, configId,
                                    binding.rowId(),
                                    row == null ? null : row.companionUuid(),
                                    row == null ? null : row.profileId());
                    handle = session.issueContributor(binding, actionGeneration,
                            identity, rendererGenerationCheck::getAsBoolean,
                            generationCheck(binding));
                }
                issued.add(new CommandUiActionCatalog.ContributorActionHandle(
                        binding, handle));
            }
            return List.copyOf(issued);
        }

        private CommandUiActionGateway.ContributorGenerationCheck
        generationCheck(CommandUiContributorActionBinding binding) {
            return () -> registry.isActive(binding.contributorId(),
                    binding.contributorGeneration());
        }

        private static boolean renderableMatches(
                @Nonnull CommandUiSnapshot oldSnapshot,
                @Nonnull CommandUiSnapshot newSnapshot,
                @Nonnull List<CommandUiContributorActionBinding> newBindings
        ) {
            for (CommandUiContributorActionBinding binding : newBindings) {
                if (binding.scope() != CommandUiContributorAction.Scope.ROW) {
                    continue;
                }
                CommandUiCompanionRow oldRow = oldSnapshot.companionRow(
                        binding.rowId());
                CommandUiCompanionRow newRow = newSnapshot.companionRow(
                        binding.rowId());
                if (oldRow == null || newRow == null) {
                    if (oldRow != newRow) return false;
                    continue;
                }
                if (!java.util.Objects.equals(oldRow.companionUuid(),
                        newRow.companionUuid())
                        || !java.util.Objects.equals(oldRow.profileId(),
                        newRow.profileId())) return false;
            }
            return true;
        }

        private record BindingKey(
                CommandUiContributorId contributorId,
                CommandUiContributorAction.Scope scope,
                UUID rowId,
                String effectiveId
        ) {
            static BindingKey of(CommandUiContributorActionBinding binding) {
                return new BindingKey(binding.contributorId(), binding.scope(),
                        binding.rowId(), binding.effectiveId());
            }
        }

        record ManagedFlowActions(
                @Nonnull Map<String, CommandUiActionHandle> handles,
                @Nonnull Map<String, CommandUiActionView> views
        ) {
            ManagedFlowActions {
                handles = Map.copyOf(handles);
                views = Map.copyOf(views);
            }
        }
    }
}
