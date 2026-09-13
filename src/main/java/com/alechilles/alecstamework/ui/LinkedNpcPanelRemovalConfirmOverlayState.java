package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Page-local confirmation for irreversible generic companion removal actions. */
final class LinkedNpcPanelRemovalConfirmOverlayState {
    enum Action {
        RELEASE(
                "tamework.ui.linkedPanel.card.button.release",
                "tamework.ui.linkedPanel.card.tooltip.release"
        ),
        CULL(
                "tamework.ui.linkedPanel.card.button.cull",
                "tamework.ui.linkedPanel.card.tooltip.cull"
        );

        private final String captionKey;
        private final String consequenceKey;

        Action(String captionKey, String consequenceKey) {
            this.captionKey = captionKey;
            this.consequenceKey = consequenceKey;
        }
    }

    private boolean visible;
    private UUID npcUuid;
    private String npcName = "";
    private Action action;
    private long revision;

    boolean isVisible() {
        return visible;
    }

    long revision() {
        return revision;
    }

    boolean matchesRevision(long revision) {
        return visible && this.revision == revision;
    }

    void open(@Nonnull LinkedNpcEntry entry, @Nonnull Action action) {
        visible = true;
        npcUuid = entry.npcUuid();
        npcName = entry.displayName();
        this.action = action;
        revision++;
    }

    @Nullable
    UUID npcUuid() {
        return npcUuid;
    }

    @Nullable
    Action action() {
        return action;
    }

    void clear() {
        if (!visible && npcUuid == null && action == null) {
            return;
        }
        visible = false;
        npcUuid = null;
        npcName = "";
        action = null;
        revision++;
    }

    void applyTo(@Nonnull UICommandBuilder commands, @Nullable String language) {
        commands.set("#TameworkLinkedPanelRemovalConfirmOverlay.Visible", visible);
        if (!visible || action == null) {
            return;
        }
        String caption = LocalizedText.resolve(language, action.captionKey);
        commands.set("#TameworkLinkedPanelRemovalConfirmTitle.Text", caption + " " + npcName);
        commands.set("#TameworkLinkedPanelRemovalConfirmTitle.TooltipText", npcName);
        commands.set("#TameworkLinkedPanelRemovalConfirmMessage.Text",
                LocalizedText.resolve(language, action.consequenceKey));
        commands.set("#TameworkLinkedPanelRemovalConfirmButton.Text", caption);
    }
}
