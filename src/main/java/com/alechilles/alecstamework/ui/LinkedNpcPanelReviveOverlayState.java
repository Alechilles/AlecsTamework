package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Holds and renders the complete server-authoritative revival quote. */
final class LinkedNpcPanelReviveOverlayState {
    static final String COST_LINE_UI_PATH = "TameworkReviveCostLine.ui";

    private boolean visible;
    private UUID npcUuid;
    private String npcName;
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
        npcName = entry.displayName();
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
        npcName = null;
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
        commandBuilder.set(
                "#TameworkLinkedPanelReviveSubtitle.Text",
                LocalizedText.format(
                        language,
                        "tamework.ui.linkedPanel.revive.subtitle",
                        resolveNpcName(language)
                )
        );
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
        commandBuilder.set(
                "#TameworkLinkedPanelReviveSummary.Text",
                summary(language)
        );
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
            commandBuilder.set(
                    root + " #CostItem.Slots",
                    List.of(itemSlot(line))
            );
            commandBuilder.set(
                    root + " #CostName.Text", line.localizedName()
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
            commandBuilder.set(
                    root + " #CostShortage.Visible", !line.satisfied()
            );
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
    }

    private String summary(String language) {
        PaidCommandRevivalQuote.Status status = presentation.status();
        return switch (status) {
            case READY -> LocalizedText.resolve(
                    language,
                    "tamework.ui.linkedPanel.revive.ready"
            );
            case INSUFFICIENT_COST -> LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.revive.missingComponents",
                    presentation.missingComponentCount()
            );
            case COOLDOWN -> LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.revive.cooldown",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            presentation.cooldownRemainingMs(), language
                    )
            );
            case DISABLED -> LocalizedText.resolve(
                    language,
                    "tamework.ui.linkedPanel.revive.disabled"
            );
            case DENIED -> LocalizedText.resolve(
                    language,
                    "tamework.ui.linkedPanel.revive.denied"
            );
            case UNAVAILABLE -> LocalizedText.resolve(
                    language,
                    "tamework.ui.linkedPanel.revive.unavailable"
            );
        };
    }

    private ItemGridSlot itemSlot(
            CommandReviveCostPresentation.CostLine line
    ) {
        ItemGridSlot slot = new ItemGridSlot(
                new ItemStack(line.itemId(), 1)
        );
        slot.setName(line.localizedName());
        slot.setSkipItemQualityBackground(true);
        return slot;
    }

    private String resolveNpcName(String language) {
        return npcName == null || npcName.isBlank()
                ? LocalizedText.resolve(
                        language,
                        "tamework.ui.linkedPanel.subtitle.defaultNpcName"
                )
                : npcName;
    }
}
