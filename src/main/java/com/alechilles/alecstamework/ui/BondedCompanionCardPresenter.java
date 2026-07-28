package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.items.BondedCompanionActionFeedbackMapper;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Binds the dedicated, durable-profile card used by bonded companion rosters.
 *
 * <p>The card deliberately reads only immutable profile presentation. A live
 * projection may enrich the profile elsewhere, but must never be required for
 * a stored or dead row to render correctly.</p>
 */
final class BondedCompanionCardPresenter {
    static final String CARD_UI_PATH = "TameworkBondedCompanionPanelCard.ui";
    private static final int HEALTH_FILL_WIDTH = 375;
    private static final int METRIC_LEFT = 14;
    private static final int METRIC_WIDTH = 66;
    private static final int METRIC_GAP = 6;

    private BondedCompanionCardPresenter() {
    }

    static void bind(
            @Nonnull UICommandBuilder commands,
            @Nonnull UIEventBuilder events,
            @Nonnull String entrySelector,
            @Nonnull UUID cardUuid,
            @Nonnull BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nonnull LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        BondedCompanionStatusPresentation status = row.status();
        CardLayout layout = layout(row.attributes());
        commands.setObject(entrySelector + ".Anchor", layout.cardAnchor());
        bindIdentity(commands, entrySelector, row);
        bindState(commands, entrySelector, row, language);
        bindLayout(commands, entrySelector, layout);
        bindUnlink(commands, events, entrySelector, cardUuid, pendingUnlink,
                config, language);
        bindHealth(commands, entrySelector, row.attributes());
        bindMetrics(commands, entrySelector, row.attributes(), layout, language);
        bindTalents(commands, events, entrySelector, cardUuid, row, pendingUnlink,
                config, layout, language);
        bindPrimaryAction(commands, events, entrySelector, cardUuid, row,
                pendingUnlink, config, language);
    }

    /**
     * Patches only the text and accents that change as a session or cooldown
     * counts down. Existing controls and their input bindings remain intact.
     */
    static void refreshDynamicState(
            @Nonnull UICommandBuilder commands,
            @Nonnull String entrySelector,
            @Nonnull BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        bindState(commands, entrySelector, row, language);
    }


    private static void bindUnlink(
            UICommandBuilder commands,
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        commands.set(entrySelector + " #BondedUnlinkButton.Visible",
                !pendingUnlink);
        commands.set(entrySelector + " #BondedUnlinkConfirmButton.Visible",
                pendingUnlink);
        if (pendingUnlink) {
            commands.set(entrySelector + " #BondedStateDetail.Text",
                    LocalizedText.resolve(language,
                            "tamework.ui.linkedPanel.bonded.detail.unlinkConfirm"));
            commands.set(entrySelector + " #BondedStateDetailValue.Text", "");
        }
        String command = config.unlinkCommandPrefix() + cardUuid;
        events.addEventBinding(CustomUIEventBindingType.Activating,
                entrySelector + " #BondedUnlinkButton",
                EventData.of(config.eventCommandId(), command), false);
        events.addEventBinding(CustomUIEventBindingType.Activating,
                entrySelector + " #BondedUnlinkConfirmButton",
                EventData.of(config.eventCommandId(), command), false);
    }

    private static void bindIdentity(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row
    ) {
        commands.set(entrySelector + " #BondedName.Text", displayName(row));
        commands.set(entrySelector + " #BondedSpecies.Text", identityLine(row));
        commands.set(entrySelector + " #BondedGenderMaleIcon.Visible",
                "male".equalsIgnoreCase(row.gender()));
        commands.set(entrySelector + " #BondedGenderFemaleIcon.Visible",
                "female".equalsIgnoreCase(row.gender()));
    }

