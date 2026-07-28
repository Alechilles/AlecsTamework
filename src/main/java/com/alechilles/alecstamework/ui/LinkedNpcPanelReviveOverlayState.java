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
    private static final int MODAL_WIDTH = 382;
    private static final int MODAL_HEIGHT_LIMIT = 592;
    private static final int MODAL_TOP_LIMIT = 24;
    private static final int OVERLAY_HEIGHT = 640;
    private static final int COST_ROW_HEIGHT = 44;
    private static final int COST_VIEWPORT_TOP = 44;
    private static final int COST_VIEWPORT_MAX_HEIGHT = 440;
    private static final int ACTION_GAP = 14;
    private static final int ACTION_HEIGHT = 24;
    private static final int MODAL_BOTTOM_PADDING = 10;

    private boolean visible;
    private UUID npcUuid;
    private CommandReviveCostPresentation presentation;

    boolean isVisible() {
        return visible;
    }

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
        presentation = quote;
    }

    void refresh(@Nullable CommandPanelFeaturePresentation row) {
        if (!visible) {
            return;
        }
        presentation = row == null ? null : row.revival();
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
        visible = false;
        npcUuid = null;
        presentation = null;
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
        int actionTop = COST_VIEWPORT_TOP + costViewportHeight + ACTION_GAP;
        int modalHeight = Math.min(MODAL_HEIGHT_LIMIT,
                actionTop + ACTION_HEIGHT + MODAL_BOTTOM_PADDING);
        int modalTop = Math.max(MODAL_TOP_LIMIT,
                (OVERLAY_HEIGHT - modalHeight) / 2);
        commandBuilder.setObject("#TameworkLinkedPanelReviveModal.Anchor",
                anchor(modalTop, 24, MODAL_WIDTH, modalHeight));
        commandBuilder.setObject("#TameworkLinkedPanelReviveCostViewport.Anchor",
                anchor(COST_VIEWPORT_TOP, 14, 354, costViewportHeight));
        commandBuilder.setObject("#TameworkLinkedPanelReviveActions.Anchor",
                anchor(actionTop, 14, 354, ACTION_HEIGHT));
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
