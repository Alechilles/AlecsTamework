package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.items.BondedCompanionActionFeedbackMapper;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Binds roster and paid-revival presentation for one panel card: a state caption, a bold line for a
 * running timer or the revival status, and the active count of a roster with an active limit.
 */
final class LinkedNpcPanelFeatureBinder {
    private static final String CARD_UI = "TameworkLinkedNpcPanelCard.ui";

    private LinkedNpcPanelFeatureBinder() {
    }

    static void bind(
            UICommandBuilder builder,
            UIEventBuilder events,
            String entrySelector,
            UUID npcUuid,
            @Nullable CommandPanelFeaturePresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            String language
    ) {
        String stateSelector = entrySelector + " #RosterState";
        String timerSelector = entrySelector + " #RosterTimer";
        String capacitySelector = entrySelector + " #RosterCapacity";
        String summonSelector = entrySelector + " #RosterSummonButton";
        String dismissSelector =
                entrySelector + " #RosterDismissButton";
        boolean visible = row != null && (row.roster() != null || row.bonded() != null);
        if (!visible) {
            builder.set(stateSelector + ".Visible", false);
            builder.set(timerSelector + ".Visible", false);
            builder.set(capacitySelector + ".Visible", false);
            builder.set(summonSelector + ".Visible", false);
            builder.set(dismissSelector + ".Visible", false);
            return;
        }
        if (row.bonded() != null) {
            builder.set(stateSelector + ".Visible", true);
            builder.set(timerSelector + ".Visible", true);
            builder.set(capacitySelector + ".Visible", true);
            bindBonded(builder, events, npcUuid, row.bonded(), config,
                    stateSelector, timerSelector, capacitySelector,
                    summonSelector, dismissSelector, language);
            return;
        }
        CommandRosterStatusPresentation roster = row.roster();
        builder.set(summonSelector + ".Visible", roster.summonVisible());
        builder.set(dismissSelector + ".Visible", roster.dismissVisible());
        // The caption and bold line match the status text of generic cards (#InlineLocation). The
        // styles and anchors are set here so bonded rows keep the defaults of the shared labels.
        String status = statusLine(row, language);
        String capacity = capacityLine(roster, language);
        builder.set(stateSelector + ".Visible", true);
        builder.set(stateSelector + ".Text", stateText(roster, language));
        builder.set(stateSelector + ".Style", Value.ref(CARD_UI, "RosterCaption"));
        builder.setObject(stateSelector + ".Anchor", LinkedNpcPanelCardBinder.fixedAnchor(32, 432, 284, 16));
        builder.set(timerSelector + ".Visible", !status.isEmpty());
        builder.set(timerSelector + ".Text", status);
        builder.set(timerSelector + ".Style", Value.ref(CARD_UI, "RosterStatus"));
        builder.setObject(timerSelector + ".Anchor", LinkedNpcPanelCardBinder.fixedAnchor(48, 432, 284, 18));
        builder.set(capacitySelector + ".Visible", !capacity.isEmpty());
        builder.set(capacitySelector + ".Text", capacity);
        builder.set(capacitySelector + ".Style",
                Value.ref(CARD_UI, roster.capBlocked() ? "RosterCapacityFull" : "RosterCapacity"));
        builder.setObject(capacitySelector + ".Anchor",
                LinkedNpcPanelCardBinder.fixedAnchor(status.isEmpty() ? 48 : 68, 432, 284, 14));
        if (roster.summonEnabled()) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    summonSelector,
                    EventData.of(
                            config.eventCommandId(),
                            config.summonCommandPrefix() + npcUuid
                    ),
                    false
            );
        }
        if (roster.dismissEnabled()) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    dismissSelector,
                    EventData.of(
                            config.eventCommandId(),
                            config.dismissCommandPrefix() + npcUuid
                    ),
                    false
            );
        }
    }

    static boolean paidReviveVisible(
            @Nullable CommandPanelFeaturePresentation row
    ) {
        return row != null
                && row.managesPaidRevival()
                && (row.bonded() != null
                        ? row.bonded().status().action()
                                == BondedCompanionStatusPresentation.Action.REVIVE
                                && row.bonded().status().actionEnabled()
                        : row.revival() != null
                                && row.revival().actionVisible());
    }

    private static void bindBonded(
            UICommandBuilder builder, UIEventBuilder events, UUID cardUuid,
            BondedCompanionPanelPresentation row,
            LinkedNpcPanelCardBinder.CardBindingConfig config,
            String stateSelector, String detailSelector, String reasonSelector,
            String summonSelector, String dismissSelector, String language) {
        BondedCompanionStatusPresentation status = row.status();
        builder.set(stateSelector + ".Text", bondedStateText(status, language));
        builder.set(detailSelector + ".Text", bondedDetailText(row, language));
        builder.set(reasonSelector + ".Text", status.blockReason() == null
                ? "" : BondedCompanionActionFeedbackMapper.resolve(
                        language, status.blockReason()));
        boolean summon = status.action()
                == BondedCompanionStatusPresentation.Action.SUMMON;
        boolean dismiss = status.action()
                == BondedCompanionStatusPresentation.Action.DISMISS;
        builder.set(summonSelector + ".Visible", summon);
        builder.set(dismissSelector + ".Visible", dismiss);
        if (summon && status.actionEnabled()) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    summonSelector, EventData.of(config.eventCommandId(),
                            config.summonCommandPrefix() + cardUuid), false);
        }
        if (dismiss && status.actionEnabled()) {
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    dismissSelector, EventData.of(config.eventCommandId(),
                            config.dismissCommandPrefix() + cardUuid), false);
        }
    }

    /** The state caption of a bonded row, in the viewer's language; the roster state texts are shared. */
    static String bondedStateText(BondedCompanionStatusPresentation status, String language) {
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.roster.state." + switch (status.state()) {
            case STORED -> "stored";
            case ACTIVE -> "active";
            case DEAD -> "dead";
        });
    }

    /**
     * The detail line of a bonded row for the viewer: species, role presentation, level and
     * health. Every other attribute is either shown elsewhere on the card or is an internal key,
     * and extension values are other mods' data, so none of them is printed.
     */
    static String bondedDetailText(BondedCompanionPanelPresentation row, String language) {
        java.util.ArrayList<String> details = new java.util.ArrayList<>();
        if (row.species() != null) details.add(row.species());
        if (row.rolePresentation() != null
                && !row.rolePresentation().equalsIgnoreCase(row.species())) {
            details.add(row.rolePresentation());
        }
        Long level = wholeNumber(row.attributes().get("level"));
        if (level != null) {
            details.add(LocalizedText.format(language, "tamework.ui.talents.requirement.level", level));
        }
        Long health = wholeNumber(row.attributes().get("currentHealth"));
        Long maxHealth = wholeNumber(row.attributes().get("maxHealth"));
        if (health != null && maxHealth != null && maxHealth > 0L) {
            details.add(LocalizedText.format(language, "tamework.ui.commandTargetHud.health.value", health, maxHealth));
        }
        return String.join(" | ", details);
    }

    /** A presentation value rounded to a whole number; null when it is absent or not a number. */
    @Nullable
    private static Long wholeNumber(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? Math.round(parsed) : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String stateText(
            CommandRosterStatusPresentation roster,
            String language
    ) {
        String stateKey = switch (roster.state()) {
            case ACTIVE -> "active";
            case UNLOADED -> "unloaded";
            case UNAVAILABLE -> "unavailable";
            case RESTORING -> "restoring";
            case STORING -> "storing";
            case ROSTER_STORED -> "stored";
            case DEAD_REVIVABLE -> "dead";
            case LOST -> "lost";
        };
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.roster.state." + stateKey);
    }

    /**
     * The bold line under the state caption: the revival status of a dead or lost member, else the
     * running summon time or summon cooldown. Empty when no timer is running; an untimed member
     * shows only its state.
     */
    static String statusLine(
            CommandPanelFeaturePresentation row,
            String language
    ) {
        CommandRosterStatusPresentation roster = row.roster();
        if (row.managesPaidRevival()) {
            return row.revival() == null
                    ? LocalizedText.resolve(
                            language,
                            "tamework.ui.linkedPanel.revive.unavailable"
                    )
                    : revivalStatus(row.revival(), language);
        }
        if (roster.remainingMs() != null) {
            return LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.roster.remaining",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            roster.remainingMs(), language
                    )
            );
        }
        if (roster.cooldownRemainingMs() > 0L) {
            return LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.roster.cooldown",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            roster.cooldownRemainingMs(), language
                    )
            );
        }
        return "";
    }

    private static String revivalStatus(
            CommandReviveCostPresentation revival,
            String language
    ) {
        PaidCommandRevivalQuote.Status status = revival.status();
        return switch (status) {
            case READY -> LocalizedText.resolve(
                    language,
                    "tamework.ui.linkedPanel.revive.ready"
            );
            case INSUFFICIENT_COST -> LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.revive.missingComponents",
                    revival.missingComponentCount()
            );
            case COOLDOWN -> LocalizedText.format(
                    language,
                    "tamework.ui.linkedPanel.revive.cooldown",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            revival.cooldownRemainingMs(), language
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

    /** The active count matters only for a roster with an active limit; without one this is empty. */
    static String capacityLine(
            CommandRosterStatusPresentation roster,
            String language
    ) {
        return roster.capUnlimited() ? "" : LocalizedText.format(
                language,
                "tamework.ui.linkedPanel.roster.capacity",
                roster.activeCount(),
                roster.activeLimit()
        );
    }
}