    private static void bindState(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        BondedCompanionStatusPresentation status = row.status();
        BondedCompanionCardStatePresentation.StateCopy copy =
                BondedCompanionCardStatePresentation.resolve(row, language);
        commands.set(entrySelector + " #BondedStateInWorld #BondedStateText.Text",
                copy.label());
        commands.set(entrySelector + " #BondedStateStored #BondedStateText.Text",
                copy.label());
        commands.set(entrySelector + " #BondedStateDead #BondedStateText.Text",
                copy.label());
        commands.set(entrySelector + " #BondedStateReady #BondedStateText.Text",
                copy.label());
        commands.set(entrySelector + " #BondedStateDetail.Text", copy.detail());
        commands.set(entrySelector + " #BondedStateDetailValue.Text",
                copy.detailValue());
        commands.set(entrySelector + " #BondedStateInWorld.Visible",
                status.state() == BondedCompanionStateView.ACTIVE);
        commands.set(entrySelector + " #BondedStateStored.Visible",
                status.state() == BondedCompanionStateView.STORED);
        commands.set(entrySelector + " #BondedStateDead.Visible",
                status.state() == BondedCompanionStateView.DEAD
                        && !copy.reviveReady());
        commands.set(entrySelector + " #BondedStateReady.Visible",
                status.state() == BondedCompanionStateView.DEAD
                        && copy.reviveReady());
        commands.set(entrySelector + " #BondedAccentInWorld.Visible",
                status.state() == BondedCompanionStateView.ACTIVE);
        commands.set(entrySelector + " #BondedAccentStored.Visible",
                status.state() == BondedCompanionStateView.STORED);
        commands.set(entrySelector + " #BondedAccentDead.Visible",
                status.state() == BondedCompanionStateView.DEAD
                        && !copy.reviveReady());
        commands.set(entrySelector + " #BondedAccentReady.Visible",
                status.state() == BondedCompanionStateView.DEAD
                        && copy.reviveReady());
    }

    private static void bindHealth(
            UICommandBuilder commands,
            String entrySelector,
            Map<String, String> attributes
    ) {
        int maximum = positiveRoundedInt(attributes.get("maxHealth"), 100);
        int current = boundedInt(attributes.get("currentHealth"), maximum,
                percent(attributes.get("healthPercent"), maximum));
        commands.set(entrySelector + " #BondedHealthText.Text",
                current + " / " + maximum);
        commands.setObject(entrySelector + " #BondedHealthFill.Anchor",
                fillAnchor(0, 0,
                        1 + (int) Math.round((HEALTH_FILL_WIDTH - 1D)
                                * current / maximum), 16));
    }

    private static void bindLayout(
            UICommandBuilder commands,
            String entrySelector,
            CardLayout layout
    ) {
        commands.setObject(entrySelector + " #BondedStateDetail.Anchor",
                horizontalAnchor(14, layout.detailTop(), 118, 14));
        commands.setObject(entrySelector + " #BondedStateDetailValue.Anchor",
                horizontalAnchor(14, layout.detailTop() + 14, 118, 18));
        Anchor action = rightAnchor(layout.actionTop(), 14, 94, 28);
        commands.setObject(entrySelector + " #BondedPrimaryAction.Anchor", action);
        commands.setObject(entrySelector + " #BondedPrimaryActionDisabled.Anchor",
                rightAnchor(layout.actionTop(), 14, 94, 28));
        commands.setObject(entrySelector + " #BondedUnlinkConfirmButton.Anchor",
                rightAnchor(layout.actionTop(), 14, 94, 28));
    }

    private static void bindMetrics(
            UICommandBuilder commands,
            String entrySelector,
            Map<String, String> attributes,
            CardLayout layout,
            @Nullable String language
    ) {
        int visibleIndex = 0;
        visibleIndex = bindMetric(commands, entrySelector, "Happiness",
                attributes.get("happiness"), visibleIndex, layout.metricTop(), language);
        visibleIndex = bindMetric(commands, entrySelector, "Hunger",
                attributes.get("hunger"), visibleIndex, layout.metricTop(), language);
        bindMetric(commands, entrySelector, "Thirst", attributes.get("thirst"),
                visibleIndex, layout.metricTop(), language);
    }

    private static int bindMetric(
            UICommandBuilder commands,
            String entrySelector,
            String metric,
            @Nullable String rawValue,
            int visibleIndex,
            int top,
            @Nullable String language
    ) {
        String selector = entrySelector + " #BondedMetric" + metric;
        boolean visible = rawValue != null && !rawValue.isBlank();
        commands.set(selector + ".Visible", visible);
        if (!visible) {
            return visibleIndex;
        }
        int value = metricPercent(rawValue);
        int left = METRIC_LEFT + visibleIndex * (METRIC_WIDTH + METRIC_GAP);
        commands.setObject(selector + ".Anchor", fillAnchor(left, top,
                METRIC_WIDTH, 18));
        commands.set(selector + " #MetricValue.Text", value + "%");
        commands.set(selector + " #MetricLabel.Text", LocalizedText.resolve(
                language, "tamework.ui.linkedPanel.bonded.metric."
                        + metric.toLowerCase(Locale.ROOT)));
        return visibleIndex + 1;
    }

