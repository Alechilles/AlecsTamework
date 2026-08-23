package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiSection;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
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

        return new CommandUiChangeSet(false, sections, changed, removed);
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
}
