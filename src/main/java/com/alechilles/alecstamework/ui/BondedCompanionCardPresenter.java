package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
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
    private static final int HEALTH_FILL_WIDTH = 358;
    private static final int XP_FILL_WIDTH = 358;
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
        ProgressionSummary progression = progressionSummary(row.attributes(),
                row.roleId());
        CardLayout layout = layout(row.attributes());
        commands.setObject(entrySelector + ".Anchor", layout.cardAnchor());
        bindIdentity(commands, entrySelector, row, progression, language);
        bindState(commands, entrySelector, row, language);
        bindLayout(commands, entrySelector, layout);
        bindUnlink(commands, events, entrySelector, cardUuid, pendingUnlink,
                config, language);
        bindHealth(commands, entrySelector, row.attributes());
        bindXpProgress(commands, entrySelector, row, progression, language);
        bindMetrics(commands, entrySelector, row.attributes(), layout, language);
        bindProgression(commands, events, entrySelector, cardUuid, row,
                progression, pendingUnlink, config, language);
        bindFlightToggle(commands, events, entrySelector, cardUuid, row,
                config, language);
        bindShoulderRide(commands, events, entrySelector, cardUuid, row,
                config, language);
        bindPrimaryAction(commands, events, entrySelector, cardUuid, row,
                pendingUnlink, config, language);
    }

    /**
     * Patches live status text, vitals, and accents without recreating card
     * controls or their input bindings.
     */
    static void refreshDynamicState(
            @Nonnull UICommandBuilder commands,
            @Nonnull String entrySelector,
            @Nonnull BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        bindState(commands, entrySelector, row, language);
        bindHealth(commands, entrySelector, row.attributes());
        bindXpProgress(commands, entrySelector, row,
                progressionSummary(row.attributes(), row.roleId()), language);
        bindFlightToggle(commands, entrySelector, row, language);
        bindShoulderRide(commands, entrySelector, row, language);
    }

    /** Patches progression-derived labels and tooltips without emitting events. */
    static void refreshProgressionState(@Nonnull UICommandBuilder commands,
                                        @Nonnull String entrySelector,
                                        @Nonnull BondedCompanionPanelPresentation row,
                                        boolean pendingUnlink, @Nullable String language) {
        ProgressionSummary progression = progressionSummary(row.attributes(), row.roleId());
        bindIdentity(commands, entrySelector, row, progression, language);
        bindXpProgress(commands, entrySelector, row, progression, language);
        commands.set(entrySelector + " #BondedProgressionButton.Visible", !pendingUnlink);
        commands.set(entrySelector + " #BondedProgressionButton.TooltipText", progression.visible()
                ? progressionTooltip(progression, row.attributes(), row.roleId(), language) : LocalizedText.resolve(language,
                "tamework.ui.linkedPanel.bonded.talents.tooltip"));
    }

    /**
     * Re-emits card input bindings without recreating any visible controls.
     *
     * <p>The linked panel sends lightweight refresh packets while timers are
     * running. Those packets have a fresh event-binding payload, so a bonded
     * card must contribute its handlers again even when its visual tree did
     * not need a rebuild.</p>
     */
    static void bindEventBindings(
            @Nonnull UIEventBuilder events,
            @Nonnull String entrySelector,
            @Nonnull UUID cardUuid,
            @Nonnull BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            @Nonnull LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        bindUnlinkEvents(events, entrySelector, cardUuid, config);
        bindProgressionEvents(events, entrySelector, cardUuid,
                progressionSummary(row.attributes(), row.roleId()), row.attributes(),
                pendingUnlink, config);
        bindPrimaryActionEvents(events, entrySelector, cardUuid, row,
                pendingUnlink, config, language);
        bindFlightToggleEvents(events, entrySelector, cardUuid, row, config);
        bindShoulderRideEvents(events, entrySelector, cardUuid, row, config);
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
        bindUnlinkEvents(events, entrySelector, cardUuid, config);
    }

    private static void bindUnlinkEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
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
            BondedCompanionPanelPresentation row,
            ProgressionSummary progression,
            @Nullable String language
    ) {
        commands.set(entrySelector + " #BondedName.Text", displayName(row));
        commands.set(entrySelector + " #BondedSpecies.Text",
                identityLine(row));
        commands.set(entrySelector + " #BondedLevelText.Visible",
                progression.visible());
        commands.set(entrySelector + " #BondedLevelText.Text",
                progression.visible() ? LocalizedText.format(language,
                        "tamework.ui.linkedPanel.bonded.talents.level",
                        progression.level()) : "");
        commands.set(entrySelector + " #BondedTalentPointAction.Visible", true);
        boolean pointsAvailable = progression.talentsConfigured()
                && progression.availablePoints() > 0;
        commands.set(entrySelector + " #BondedTalentPointCountBadgeBorder.Visible",
                pointsAvailable);
        commands.set(entrySelector + " #BondedTalentPointCountBadgeFill.Visible",
                pointsAvailable);
        commands.set(entrySelector + " #BondedTalentPointCountShadow.Visible",
                pointsAvailable);
        commands.set(entrySelector + " #BondedTalentPointCount.Visible",
                pointsAvailable);
        String points = pointsAvailable
                ? Integer.toString(progression.availablePoints()) : "";
        commands.set(entrySelector + " #BondedTalentPointCount.Text", points);
        commands.set(entrySelector + " #BondedTalentPointCountShadow.Text", points);
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
        commands.set(entrySelector + " #BondedStateInWorld.Text", copy.label());
        commands.set(entrySelector + " #BondedStateStored.Text", copy.label());
        commands.set(entrySelector + " #BondedStateDead.Text", copy.label());
        commands.set(entrySelector + " #BondedStateReady.Text", copy.label());
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
        commands.set(entrySelector + " #BondedFrameInWorld.Visible",
                status.state() == BondedCompanionStateView.ACTIVE);
        commands.set(entrySelector + " #BondedFrameStored.Visible",
                status.state() == BondedCompanionStateView.STORED);
        commands.set(entrySelector + " #BondedFrameDead.Visible",
                status.state() == BondedCompanionStateView.DEAD
                        && !copy.reviveReady());
        commands.set(entrySelector + " #BondedFrameReady.Visible",
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
                fillAnchor(1, 1, (int) Math.round(HEALTH_FILL_WIDTH
                        * current / maximum), 16));
    }

    private static void bindXpProgress(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            ProgressionSummary progression,
            @Nullable String language
    ) {
        Map<String, String> attributes = row.attributes();
        TwLevelingConfig config = TwLevelingConfig.resolveById(
                attributes.get("levelingConfigId"));
        boolean visible = progression.visible() && config != null && config.isEnabled();
        commands.set(entrySelector + " #BondedXpFrame.Visible", visible);
        commands.set(entrySelector + " #BondedXpButton.Visible", visible);
        if (!visible) {
            return;
        }
        int maxLevel = Math.max(1, config.getLevels().getMaxLevel());
        int level = Math.min(progression.level(), maxLevel);
        int requiredXp = (int) Math.max(1L, Math.round(
                config.getLevels().getBaseXp()
                        * Math.pow(config.getLevels().getGrowthFactor(), level - 1)));
        int currentXp = nonNegativeRoundedInt(attributes.get("currentXp"));
        int width = level >= maxLevel ? XP_FILL_WIDTH : (int) Math.round(
                XP_FILL_WIDTH * Math.min(1.0, (double) currentXp / requiredXp));
        commands.setObject(entrySelector + " #BondedXpFill.Anchor",
                fixedWidthAnchor(0, 0, width, 3));
        commands.set(entrySelector + " #BondedXpButton.TooltipText",
                progressionTooltip(progression, attributes, row.roleId(), language));
    }

    private static void bindLayout(
            UICommandBuilder commands,
            String entrySelector,
            CardLayout layout
    ) {
        commands.setObject(entrySelector + " #BondedStateDetail.Anchor",
                horizontalAnchor(20, layout.detailTop(), 150, 14));
        commands.setObject(entrySelector + " #BondedStateDetailValue.Anchor",
                horizontalAnchor(20, layout.detailTop() + 14, 150, 18));
        Anchor action = rightAnchor(layout.actionTop(), 14, 94, 28);
        commands.setObject(entrySelector + " #BondedPrimaryAction.Anchor", action);
        commands.setObject(entrySelector + " #BondedPrimaryActionNoTooltip.Anchor",
                rightAnchor(layout.actionTop(), 14, 94, 28));
        commands.setObject(entrySelector + " #BondedPrimaryActionDisabled.Anchor",
                rightAnchor(layout.actionTop(), 14, 94, 28));
        commands.setObject(entrySelector
                        + " #BondedPrimaryActionDisabledNoTooltip.Anchor",
                rightAnchor(layout.actionTop(), 14, 94, 28));
        commands.setObject(entrySelector + " #BondedReviveAction.Anchor",
                rightAnchor(layout.actionTop(), 14, 94, 28));
        commands.setObject(entrySelector + " #BondedReviveActionNoTooltip.Anchor",
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

    private static void bindProgression(
            UICommandBuilder commands,
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            ProgressionSummary progression,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        commands.set(entrySelector + " #BondedProgressionButton.Visible",
                !pendingUnlink);
        commands.set(entrySelector + " #BondedProgressionButton.TooltipText",
                progression.visible()
                        ? progressionTooltip(progression, row.attributes(), row.roleId(), language)
                        : LocalizedText.resolve(language,
                                "tamework.ui.linkedPanel.bonded.talents.tooltip"));
        bindProgressionEvents(events, entrySelector, cardUuid, progression,
                row.attributes(), pendingUnlink, config);
    }

    private static void bindProgressionEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            ProgressionSummary progression,
            Map<String, String> attributes,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        // A saved level is sufficient to inspect the durable talent page. The
        // page itself resolves a saved or role-derived tree and can explain a
        // genuinely missing configuration instead of leaving a silent button.
        boolean canOpen = !pendingUnlink;
        if (canOpen) {
            String commandValue = config.openTalentsCommandPrefix() + cardUuid;
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedProgressionButton",
                    EventData.of(config.eventCommandId(), commandValue), false);
            if (xpProgressVisible(progression, attributes)) {
                events.addEventBinding(CustomUIEventBindingType.Activating,
                        entrySelector + " #BondedXpButton",
                        EventData.of(config.eventCommandId(), commandValue), false);
            }
        }
    }

    private static boolean xpProgressVisible(
            ProgressionSummary progression,
            Map<String, String> attributes
    ) {
        TwLevelingConfig config = TwLevelingConfig.resolveById(
                attributes.get("levelingConfigId"));
        return progression.visible() && config != null && config.isEnabled();
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
        boolean tooltipVisible = !tooltip.isBlank();
        commands.set(entrySelector + " #BondedPrimaryAction.Visible",
                enabled && !revive && tooltipVisible);
        commands.set(entrySelector + " #BondedPrimaryAction.Text", label);
        if (tooltipVisible) {
            commands.set(entrySelector + " #BondedPrimaryAction.TooltipText", tooltip);
        }
        commands.set(entrySelector + " #BondedPrimaryActionNoTooltip.Visible",
                enabled && !revive && !tooltipVisible);
        commands.set(entrySelector + " #BondedPrimaryActionNoTooltip.Text", label);
        commands.set(entrySelector + " #BondedPrimaryActionDisabled.Visible",
                visible && !enabled && !pendingUnlink && tooltipVisible);
        commands.set(entrySelector + " #BondedPrimaryActionDisabled.Text", label);
        if (tooltipVisible) {
            commands.set(entrySelector + " #BondedPrimaryActionDisabled.TooltipText",
                    tooltip);
        }
        commands.set(entrySelector + " #BondedPrimaryActionDisabledNoTooltip.Visible",
                visible && !enabled && !pendingUnlink && !tooltipVisible);
        commands.set(entrySelector + " #BondedPrimaryActionDisabledNoTooltip.Text",
                label);
        commands.set(entrySelector + " #BondedReviveAction.Visible",
                enabled && revive && tooltipVisible);
        commands.set(entrySelector + " #BondedReviveAction.Text", label);
        if (tooltipVisible) {
            commands.set(entrySelector + " #BondedReviveAction.TooltipText", tooltip);
        }
        commands.set(entrySelector + " #BondedReviveActionNoTooltip.Visible",
                enabled && revive && !tooltipVisible);
        commands.set(entrySelector + " #BondedReviveActionNoTooltip.Text", label);
        bindPrimaryActionEvents(events, entrySelector, cardUuid, row,
                pendingUnlink, config, language);
    }

    private static void bindPrimaryActionEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            boolean pendingUnlink,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        BondedCompanionStatusPresentation status = row.status();
        String tooltip = actionTooltip(row, status, language);
        boolean visible = status.action() != BondedCompanionStatusPresentation.Action.NONE;
        boolean enabled = visible && status.actionEnabled() && !pendingUnlink;
        boolean revive = status.action() == BondedCompanionStatusPresentation.Action.REVIVE;
        boolean tooltipVisible = !tooltip.isBlank();
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
            String actionSelector = revive
                    ? tooltipVisible ? " #BondedReviveAction"
                    : " #BondedReviveActionNoTooltip"
                    : tooltipVisible ? " #BondedPrimaryAction"
                    : " #BondedPrimaryActionNoTooltip";
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + actionSelector,
                    EventData.of(config.eventCommandId(), command), false);
        }
    }

    private static void bindFlightToggle(
            UICommandBuilder commands,
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        bindFlightToggle(commands, entrySelector, row, language);
        bindFlightToggleEvents(events, entrySelector, cardUuid, row, config);
    }

    private static void bindFlightToggle(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        boolean visible = flightToggleVisible(row);
        boolean airborne = Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AIRBORNE));
        commands.set(entrySelector + " #BondedFlightToggleButton.Visible", visible);
        commands.set(entrySelector + " #BondedFlightModeGroundedIcon.Visible",
                visible && !airborne);
        commands.set(entrySelector + " #BondedFlightModeAirborneIcon.Visible",
                visible && airborne);
        commands.set(entrySelector + " #BondedFlightToggleButton.TooltipText",
                visible ? LocalizedText.resolve(language, airborne
                        ? "tamework.ui.linkedPanel.bonded.flight.switchToGround"
                        : "tamework.ui.linkedPanel.bonded.flight.switchToFlight") : "");
    }

    private static boolean flightToggleVisible(BondedCompanionPanelPresentation row) {
        return row.status().state() == BondedCompanionStateView.ACTIVE
                && Boolean.parseBoolean(row.attributes().get(
                        BondedCompanionPresentationAttributes.FLIGHT_TOGGLE_AVAILABLE));
    }

    static void bindFlightToggleEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        if (flightToggleVisible(row)) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedFlightToggleButton",
                    EventData.of(config.eventCommandId(),
                            config.bondedFlightToggleCommandPrefix() + cardUuid), false);
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
        String label = pluralSpecies(row.species());
        if (label.isBlank()) {
            label = attributes.get(
                    BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL);
        }
        if (limit == 0 || label == null || label.isBlank()) {
            return "";
        }
        return LocalizedText.format(language,
                "tamework.ui.linkedPanel.bonded.tooltip.summonCapacity",
                label, active, limit);
    }

    @Nonnull
    private static String pluralSpecies(@Nullable String species) {
        if (species == null || species.isBlank()) {
            return "";
        }
        String value = species.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z")
                || lower.endsWith("ch") || lower.endsWith("sh")) {
            return value + "es";
        }
        if (lower.endsWith("y") && value.length() > 1
                && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
            return value.substring(0, value.length() - 1) + "ies";
        }
        return value + "s";
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

    private static ProgressionSummary progressionSummary(
            Map<String, String> attributes,
            @Nullable String roleId
    ) {
        int level = positiveRoundedInt(attributes.get("level"), 0);
        String levelingConfig = attributes.get("levelingConfigId");
        String talentConfig = attributes.get("talentConfigId");
        if (level == 0 || levelingConfig == null || levelingConfig.isBlank()) {
            return ProgressionSummary.hidden();
        }
        int spent = nonNegativeInt(attributes.get("talentSpentPoints"));
        int earned = CompanionLevelingService.resolveEarnedTalentPoints(level,
                levelingConfig);
        boolean talentsConfigured = talentConfig != null && !talentConfig.isBlank()
                || roleId != null && !roleId.isBlank()
                && TwTalentConfig.resolveForRole(roleId) != null;
        int available = talentsConfigured ? Math.max(0, earned - spent) : 0;
        return new ProgressionSummary(true, talentsConfigured, level, available);
    }

    private static CardLayout layout(Map<String, String> attributes) {
        boolean metrics = hasMetric(attributes, "happiness")
                || hasMetric(attributes, "hunger")
                || hasMetric(attributes, "thirst");
        int metricTop = 86;
        int detailTop = metrics ? 110 : 82;
        int actionTop = detailTop + 3;
        return new CardLayout(metricTop, detailTop, actionTop);
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

    private static Anchor fixedWidthAnchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(top));
        anchor.setLeft(Value.of(left));
        anchor.setWidth(Value.of(Math.max(0, width)));
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

    private static String progressionTooltip(
            ProgressionSummary progression,
            Map<String, String> attributes,
            @Nullable String roleId,
            @Nullable String language
    ) {
        TwLevelingConfig config = TwLevelingConfig.resolveById(
                attributes.get("levelingConfigId"));
        if (config == null || !config.isEnabled()) {
            return "Level: " + progression.level();
        }
        int maxLevel = Math.max(1, config.getLevels().getMaxLevel());
        int level = Math.min(progression.level(), maxLevel);
        if (level >= maxLevel) {
            return LinkedNpcPanelProgressionBinder.resolveXpTooltip(
                    new LinkedNpcEntry.FutureStat("Level " + level + " MAX",
                            1, 1,
                            "Level: " + level + "/" + maxLevel + " - MAX XP",
                            modifierTooltip(config, level, attributes, roleId, language)));
        }
        int currentXp = nonNegativeRoundedInt(attributes.get("currentXp"));
        int requiredXp = (int) Math.max(1L, Math.round(
                config.getLevels().getBaseXp()
                        * Math.pow(config.getLevels().getGrowthFactor(), level - 1)));
        return LinkedNpcPanelProgressionBinder.resolveXpTooltip(
                new LinkedNpcEntry.FutureStat("Level " + level + " XP",
                        currentXp, requiredXp,
                        "Level: " + level + "/" + maxLevel + " - "
                                + currentXp + "/" + requiredXp + " XP",
                        modifierTooltip(config, level, attributes, roleId, language)));
    }

    @Nullable
    private static String modifierTooltip(
            TwLevelingConfig config,
            int level,
            Map<String, String> attributes,
            @Nullable String roleId,
            @Nullable String language
    ) {
        return LinkedNpcPanelProgressionBinder.resolveSavedModifierTooltip(
                config, level, attributes.get("talentConfigId"),
                attributes.get("talents"), attributes.get("traitConfigId"),
                roleId, attributes.get("traits"),
                nonNegativeDouble(attributes.get("maxHealth")), language);
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

    private static double nonNegativeDouble(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed >= 0.0 ? parsed : 0.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static void bindShoulderRide(
            UICommandBuilder commands,
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            @Nullable String language
    ) {
        bindShoulderRide(commands, entrySelector, row, language);
        bindShoulderRideEvents(events, entrySelector, cardUuid, row, config);
    }

    private static void bindShoulderRide(
            UICommandBuilder commands,
            String entrySelector,
            BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        boolean visible = shoulderRideVisible(row);
        boolean mounted = Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.SHOULDER_RIDE_MOUNTED));
        commands.set(entrySelector + " #BondedShoulderRideButton.Visible", visible);
        commands.set(entrySelector + " #BondedShoulderRideIcon.Visible", visible);
        commands.set(entrySelector + " #BondedShoulderRideButton.Text", "");
        commands.set(entrySelector + " #BondedShoulderRideButton.TooltipText",
                visible ? LocalizedText.resolve(language, mounted
                        ? "tamework.ui.linkedPanel.bonded.shoulder.down.tooltip"
                        : "tamework.ui.linkedPanel.bonded.shoulder.toMe.tooltip") : "");
    }

    private static boolean shoulderRideVisible(
            BondedCompanionPanelPresentation row) {
        return row.status().state() == BondedCompanionStateView.ACTIVE
                && Boolean.parseBoolean(row.attributes().get(
                BondedCompanionPresentationAttributes.SHOULDER_RIDE_AVAILABLE));
    }

    static void bindShoulderRideEvents(
            UIEventBuilder events,
            String entrySelector,
            UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config
    ) {
        if (shoulderRideVisible(row)) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    entrySelector + " #BondedShoulderRideButton",
                    EventData.of(config.eventCommandId(),
                            CommandSelectionPageEventBinder
                                    .BONDED_SHOULDER_RIDE_COMMAND_PREFIX
                                    + cardUuid), false);
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

    private record ProgressionSummary(
            boolean visible,
            boolean talentsConfigured,
            int level,
            int availablePoints
    ) {
        private static ProgressionSummary hidden() {
            return new ProgressionSummary(false, false, 0, 0);
        }

    }

    /** Compact vertical allocation that omits absent metrics. */
    private record CardLayout(int metricTop, int detailTop, int actionTop) {
        private int baseHeight() {
            return actionTop + 36;
        }

        private Anchor cardAnchor() {
            return fillAnchor(0, 3, 0, baseHeight());
        }
    }
}
