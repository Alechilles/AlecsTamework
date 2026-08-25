package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionView;
import com.alechilles.alecstamework.api.commandui.CommandUiCommandOption;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Collects visible Tamework actions and attaches their opaque handles. */
final class CommandUiActionCatalog {
    private final List<Entry> generic = new ArrayList<>();
    private final List<Entry> bonded = new ArrayList<>();
    private final List<ContributorEntry> contributors = new ArrayList<>();

    void addCommand(
            String commandId,
            String label,
            CommandSelectionPageService.GenericUiActionBinding binding
    ) {
        generic.add(new Entry(Scope.COMMAND, commandId, null, label, binding,
                null));
    }

    void addGlobal(
            String key,
            String label,
            CommandSelectionPageService.GenericUiActionBinding binding
    ) {
        generic.add(new Entry(Scope.GLOBAL, key, null, label, binding, null));
    }

    void addPanel(
            String key,
            String label,
            CommandSelectionPageService.GenericUiActionBinding binding
    ) {
        generic.add(new Entry(Scope.PANEL, key, null, label, binding, null));
    }

    void addHotswap(
            String slot,
            String commandId,
            String label,
            CommandSelectionPageService.GenericUiActionBinding binding
    ) {
        generic.add(new Entry(Scope.HOTSWAP, slot + ":" + commandId, null,
                label, binding, null));
    }

    void addRow(
            UUID rowId,
            String key,
            String label,
            CommandSelectionPageService.GenericUiActionBinding binding
    ) {
        generic.add(new Entry(Scope.ROW, key, rowId, label, binding, null));
    }

    void addBondedRow(
            UUID rowId,
            String key,
            String label,
            CommandSelectionPageService.BondedUiActionBinding binding
    ) {
        bonded.add(new Entry(Scope.ROW, key, rowId, label, null, binding));
    }

    /** Adds one page-scoped contributor action when its effective ID is free. */
    CommandUiActionResult addContributorPage(
            @Nonnull CommandUiContributorId contributorId,
            long contributorGeneration,
            @Nonnull CommandUiContributorAction action
    ) {
        return addContributor(CommandUiContributorAction.Scope.PAGE,
                null, contributorId, contributorGeneration, action);
    }

    /** Adds one command-scoped contributor action when its effective ID is free. */
    CommandUiActionResult addContributorCommand(
            @Nonnull CommandUiContributorId contributorId,
            long contributorGeneration,
            @Nonnull CommandUiContributorAction action
    ) {
        return addContributor(CommandUiContributorAction.Scope.COMMAND,
                null, contributorId, contributorGeneration, action);
    }

    /** Adds one row-scoped contributor action when its effective ID is free. */
    CommandUiActionResult addContributorRow(
            @Nonnull UUID rowId,
            @Nonnull CommandUiContributorId contributorId,
            long contributorGeneration,
            @Nonnull CommandUiContributorAction action
    ) {
        return addContributor(CommandUiContributorAction.Scope.ROW,
                Objects.requireNonNull(rowId, "rowId"), contributorId,
                contributorGeneration, action);
    }

    /** Adds one flow-scoped contributor action when its effective ID is free. */
    CommandUiActionResult addContributorFlow(
            @Nonnull CommandUiContributorId contributorId,
            long contributorGeneration,
            @Nonnull CommandUiContributorAction action
    ) {
        return addContributor(CommandUiContributorAction.Scope.FLOW,
                null, contributorId, contributorGeneration, action);
    }

    /** Returns server-owned contributor bindings in deterministic add order. */
    @Nonnull
    List<CommandUiContributorActionBinding> contributorBindings() {
        return contributors.stream().map(ContributorEntry::binding).toList();
    }

    List<CommandSelectionPageService.GenericUiActionBinding> genericBindings() {
        return generic.stream().map(Entry::genericBinding).toList();
    }

    List<CommandSelectionPageService.BondedUiActionBinding> bondedBindings() {
        return bonded.stream().map(Entry::bondedBinding).toList();
    }

