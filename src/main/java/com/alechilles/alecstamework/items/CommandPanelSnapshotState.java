package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Keeps the entry and feature snapshots consumed by one command-page refresh coherent. */
final class CommandPanelSnapshotState {
    private final Supplier<CommandPanelEntrySourceService.CommandPanelSnapshot> supplier;
    private CommandPanelEntrySourceService.CommandPanelSnapshot current =
            new CommandPanelEntrySourceService.CommandPanelSnapshot(List.of(), Map.of());

    CommandPanelSnapshotState(
            Supplier<CommandPanelEntrySourceService.CommandPanelSnapshot> supplier) {
        this.supplier = supplier;
    }

    List<LinkedNpcEntry> refreshEntries() {
        CommandPanelEntrySourceService.CommandPanelSnapshot next = supplier.get();
        current = next == null
                ? new CommandPanelEntrySourceService.CommandPanelSnapshot(List.of(), Map.of())
                : next;
        return current.entries();
    }

    Map<UUID, CommandPanelFeaturePresentation> featurePresentations() {
        return current.featurePresentations();
    }

    String emptyStateKey() {
        return current.emptyStateKey();
    }

    CommandPanelFeaturePresentation presentation(UUID id) {
        return id == null ? null : current.featurePresentations().get(id);
    }

    LinkedNpcEntry entry(UUID id) {
        if (id == null) return null;
        for (LinkedNpcEntry entry : current.entries()) {
            if (id.equals(entry.npcUuid())) return entry;
        }
        return null;
    }
}