    private static void bindTalents(
            UICommandBuilder commands,
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            CardLayout layout,
            @Nullable String language
    ) {
        TalentSummary talents = talentSummary(row.attributes());
        commands.set(entrySelector + " #BondedTalentAction.Visible", talents.visible());
        if (!talents.visible()) {
            return;
        }
        commands.setObject(entrySelector + " #BondedTalentAction.Anchor",
                fillAnchor(METRIC_LEFT, layout.talentTop(), 188, 22));
        commands.set(entrySelector + " #BondedTalentText.Text", LocalizedText.format(
                language, "tamework.ui.linkedPanel.bonded.talents.summary",
                talents.level(), talents.availablePoints()));
        boolean canOpen = row.status().state() == BondedCompanionStateView.ACTIVE
                && !pendingUnlink;
        commands.set(entrySelector + " #BondedTalentButton.Visible", canOpen);
        if (canOpen) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedTalentButton",
                    EventData.of(config.eventCommandId(),
                            config.openTalentsCommandPrefix() + cardUuid), false);
        }
    }

    private static void bindPrimaryAction(
            UICommandBuilder commands,
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        BondedCompanionStatusPresentation status = row.status();
        String label = actionLabel(status.action(), language);
        String tooltip = actionTooltip(row, status, language);
        boolean visible = status.action() != BondedCompanionStatusPresentation.Action.NONE;
        boolean enabled = visible && status.actionEnabled() && !pendingUnlink;
        boolean revive = status.action() == BondedCompanionStatusPresentation.Action.REVIVE;
        commands.set(entrySelector + " #BondedPrimaryAction.Visible", enabled && !revive);
        commands.set(entrySelector + " #BondedPrimaryAction.Text", label);
        commands.set(entrySelector + " #BondedPrimaryAction.TooltipText", tooltip);
        commands.set(entrySelector + " #BondedPrimaryActionDisabled.Visible",
                visible && !enabled && !pendingUnlink);
        commands.set(entrySelector + " #BondedPrimaryActionDisabled.Text", label);
        commands.set(entrySelector + " #BondedPrimaryActionDisabled.TooltipText", tooltip);
        commands.set(entrySelector + " #BondedReviveAction.Visible", enabled && revive);
        commands.set(entrySelector + " #BondedReviveAction.Text", label);
        commands.set(entrySelector + " #BondedReviveAction.TooltipText", tooltip);
        if (!enabled) {
            return;
        }
        String command = switch (status.action()) {
            case SUMMON -> config.summonCommandPrefix() + cardUuid;
            case DISMISS -> config.dismissCommandPrefix() + cardUuid;
            case REVIVE -> config.respawnCommandPrefix() + cardUuid;
            case NONE -> null;
        };
        if (command != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + (revive ? " #BondedReviveAction"
                            : " #BondedPrimaryAction"),
                    EventData.of(config.eventCommandId(), command), false);
        }
    }

    private static String reviveTooltip(
            @Nullable BondedCompanionReviveQuote quote,
            BondedCompanionStatusPresentation.Action action,
            @Nullable String language
    ) {
        if (action != BondedCompanionStatusPresentation.Action.REVIVE
                || quote == null || quote.costs().isEmpty()) {
            return "";
        }
        StringBuilder tooltip = new StringBuilder(LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.bonded.revive.cost"));
        for (BondedCompanionReviveQuote.CostLine line : quote.costs()) {
            tooltip.append('\n')
                    .append(ReviveCostItemText.resolve(line.itemId(), null, language))
                    .append(" x ")
                    .append(line.requiredQuantity());
        }
        return tooltip.toString();
    }

    private static String actionTooltip(
            BondedCompanionPanelPresentation row,
            BondedCompanionStatusPresentation status,
            @Nullable String language
    ) {
        if (status.action() == BondedCompanionStatusPresentation.Action.REVIVE) {
            return reviveTooltip(row.reviveQuote(), status.action(), language);
        }
        if (status.actionEnabled()) {
            return "";
        }
        if (status.cooldownRemainingMs() > 0L
                && status.action() == BondedCompanionStatusPresentation.Action.SUMMON) {
            return LocalizedText.format(language,
                    "tamework.ui.linkedPanel.bonded.detail.summonIn",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            status.cooldownRemainingMs(), language));
        }
        if (status.blockReason() == BondedCompanionActionBlockReason.CAPACITY_REACHED) {
            String capacity = capacityTooltip(row, language);
            if (!capacity.isEmpty()) {
                return capacity;
            }
        }
        if (status.blockReason() != null) {
            return BondedCompanionActionFeedbackMapper.resolve(language,
                    status.blockReason());
        }
        return status.unavailableReason() == null ? ""
                : status.unavailableReason();
    }

    private static String capacityTooltip(
            BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        Map<String, String> attributes = row.attributes();
        int active = nonNegativeInt(attributes.get(
                BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT));
        int limit = positiveRoundedInt(attributes.get(
                BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT), 0);
        String label = attributes.get(
                BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL);
        if (limit == 0 || label == null || label.isBlank()) {
            return "";
        }
        return LocalizedText.format(language,
                "tamework.ui.linkedPanel.bonded.tooltip.summonCapacity",
                label, active, limit);
    }

    private static String actionLabel(
            BondedCompanionStatusPresentation.Action action,
            @Nullable String language
    ) {
        return switch (action) {
            case SUMMON -> LocalizedText.resolve(language,
                    "tamework.ui.linkedPanel.bonded.action.summon");
            case DISMISS -> LocalizedText.resolve(language,
                    "tamework.ui.linkedPanel.bonded.action.dismiss");
            case REVIVE -> LocalizedText.resolve(language,
                    "tamework.ui.linkedPanel.bonded.action.revive");
            case NONE -> "";
        };
    }

    private static TalentSummary talentSummary(Map<String, String> attributes) {
        int level = positiveRoundedInt(attributes.get("level"), 0);
        String talentConfig = attributes.get("talentConfigId");
        if (level == 0 || talentConfig == null || talentConfig.isBlank()) {
            return TalentSummary.hidden();
        }
        int spent = nonNegativeInt(attributes.get("talentSpentPoints"));
        int earned = CompanionLevelingService.resolveEarnedTalentPoints(level,
                attributes.get("levelingConfigId"));
        return new TalentSummary(true, level, Math.max(0, earned - spent));
    }

    private static CardLayout layout(Map<String, String> attributes) {
        boolean metrics = hasMetric(attributes, "happiness")
                || hasMetric(attributes, "hunger")
                || hasMetric(attributes, "thirst");
        boolean talents = talentSummary(attributes).visible();
        int metricTop = 86;
        int talentTop = metrics ? 110 : 86;
        int detailTop = talents ? talentTop + 26 : metrics ? 110 : 82;
        int actionTop = detailTop + 3;
        return new CardLayout(metricTop, talentTop, detailTop, actionTop);
    }

    private static boolean hasMetric(Map<String, String> attributes,
                                     String key) {
        String value = attributes.get(key);
        return value != null && !value.isBlank();
    }

    private static Anchor fillAnchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setLeft(Value.of(left));
        if (width > 0) {
            anchor.setWidth(Value.of(width));
        } else {
            anchor.setRight(Value.of(0));
        }
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private static Anchor horizontalAnchor(
            int left, int top, int right, int height
    ) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setLeft(Value.of(left));
        anchor.setRight(Value.of(right));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private static Anchor rightAnchor(
            int top, int right, int width, int height
    ) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setRight(Value.of(right));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private static String displayName(BondedCompanionPanelPresentation row) {
        if (row.displayName() != null) {
            return row.displayName();
        }
        if (row.species() != null) {
            return row.species();
        }
        return LocalizedText.resolve((String) null,
                "tamework.ui.linkedPanel.subtitle.defaultNpcName");
    }

    private static String identityLine(BondedCompanionPanelPresentation row) {
        return row.species() == null ? "" : row.species();
    }

    private static int positiveRoundedInt(@Nullable String value, int fallback) {
        int parsed = nonNegativeRoundedInt(value);
        return parsed > 0 ? parsed : fallback;
    }

    private static int boundedInt(@Nullable String value, int maximum,
                                  int fallback) {
        int parsed = value == null ? fallback : nonNegativeRoundedInt(value);
        return Math.min(Math.max(1, maximum), parsed);
    }

    private static int nonNegativeRoundedInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                return 0;
            }
            return (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, Math.round(parsed)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int percent(@Nullable String value, int scale) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                return 0;
            }
            double normalized = parsed >= 0D && parsed <= 1D
                    ? parsed * 100D : parsed;
            return (int) Math.round(Math.max(0D, Math.min(100D, normalized))
                    * Math.max(1, scale) / 100D);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int metricPercent(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                return 0;
            }
            return (int) Math.round(Math.max(0D, Math.min(100D, parsed)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int nonNegativeInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record TalentSummary(boolean visible, int level, int availablePoints) {
        private static TalentSummary hidden() {
            return new TalentSummary(false, 0, 0);
        }
    }

    /** Compact vertical allocation that omits absent metrics and talent rows. */
    private record CardLayout(int metricTop, int talentTop, int detailTop,
                              int actionTop) {
        private int baseHeight() {
            return actionTop + 36;
        }

        private Anchor cardAnchor() {
            return fillAnchor(0, 3, 0, baseHeight());
        }
    }
}
