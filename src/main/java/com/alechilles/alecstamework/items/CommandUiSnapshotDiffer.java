package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiSection;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Produces section and row-level rendering hints between immutable snapshots. */
final class CommandUiSnapshotDiffer {
    CommandUiSnapshotDiffer() {
    }

    /** Computes a full hint when no prior snapshot exists. */
    @Nonnull
    static CommandUiChangeSet diff(
            @Nullable CommandUiSnapshot previous,
            @Nonnull CommandUiSnapshot current
    ) {
        Objects.requireNonNull(current, "current");
        if (previous == null) return CommandUiChangeSet.full();

        EnumSet<CommandUiSection> sections = EnumSet.noneOf(CommandUiSection.class);
        if (!Objects.equals(previous.toolId(), current.toolId())
                || !Objects.equals(previous.itemId(), current.itemId())
                || !Objects.equals(previous.configId(), current.configId())
                || !Objects.equals(previous.rosterMode(), current.rosterMode())
                || !Objects.equals(previous.enabledCapabilities(),
                        current.enabledCapabilities())) {
            sections.add(CommandUiSection.TOOL);
        }
        if (!Objects.equals(previous.selectedCommand(), current.selectedCommand())
                || !Objects.equals(previous.commandOptions(), current.commandOptions())
                || !Objects.equals(previous.commandActions(), current.commandActions())) {
            sections.add(CommandUiSection.COMMANDS);
        }
        if (!Objects.equals(previous.hotswapAssignments(), current.hotswapAssignments())
                || !Objects.equals(previous.hotswapChoices(), current.hotswapChoices())) {
            sections.add(CommandUiSection.HOTSWAPS);
        }
        if (!Objects.equals(previous.groups(), current.groups())) {
            sections.add(CommandUiSection.GROUPS);
        }
        if (!Objects.equals(previous.panelState(), current.panelState())) {
            sections.add(CommandUiSection.PANEL);
        }
        if (!Objects.equals(previous.globalActions(), current.globalActions())
                || !Objects.equals(previous.deadlines(), current.deadlines())
                || !Objects.equals(previous.emptyStateKey(), current.emptyStateKey())
                || !Objects.equals(previous.disabledReason(), current.disabledReason())
                || previous.serverTimeMillis() != current.serverTimeMillis()) {
            sections.add(CommandUiSection.GLOBAL_PRESENTATION);
        }
        if (previous.actionGeneration() != current.actionGeneration()) {
            sections.add(CommandUiSection.ACTIONS);
        }

        Map<UUID, CommandUiCompanionRow> oldRows = index(previous);
        Map<UUID, CommandUiCompanionRow> newRows = index(current);
        Set<UUID> changed = new HashSet<>();
        Set<UUID> removed = new HashSet<>();
        for (Map.Entry<UUID, CommandUiCompanionRow> entry : newRows.entrySet()) {
            CommandUiCompanionRow old = oldRows.get(entry.getKey());
            if (old == null || !old.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (UUID oldId : oldRows.keySet()) {
            if (!newRows.containsKey(oldId)) removed.add(oldId);
        }
        if (!changed.isEmpty() || !removed.isEmpty()) {
            sections.add(CommandUiSection.COMPANIONS);
        }
        if (!removed.isEmpty()
                || oldRows.size() != newRows.size()
                || !sameOrder(previous, current)) {
            sections.add(CommandUiSection.ROSTER_STRUCTURE);
        }

        ContributorChanges contributorChanges = contributorChanges(
                previous.contributions(), current.contributions());
        return new CommandUiChangeSet(false, sections, changed, removed,
                contributorChanges.ids(), contributorChanges.paths(),
                contributorChanges.rows(), contributorChanges.removedRows());
    }

    /** Alias for code that names the operation {@code between}. */
    @Nonnull
    static CommandUiChangeSet between(
            @Nullable CommandUiSnapshot previous,
            @Nonnull CommandUiSnapshot current
    ) {
        return diff(previous, current);
    }

    @Nonnull
    private static Map<UUID, CommandUiCompanionRow> index(
            CommandUiSnapshot snapshot
    ) {
        Map<UUID, CommandUiCompanionRow> indexed = new HashMap<>();
        snapshot.companionRows().forEach(row -> indexed.put(row.rowId(), row));
        return indexed;
    }

    private static boolean sameOrder(
            CommandUiSnapshot previous,
            CommandUiSnapshot current
    ) {
        if (previous.companionRows().size() != current.companionRows().size()) {
            return false;
        }
        for (int index = 0; index < previous.companionRows().size(); index++) {
            if (!previous.companionRows().get(index).rowId().equals(
                    current.companionRows().get(index).rowId())) return false;
        }
        return true;
    }

    @Nonnull
    private static ContributorChanges contributorChanges(
            @Nonnull Map<CommandUiContributorId, CommandUiContribution> previous,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution> current
    ) {
        Set<CommandUiContributorId> ids = new HashSet<>();
        Map<CommandUiContributorId, Set<String>> paths = new HashMap<>();
        Map<CommandUiContributorId, Set<UUID>> rows = new HashMap<>();
        Map<CommandUiContributorId, Set<UUID>> removedRows = new HashMap<>();
        Set<CommandUiContributorId> all = new HashSet<>();
        all.addAll(previous.keySet());
        all.addAll(current.keySet());
        for (CommandUiContributorId id : all) {
            CommandUiContribution before = previous.get(id);
            CommandUiContribution after = current.get(id);
            if (sameContribution(before, after)) continue;
            ids.add(id);
            if (before == null) {
                addAllPaths(paths, id, after);
                addAllRows(rows, id, after);
                continue;
            }
            if (after == null) {
                addAllRows(removedRows, id, before);
                paths.computeIfAbsent(id, ignored -> new HashSet<>()).add("*");
                continue;
            }
            compareContribution(id, before, after, paths, rows, removedRows);
        }
        return new ContributorChanges(ids, paths, rows, removedRows);
    }

    private static boolean sameContribution(
            @Nullable CommandUiContribution before,
            @Nullable CommandUiContribution after
    ) {
        if (before == after) return true;
        if (before == null || after == null) return false;
        return before.contributorId().equals(after.contributorId())
                && before.pageData().equals(after.pageData())
                && before.rowData().equals(after.rowData())
                && before.pageActions().equals(after.pageActions())
                && before.commandActions().equals(after.commandActions())
                && before.rowActions().equals(after.rowActions())
                && before.flowActions().equals(after.flowActions())
                && before.status() == after.status()
                && before.diagnosticReason().equals(after.diagnosticReason());
    }

    private static void compareContribution(
            CommandUiContributorId id,
            CommandUiContribution before,
            CommandUiContribution after,
            Map<CommandUiContributorId, Set<String>> paths,
            Map<CommandUiContributorId, Set<UUID>> rows,
            Map<CommandUiContributorId, Set<UUID>> removedRows
    ) {
        compareValues(id, "page", before.pageData(), after.pageData(), paths);
        Map<UUID, Map<String, CommandUiValue>> beforeRows = before.rowData();
        Map<UUID, Map<String, CommandUiValue>> afterRows = after.rowData();
        Set<UUID> allRows = new HashSet<>();
        allRows.addAll(beforeRows.keySet());
        allRows.addAll(afterRows.keySet());
        for (UUID rowId : allRows) {
            Map<String, CommandUiValue> oldData = beforeRows.get(rowId);
            Map<String, CommandUiValue> newData = afterRows.get(rowId);
            if (oldData == null) {
                rows.computeIfAbsent(id, ignored -> new HashSet<>()).add(rowId);
                addMapPaths(paths, id, "rows." + rowId, newData);
            } else if (newData == null) {
                removedRows.computeIfAbsent(id, ignored -> new HashSet<>()).add(rowId);
                paths.computeIfAbsent(id, ignored -> new HashSet<>())
                        .add("rows." + rowId);
            } else {
                int beforeCount = paths.getOrDefault(id, Set.of()).size();
                compareValues(id, "rows." + rowId, oldData, newData, paths);
                if (paths.getOrDefault(id, Set.of()).size() != beforeCount) {
                    rows.computeIfAbsent(id, ignored -> new HashSet<>()).add(rowId);
                }
            }
        }
        if (before.status() != after.status()
                || !Objects.equals(before.diagnosticReason(), after.diagnosticReason())
                || !Objects.equals(before.pageActions(), after.pageActions())
                || !Objects.equals(before.commandActions(), after.commandActions())
                || !Objects.equals(before.rowActions(), after.rowActions())
                || !Objects.equals(before.flowActions(), after.flowActions())) {
            paths.computeIfAbsent(id, ignored -> new HashSet<>()).add("metadata");
        }
    }

    private static void compareValues(
            CommandUiContributorId id,
            String prefix,
            Map<String, CommandUiValue> before,
            Map<String, CommandUiValue> after,
            Map<CommandUiContributorId, Set<String>> paths
    ) {
        Set<String> keys = new HashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            CommandUiValue oldValue = before.get(key);
            CommandUiValue newValue = after.get(key);
            if (!Objects.equals(oldValue, newValue)) {
                paths.computeIfAbsent(id, ignored -> new HashSet<>())
                        .add(prefix + "." + key);
            }
        }
    }

    private static void addMapPaths(
            Map<CommandUiContributorId, Set<String>> paths,
            CommandUiContributorId id,
            String prefix,
            Map<String, CommandUiValue> values
    ) {
        if (values == null || values.isEmpty()) {
            paths.computeIfAbsent(id, ignored -> new HashSet<>()).add(prefix);
            return;
        }
        for (String key : values.keySet()) {
            paths.computeIfAbsent(id, ignored -> new HashSet<>())
                    .add(prefix + "." + key);
        }
    }

    private static void addAllPaths(
            Map<CommandUiContributorId, Set<String>> paths,
            CommandUiContributorId id,
            CommandUiContribution contribution
    ) {
        if (contribution == null) return;
        compareValues(id, "page", Map.of(), contribution.pageData(), paths);
        for (Map.Entry<UUID, Map<String, CommandUiValue>> entry
                : contribution.rowData().entrySet()) {
            addMapPaths(paths, id, "rows." + entry.getKey(), entry.getValue());
        }
        paths.computeIfAbsent(id, ignored -> new HashSet<>()).add("metadata");
    }

    private static void addAllRows(
            Map<CommandUiContributorId, Set<UUID>> rows,
            CommandUiContributorId id,
            CommandUiContribution contribution
    ) {
        if (contribution == null || contribution.rowData().isEmpty()) return;
        rows.computeIfAbsent(id, ignored -> new HashSet<>())
                .addAll(contribution.rowData().keySet());
    }

    private record ContributorChanges(
            Set<CommandUiContributorId> ids,
            Map<CommandUiContributorId, Set<String>> paths,
            Map<CommandUiContributorId, Set<UUID>> rows,
            Map<CommandUiContributorId, Set<UUID>> removedRows
    ) {
    }
}
