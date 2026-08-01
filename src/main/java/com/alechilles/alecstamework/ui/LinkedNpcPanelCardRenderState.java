package com.alechilles.alecstamework.ui;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Tracks the last card render so the linked-panel timer can patch an existing
 * card instead of repeatedly clearing and recreating the entire list.
 */
final class LinkedNpcPanelCardRenderState {
    private LinkedNpcEntry[] entries = new LinkedNpcEntry[0];
    private Map<UUID, CommandPanelFeaturePresentation> features = Map.of();
    private UUID pendingUnlinkNpcUuid;

    boolean requiresRebuild(
            @Nonnull LinkedNpcEntry[] currentEntries,
            @Nonnull Map<UUID, CommandPanelFeaturePresentation> currentFeatures
    ) {
        if (entries.length != currentEntries.length) return true;
        for (int index = 0; index < currentEntries.length; index++) {
            UUID npcUuid = currentEntries[index].npcUuid();
            if (isBonded(features.get(npcUuid))
                    != isBonded(currentFeatures.get(npcUuid))) {
                return true;
            }
        }
        return false;
    }

    Update updateAt(
            int index,
            @Nonnull LinkedNpcEntry[] currentEntries,
            UUID currentPendingUnlinkNpcUuid,
            @Nonnull Map<UUID, CommandPanelFeaturePresentation> currentFeatures
    ) {
        LinkedNpcEntry previousEntry = entries[index];
        LinkedNpcEntry currentEntry = currentEntries[index];
        if (pending(previousEntry, pendingUnlinkNpcUuid)
                != pending(currentEntry, currentPendingUnlinkNpcUuid)) {
            return Update.FULL;
        }
        CommandPanelFeaturePresentation previous = features.get(
                currentEntry.npcUuid());
        CommandPanelFeaturePresentation current = currentFeatures.get(
                currentEntry.npcUuid());
        // Active bonded health also updates the legacy row snapshot. Classify
        // its matching bonded presentation before that stale generic row can
        // force a full-card bind.
        if (onlyDynamicBondedChange(previous, current)) return Update.DYNAMIC;
        if (!Objects.equals(previousEntry, currentEntry)) return Update.FULL;
        if (Objects.equals(previous, current)) return Update.NONE;
        return Update.FULL;
    }

    void markRendered(
            @Nonnull LinkedNpcEntry[] currentEntries,
            UUID currentPendingUnlinkNpcUuid,
            @Nonnull Map<UUID, CommandPanelFeaturePresentation> currentFeatures
    ) {
        entries = currentEntries.clone();
        pendingUnlinkNpcUuid = currentPendingUnlinkNpcUuid;
        features = Map.copyOf(currentFeatures);
    }

    CommandPanelFeaturePresentation presentation(UUID npcUuid) { return features.get(npcUuid); }


    private static boolean pending(LinkedNpcEntry entry, UUID pendingUuid) {
        return entry != null && pendingUuid != null
                && pendingUuid.equals(entry.npcUuid());
    }

    private static boolean onlyDynamicBondedChange(
            CommandPanelFeaturePresentation previous,
            CommandPanelFeaturePresentation current
    ) {
        return previous != null && current != null
                && previous.bonded() != null && current.bonded() != null
                && BondedCompanionCardDynamicState.changedOnlyByLiveFields(
                        previous.bonded(), current.bonded());
    }

    private static boolean isBonded(
            CommandPanelFeaturePresentation presentation
    ) {
        return presentation != null && presentation.bonded() != null;
    }

    enum Update { NONE, DYNAMIC, FULL }
}
