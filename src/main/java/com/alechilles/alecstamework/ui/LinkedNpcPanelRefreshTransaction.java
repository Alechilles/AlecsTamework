package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Holds packet-delivery state that is committed only after a UI send succeeds. */
final class LinkedNpcPanelRefreshTransaction {
    private final LinkedNpcPanelRefreshValues values = new LinkedNpcPanelRefreshValues();
    private long groupOverlayRevision = -1L;
    private long reviveOverlayRevision = -1L;
    LinkedNpcPanelRefreshValues values() { return values; }
    LinkedNpcPanelRefreshValues stagedValues() { return values.staged(); }
    boolean needsGroupOverlay(long revision) { return groupOverlayRevision != revision; }
    boolean needsReviveOverlay(long revision) { return reviveOverlayRevision != revision; }
    long applyGroupOverlay(LinkedNpcPanelGroupAssignOverlayState overlay,
                           UICommandBuilder commands, String language) {
        long revision = overlay.revision();
        if (needsGroupOverlay(revision)) overlay.applyTo(commands, language);
        return revision;
    }
    long applyReviveOverlay(LinkedNpcPanelFeatureController controller,
                            UICommandBuilder commands, String language) {
        long revision = controller.reviveOverlayRevision();
        if (needsReviveOverlay(revision)) controller.applyOverlay(commands, language);
        return revision;
    }
    void seedOverlayRevisions(long groupRevision, long reviveRevision) { groupOverlayRevision = groupRevision; reviveOverlayRevision = reviveRevision; }
    void commit(LinkedNpcPanelRefreshValues staged, long groupRevision, long reviveRevision) { values.commitFrom(staged); seedOverlayRevisions(groupRevision, reviveRevision); }
}
