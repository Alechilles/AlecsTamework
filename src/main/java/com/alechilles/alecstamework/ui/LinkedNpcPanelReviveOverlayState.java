package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Holds and renders the full arbitrary-length paid-revival confirmation quote. */
final class LinkedNpcPanelReviveOverlayState {
    static final String COST_LINE_UI_PATH = "TameworkReviveCostLine.ui";

    private boolean visible;
    private UUID npcUuid;
    private String npcName;
    private CommandReviveCostPresentation presentation;

    boolean isVisible() {
        return visible;
    }

    void open(@Nonnull LinkedNpcEntry entry) {
        if (entry.npcUuid() == null || entry.reviveCostPresentation() == null) {
            clear();
            return;
        }
        visible = true;
        npcUuid = entry.npcUuid();
        npcName = entry.displayName();
        presentation = entry.reviveCostPresentation();
    }

    void refresh(@Nullable LinkedNpcEntry entry) {
        if (!visible || entry == null || !java.util.Objects.equals(npcUuid, entry.npcUuid())) {
            return;
        }
        presentation = entry.reviveCostPresentation();
        npcName = entry.displayName();
        if (presentation == null) clear();
    }

    @Nullable
    UUID consumeIfAffordable() {
        if (!visible || presentation == null || !presentation.affordable()) return null;
        UUID selected = npcUuid;
        clear();
        return selected;
    }

    void clear() {
        visible = false;
        npcUuid = null;
        npcName = null;
        presentation = null;
    }

    void applyTo(@Nonnull UICommandBuilder commandBuilder, @Nullable String language) {
        commandBuilder.set("#TameworkLinkedPanelReviveOverlay.Visible", visible);
        if (!visible || presentation == null) return;
        commandBuilder.set(
                "#TameworkLinkedPanelReviveSubtitle.Text",
                LocalizedText.format(language, "tamework.ui.linkedPanel.revive.subtitle", resolveNpcName(language))
        );
        commandBuilder.clear("#TameworkLinkedPanelReviveCostList");
        List<CommandReviveCostPresentation.CostLine> costs = presentation.costs();
        for (int index = 0; index < costs.size(); index++) {
            CommandReviveCostPresentation.CostLine line = costs.get(index);
            commandBuilder.append("#TameworkLinkedPanelReviveCostList", COST_LINE_UI_PATH);
            String root = "#TameworkLinkedPanelReviveCostList[" + index + "]";
            commandBuilder.set(root + " #CostItem.Slots", List.of(itemSlot(line)));
            commandBuilder.set(root + " #CostName.Text", line.localizedName());
            String ownedRequired = line.ownedQuantity() + " / " + line.requiredQuantity();
            commandBuilder.set(root + " #CostSatisfied.Text", ownedRequired);
            commandBuilder.set(root + " #CostSatisfied.Visible", line.satisfied());
            commandBuilder.set(root + " #CostInsufficient.Text", ownedRequired);
            commandBuilder.set(root + " #CostInsufficient.Visible", !line.satisfied());
            commandBuilder.set(root + " #CostShortage.Visible", !line.satisfied());
            commandBuilder.set(
                    root + " #CostShortage.Text",
                    line.satisfied()
                            ? ""
                            : LocalizedText.format(
                                    language,
                                    "tamework.ui.linkedPanel.revive.shortage",
                                    line.shortageQuantity()
                            )
            );
        }
        boolean affordable = presentation.affordable();
        commandBuilder.set("#TameworkLinkedPanelReviveConfirmButton.Visible", affordable);
        commandBuilder.set("#TameworkLinkedPanelReviveBlockedButton.Visible", !affordable);
        commandBuilder.set(
                "#TameworkLinkedPanelReviveSummary.Text",
                affordable
                        ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.revive.ready")
                        : LocalizedText.format(
                                language,
                                "tamework.ui.linkedPanel.revive.missingComponents",
                                presentation.missingComponentCount()
                        )
        );
    }

    boolean canConfirm() {
        return visible && presentation != null && presentation.affordable();
    }

    @Nonnull
    private ItemGridSlot itemSlot(@Nonnull CommandReviveCostPresentation.CostLine line) {
        ItemGridSlot slot = new ItemGridSlot(new ItemStack(line.itemId(), 1));
        slot.setName(line.localizedName());
        slot.setSkipItemQualityBackground(true);
        return slot;
    }

    private String resolveNpcName(@Nullable String language) {
        return npcName == null || npcName.isBlank()
                ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.subtitle.defaultNpcName")
                : npcName;
    }
}
