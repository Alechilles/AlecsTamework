package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.UUID;
import javax.annotation.Nullable;

/** Binds timed-roster and paid-revival presentation for one panel card. */
final class LinkedNpcPanelFeatureBinder {
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
        boolean visible = row != null;
        builder.set(stateSelector + ".Visible", visible);
        builder.set(timerSelector + ".Visible", visible);
        builder.set(capacitySelector + ".Visible", visible);
        builder.set(
                summonSelector + ".Visible",
                visible && row.roster().summonVisible()
        );
        builder.set(
                dismissSelector + ".Visible",
                visible && row.roster().dismissVisible()
        );
        if (!visible) {
            return;
        }
        CommandRosterStatusPresentation roster = row.roster();
        builder.set(stateSelector + ".Text", stateText(roster, language));
        builder.set(timerSelector + ".Text", timerText(row, language));
        builder.set(
                capacitySelector + ".Text",
                capacityText(roster, language)
        );
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
                && row.revival() != null
                && row.revival().actionVisible();
    }

    private static String stateText(
            CommandRosterStatusPresentation roster,
            String language
    ) {
        String stateKey = switch (roster.state()) {
            case ACTIVE -> "active";
            case UNLOADED -> "unloaded";
            case RESTORING -> "restoring";
            case STORING -> "storing";
            case ROSTER_STORED -> "stored";
            case DEAD_REVIVABLE -> "dead";
            case LOST -> "lost";
        };
        return LocalizedText.format(
                language,
                "tamework.ui.linkedPanel.roster.state",
                LocalizedText.resolve(
                        language,
                        "tamework.ui.linkedPanel.roster.state." + stateKey
                )
        );
    }

    private static String timerText(
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
        return LocalizedText.format(
                language,
                "tamework.ui.linkedPanel.roster.duration",
                roster.unlimitedDuration()
                        ? LocalizedText.resolve(
                                language,
                                "tamework.ui.linkedPanel.roster.unlimited"
                        )
                        : LinkedNpcPanelStatusTextService
                        .formatRemainingTime(
                                roster.configuredDurationMs(), language
                        )
        );
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

    private static String capacityText(
            CommandRosterStatusPresentation roster,
            String language
    ) {
        return roster.capUnlimited()
                ? LocalizedText.format(
                        language,
                        "tamework.ui.linkedPanel.roster.capacityUnlimited",
                        roster.activeCount()
                )
                : LocalizedText.format(
                        language,
                        "tamework.ui.linkedPanel.roster.capacity",
                        roster.activeCount(),
                        roster.activeLimit()
                );
    }
}