    CommandUiSnapshot attach(CommandUiSnapshot base,
                             List<CommandUiActionHandle> handles) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(handles, "handles");
        List<Entry> entries = new ArrayList<>(generic.size() + bonded.size());
        entries.addAll(generic);
        entries.addAll(bonded);
        if (handles.size() != entries.size()) {
            throw new IllegalArgumentException("Action handle count does not match catalog.");
        }
        Map<String, CommandUiActionView> commandActions = new LinkedHashMap<>();
        Map<String, CommandUiActionView> globalActions = new LinkedHashMap<>();
        Map<String, CommandUiActionView> panelActions = new LinkedHashMap<>();
        Map<String, CommandUiActionView> hotswapActions = new LinkedHashMap<>();
        Map<UUID, Map<String, CommandUiActionView>> rowActions =
                new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            CommandUiActionView view = entry.view(handles.get(index));
            switch (entry.scope()) {
                case COMMAND -> commandActions.put(entry.key(), view);
                case GLOBAL -> globalActions.put(entry.key(), view);
                case PANEL -> panelActions.put(entry.key(), view);
                case HOTSWAP -> hotswapActions.put(entry.key(), view);
                case ROW -> rowActions.computeIfAbsent(entry.rowId(),
                        ignored -> new LinkedHashMap<>()).put(entry.key(), view);
            }
        }
        List<CommandUiCommandOption> commands = base.commandOptions().stream()
                .map(option -> new CommandUiCommandOption(
                        option.commandId(), option.label(),
                        option.localizationSource(), option.iconAssetId(),
                        option.radialVisible(), option.selected(),
                        commandActions.get(option.commandId())))
                .toList();
        List<CommandUiCompanionRow> rows = base.companionRows().stream()
                .map(row -> copyRow(row,
                        rowActions.getOrDefault(row.rowId(), Map.of())))
                .toList();
        CommandUiPanelState panel = base.panelState();
        Map<String, List<CommandUiCommandOption>> hotswapChoices =
                new LinkedHashMap<>();
        base.hotswapChoices().forEach((slot, choices) -> hotswapChoices.put(
                slot, choices.stream().map(option -> new CommandUiCommandOption(
                        option.commandId(), option.label(),
                        option.localizationSource(), option.iconAssetId(),
                        option.radialVisible(), option.selected(),
                        hotswapActions.get(slot + ":" + option.commandId())))
                        .toList()));
        CommandUiPanelState attachedPanel = new CommandUiPanelState(
                panel.mode(), panel.autoLinkEnabled(),
                panel.activeHighlightEnabled(), panel.radius(),
                panel.radiusLabel(), panel.sort(), panel.filterMode(),
                panel.filterInput(), panel.emptyStateKey(), panelActions,
                panel.values());
        return new CommandUiSnapshot(
                base.sessionId(), base.presentationRevision(),
                base.actionGeneration(), base.providerId(), base.toolId(),
                base.itemId(), base.configId(), base.rosterMode(),
                base.enabledCapabilities(), base.selectedCommand(), commands,
                rows, attachedPanel, globalActions, commandActions,
                base.hotswapAssignments(), hotswapChoices, base.groups(),
                base.serverTimeMillis(), base.deadlines(), base.emptyStateKey(),
                base.disabledReason());
    }

    /** Returns whether the published action surface still matches this catalog. */
    boolean matchesActions(CommandUiSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<ActionKey, ActionShape> published = new LinkedHashMap<>();
        snapshot.commandActions().forEach((key, view) -> published.put(
                new ActionKey(Scope.COMMAND, key, null), ActionShape.of(view)));
        snapshot.globalActions().forEach((key, view) -> published.put(
                new ActionKey(Scope.GLOBAL, key, null), ActionShape.of(view)));
        snapshot.panelState().actions().forEach((key, view) -> published.put(
                new ActionKey(Scope.PANEL, key, null), ActionShape.of(view)));
        snapshot.hotswapChoices().forEach((slot, choices) -> choices.forEach(option -> {
            if (option.action() != null) {
                published.put(new ActionKey(Scope.HOTSWAP,
                                slot + ":" + option.commandId(), null),
                        ActionShape.of(option.action()));
            }
        }));
        snapshot.companionRows().forEach(row -> row.actions().forEach(
                (key, view) -> published.put(
                        new ActionKey(Scope.ROW, key, row.rowId()),
                        ActionShape.of(view))));

        Map<ActionKey, ActionShape> expected = new LinkedHashMap<>();
        entries().forEach(entry -> expected.put(
                new ActionKey(entry.scope(), entry.key(), entry.rowId()),
                entry.shape()));
        if (!expected.equals(published)) return false;
        Map<UUID, CommandUiCompanionRow> rows = new LinkedHashMap<>();
        snapshot.companionRows().forEach(row -> rows.put(row.rowId(), row));
        for (Entry entry : entries()) {
            if (entry.scope() != Scope.ROW) continue;
            CommandUiCompanionRow row = rows.get(entry.rowId());
            if (row == null) return false;
            if (entry.genericBinding() != null) {
                UUID target = entry.genericBinding().action().targetId();
                if (target != null && !target.equals(row.companionUuid())) {
                    return false;
                }
            } else if (!entry.bondedBinding().profileId()
                    .equals(row.profileId())) {
                return false;
            }
        }
        return true;
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(generic.size() + bonded.size());
        entries.addAll(generic);
        entries.addAll(bonded);
        return entries;
    }

    /** Keeps valid action views while replacing only presentation values. */
    static CommandUiSnapshot retainActions(
            CommandUiSnapshot fresh,
            CommandUiSnapshot previous
    ) {
        Map<String, CommandUiCommandOption> oldCommands = new LinkedHashMap<>();
        previous.commandOptions().forEach(option ->
                oldCommands.put(option.commandId(), option));
        List<CommandUiCommandOption> commands = fresh.commandOptions().stream()
                .map(option -> new CommandUiCommandOption(
                        option.commandId(), option.label(),
                        option.localizationSource(), option.iconAssetId(),
                        option.radialVisible(), option.selected(),
                        oldCommands.containsKey(option.commandId())
                                ? oldCommands.get(option.commandId()).action() : null))
                .toList();
        Map<UUID, CommandUiCompanionRow> oldRows = new LinkedHashMap<>();
        previous.companionRows().forEach(row -> oldRows.put(row.rowId(), row));
        List<CommandUiCompanionRow> rows = fresh.companionRows().stream()
                .map(row -> copyRow(row, oldRows.containsKey(row.rowId())
                        ? oldRows.get(row.rowId()).actions() : Map.of()))
                .toList();
        Map<String, List<CommandUiCommandOption>> hotswaps =
                new LinkedHashMap<>();
        fresh.hotswapChoices().forEach((slot, choices) -> {
            Map<String, CommandUiCommandOption> old = new LinkedHashMap<>();
            previous.hotswapChoices().getOrDefault(slot, List.of())
                    .forEach(option -> old.put(option.commandId(), option));
            hotswaps.put(slot, choices.stream().map(option ->
                    new CommandUiCommandOption(
                            option.commandId(), option.label(),
                            option.localizationSource(), option.iconAssetId(),
                            option.radialVisible(), option.selected(),
                            old.containsKey(option.commandId())
                                    ? old.get(option.commandId()).action() : null))
                    .toList());
        });
        CommandUiPanelState panel = fresh.panelState();
        CommandUiPanelState retainedPanel = new CommandUiPanelState(
                panel.mode(), panel.autoLinkEnabled(), panel.activeHighlightEnabled(),
                panel.radius(), panel.radiusLabel(), panel.sort(), panel.filterMode(),
                panel.filterInput(), panel.emptyStateKey(),
                previous.panelState().actions(), panel.values());
        return new CommandUiSnapshot(
                fresh.sessionId(), fresh.presentationRevision(),
                previous.actionGeneration(), fresh.providerId(), fresh.toolId(),
                fresh.itemId(), fresh.configId(), fresh.rosterMode(),
                fresh.enabledCapabilities(), fresh.selectedCommand(), commands,
                rows, retainedPanel, previous.globalActions(),
                previous.commandActions(), fresh.hotswapAssignments(), hotswaps,
                fresh.groups(), fresh.serverTimeMillis(), fresh.deadlines(),
                fresh.emptyStateKey(), fresh.disabledReason());
    }

    private static CommandUiCompanionRow copyRow(
            CommandUiCompanionRow row,
            Map<String, CommandUiActionView> actions
    ) {
        return new CommandUiCompanionRow(
                row.rowId(), row.companionUuid(), row.profileId(),
                row.displayName(), row.role(), row.species(), row.gender(),
                row.lifecycleStatus(), row.linked(), row.active(),
                row.locationAvailable(), row.currentWorld(),
                row.currentHealth(), row.maxHealth(), row.currentHappiness(),
                row.maxHappiness(), actions, row.presentation());
    }

    private enum Scope { COMMAND, GLOBAL, PANEL, HOTSWAP, ROW }

    private CommandUiActionResult addContributor(
            @Nonnull CommandUiContributorAction.Scope scope,
            @Nullable UUID rowId,
            @Nonnull CommandUiContributorId contributorId,
            long contributorGeneration,
            @Nonnull CommandUiContributorAction action
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(contributorId, "contributorId");
        Objects.requireNonNull(action, "action");
        CommandUiContributorActionBinding binding =
                new CommandUiContributorActionBinding(contributorId,
                        contributorGeneration, action, scope, rowId);
        ContributorKey key = new ContributorKey(scope, binding.effectiveId(),
                scope == CommandUiContributorAction.Scope.ROW ? rowId : null);
        for (ContributorEntry entry : contributors) {
            if (entry.key().equals(key)) {
                return CommandUiActionResult.conflict(
                        "contributor action ID is already bound in this scope");
            }
        }
        contributors.add(new ContributorEntry(key, binding));
        return CommandUiActionResult.applied();
    }

    private record ContributorKey(
            @Nonnull CommandUiContributorAction.Scope scope,
            @Nonnull String effectiveId,
            @Nullable UUID rowId
    ) { }

    private record ContributorEntry(
            @Nonnull ContributorKey key,
            @Nonnull CommandUiContributorActionBinding binding
    ) { }

    private record ActionKey(Scope scope, String key, UUID rowId) { }

    private record ActionShape(String kind, String label,
                               boolean confirmationRequired) {
        static ActionShape of(CommandUiActionView view) {
            return new ActionShape(view.kind(), view.label(),
                    view.confirmationRequired());
        }
    }

    private record Entry(
            Scope scope,
            String key,
            UUID rowId,
            String label,
            CommandSelectionPageService.GenericUiActionBinding genericBinding,
            CommandSelectionPageService.BondedUiActionBinding bondedBinding
    ) {
        Entry {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
            if ((genericBinding == null) == (bondedBinding == null)) {
                throw new IllegalArgumentException("One action binding is required.");
            }
            if (scope == Scope.ROW) Objects.requireNonNull(rowId, "rowId");
        }

        CommandUiActionView view(CommandUiActionHandle handle) {
            CommandUiAction action = genericBinding != null
                    ? genericBinding.action() : bondedBinding.action();
            boolean confirmation = genericBinding != null
                    ? genericBinding.confirmationRequired()
                    : bondedBinding.confirmationRequired();
            return new CommandUiActionView(action.kind(), label, true, null,
                    confirmation, handle);
        }

        ActionShape shape() {
            CommandUiAction action = genericBinding != null
                    ? genericBinding.action() : bondedBinding.action();
            boolean confirmation = genericBinding != null
                    ? genericBinding.confirmationRequired()
                    : bondedBinding.confirmationRequired();
            return new ActionShape(action.kind(), label, confirmation);
        }
    }
}
