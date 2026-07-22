package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Holds and renders the full arbitrary-length paid-revival confirmation quote. */
final class LinkedNpcPanelReviveOverlayState {
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
        commandBuilder.set(
                "#TameworkLinkedPanelReviveCostText.Text",
                formatCostText(presentation.costs(), language)
        );
        commandBuilder.setObject(
                "#TameworkLinkedPanelReviveCostText.Anchor",
                buildCostTextAnchor(presentation.costs().size())
        );
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
    static String formatCostText(
            @Nonnull List<CommandReviveCostPresentation.CostLine> costs,
            @Nullable String language) {
        StringBuilder text = new StringBuilder();
        for (CommandReviveCostPresentation.CostLine line : costs) {
            if (!text.isEmpty()) text.append('\n');
            text.append(line.localizedName())
                    .append("    ")
                    .append(line.ownedQuantity())
                    .append(" / ")
                    .append(line.requiredQuantity());
            if (!line.satisfied()) {
                text.append("  (")
                        .append(LocalizedText.format(
                                language,
                                "tamework.ui.linkedPanel.revive.shortage",
                                line.shortageQuantity()
                        ))
                        .append(')');
            }
        }
        return text.toString();
    }

    @Nonnull
    private static Anchor buildCostTextAnchor(int costCount) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(0));
        anchor.setRight(Value.of(8));
        anchor.setHeight(Value.of(Math.max(224, Math.max(1, costCount) * 32)));
        return anchor;
    }

    private String resolveNpcName(@Nullable String language) {
        return npcName == null || npcName.isBlank()
                ? LocalizedText.resolve(language, "tamework.ui.linkedPanel.subtitle.defaultNpcName")
                : npcName;
    }
}
