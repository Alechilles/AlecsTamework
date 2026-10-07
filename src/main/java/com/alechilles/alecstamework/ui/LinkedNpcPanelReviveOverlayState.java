package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Holds and renders the complete server-authoritative revival quote. */
final class LinkedNpcPanelReviveOverlayState {
    static final String COST_LINE_UI_PATH = "TameworkReviveCostLine.ui";
    private static final String PANEL_UI = "TameworkLinkedNpcPanel.ui";
    private static final int MODAL_WIDTH = 420;
    /** Centred in the 928 px companions panel. */
    private static final int MODAL_LEFT = (928 - MODAL_WIDTH) / 2;
    private static final int CONTENT_LEFT = 16;
    private static final int CONTENT_WIDTH = MODAL_WIDTH - 2 * CONTENT_LEFT;
    private static final int MODAL_HEIGHT_LIMIT = 592;
    private static final int MODAL_TOP_LIMIT = 24;
    private static final int OVERLAY_HEIGHT = 640;
    /** A 44 px cost plate and the 4 px gap under it (TameworkReviveCostLine.ui). */
    private static final int COST_ROW_HEIGHT = 48;
    /** Below the 48 px header band, the subtitle and the cost heading. */
    private static final int COST_VIEWPORT_TOP = 100;
    /** Five lines; a longer cost scrolls. */
    private static final int COST_VIEWPORT_MAX_HEIGHT = 5 * COST_ROW_HEIGHT;
    private static final int SUMMARY_GAP = 6;
    private static final int SUMMARY_HEIGHT = 18;
    private static final int ACTION_GAP = 12;
    private static final int ACTION_HEIGHT = 30;
    private static final int MODAL_BOTTOM_PADDING = 14;

    private boolean visible;
    private UUID npcUuid;
    private CommandReviveCostPresentation presentation;
    private String companionName = "";
    private long revision;

    boolean isVisible() {
        return visible;
    }

    long revision() { return revision; }

    void open(
            @Nonnull LinkedNpcEntry entry,
            @Nonnull CommandReviveCostPresentation quote
    ) {
        if (entry.npcUuid() == null) {
            clear();
            return;
        }
        visible = true;
        npcUuid = entry.npcUuid();
        companionName = entry.displayName() == null ? "" : entry.displayName();
        presentation = quote;
        revision++;
    }

    void refresh(@Nullable CommandPanelFeaturePresentation row) {
        if (!visible) {
            return;
        }
        CommandReviveCostPresentation refreshed = row == null ? null : row.revival();
        if (!java.util.Objects.equals(presentation, refreshed)) {
            presentation = refreshed;
            revision++;
        }
        if (presentation == null) {
            clear();
        }
    }

    @Nullable
    UUID consumeIfConfirmed() {
        if (!visible || presentation == null
                || !presentation.confirmEnabled()) {
            return null;
        }
        UUID selected = npcUuid;
        clear();
        return selected;
    }

    @Nullable
    UUID npcUuid() {
        return npcUuid;
    }

    void clear() {
        if (!visible && npcUuid == null && presentation == null) return;
        visible = false;
        npcUuid = null;
        presentation = null;
        revision++;
    }

    void applyTo(
            @Nonnull UICommandBuilder commandBuilder,
            @Nullable String language
    ) {
        commandBuilder.set(
                "#TameworkLinkedPanelReviveOverlay.Visible", visible
        );
        if (!visible || presentation == null) {
            return;
        }
        bindLayout(commandBuilder, presentation.costs().size());
        bindCosts(commandBuilder, language);
        boolean confirmEnabled = presentation.confirmEnabled();
        commandBuilder.set("#TameworkLinkedPanelReviveSubtitle.Text", LocalizedText.format(language,
                "tamework.ui.linkedPanel.revive.subtitle", companionName));
        commandBuilder.set("#TameworkLinkedPanelReviveSummary.Text",
                LinkedNpcPanelFeatureBinder.revivalStatus(presentation, language));
        commandBuilder.set("#TameworkLinkedPanelReviveSummary.Style", Value.ref(PANEL_UI,
                confirmEnabled ? "ReviveSummaryReady" : "ReviveSummaryBlocked"));
        commandBuilder.set(
                "#TameworkLinkedPanelReviveConfirmButton.Visible",
                confirmEnabled
        );
        commandBuilder.set(
                "#TameworkLinkedPanelReviveBlockedButton.Visible",
                !confirmEnabled
        );
    }

    private void bindLayout(UICommandBuilder commandBuilder, int costCount) {
        int requestedCostHeight = Math.max(0, costCount) * COST_ROW_HEIGHT;
        int costViewportHeight = Math.min(requestedCostHeight,
                COST_VIEWPORT_MAX_HEIGHT);
        int summaryTop = COST_VIEWPORT_TOP + costViewportHeight + SUMMARY_GAP;
        int actionTop = summaryTop + SUMMARY_HEIGHT + ACTION_GAP;
        int modalHeight = Math.min(MODAL_HEIGHT_LIMIT,
                actionTop + ACTION_HEIGHT + MODAL_BOTTOM_PADDING);
        int modalTop = Math.max(MODAL_TOP_LIMIT,
                (OVERLAY_HEIGHT - modalHeight) / 2);
        commandBuilder.setObject("#TameworkLinkedPanelReviveModal.Anchor",
                anchor(modalTop, MODAL_LEFT, MODAL_WIDTH, modalHeight));
        commandBuilder.setObject("#TameworkLinkedPanelReviveCostViewport.Anchor",
                anchor(COST_VIEWPORT_TOP, CONTENT_LEFT, CONTENT_WIDTH, costViewportHeight));
        commandBuilder.setObject("#TameworkLinkedPanelReviveSummary.Anchor",
                anchor(summaryTop, CONTENT_LEFT, CONTENT_WIDTH, SUMMARY_HEIGHT));
        commandBuilder.setObject("#TameworkLinkedPanelReviveActions.Anchor",
                anchor(actionTop, CONTENT_LEFT, CONTENT_WIDTH, ACTION_HEIGHT));
    }

    private void bindCosts(
            UICommandBuilder commandBuilder,
            String language
    ) {
        commandBuilder.clear("#TameworkLinkedPanelReviveCostList");
        List<CommandReviveCostPresentation.CostLine> costs =
                presentation.costs();
        for (int index = 0; index < costs.size(); index++) {
            CommandReviveCostPresentation.CostLine line =
                    costs.get(index);
            commandBuilder.append(
                    "#TameworkLinkedPanelReviveCostList",
                    COST_LINE_UI_PATH
            );
            String root = "#TameworkLinkedPanelReviveCostList["
                    + index + "]";
            String displayName = ReviveCostItemText.resolve(line.itemId(),
                    line.localizedName(), language);
            ItemGridSlot slot = itemSlot(line, displayName);
            if (slot != null) {
                commandBuilder.set(root + " #CostItem.Slots", List.of(slot));
            }
            commandBuilder.set(
                    root + " #CostName.Text", displayName
            );
            String ownedRequired = line.ownedQuantity()
                    + " / " + line.requiredQuantity();
            commandBuilder.set(
                    root + " #CostSatisfied.Text", ownedRequired
            );
            commandBuilder.set(
                    root + " #CostSatisfied.Visible", line.satisfied()
            );
            commandBuilder.set(
                    root + " #CostInsufficient.Text", ownedRequired
            );
            commandBuilder.set(
                    root + " #CostInsufficient.Visible", !line.satisfied()
            );
            commandBuilder.set(root + " #CostEdgeSatisfied.Visible", line.satisfied());
            commandBuilder.set(root + " #CostEdgeInsufficient.Visible", !line.satisfied());
            commandBuilder.set(root + " #CostCheck.Visible", line.satisfied());
            commandBuilder.set(root + " #CostShortage.Visible", !line.satisfied());
            if (!line.satisfied()) {
                commandBuilder.set(root + " #CostShortageText.Text", LocalizedText.format(language,
                        "tamework.ui.linkedPanel.revive.shortage",
                        Math.max(0, line.requiredQuantity() - line.ownedQuantity())));
            }
        }
    }

    @Nullable
    private ItemGridSlot itemSlot(
            CommandReviveCostPresentation.CostLine line,
            String displayName
    ) {
        try {
            ItemGridSlot slot = new ItemGridSlot(
                    new ItemStack(line.itemId(), 1)
            );
            slot.setName(displayName);
            slot.setSkipItemQualityBackground(true);
            return slot;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Anchor anchor(int top, int left, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setLeft(Value.of(left));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }
}
