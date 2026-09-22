package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Redraws countdown text, meter fills, and timer tooltips from an open panel snapshot. */
final class LinkedNpcPanelCountdownPresenter {
    private LinkedNpcPanelCountdownPresenter() {
    }

    static void refresh(
            UICommandBuilder commands,
            UIEventBuilder events,
            LinkedNpcEntry[] entries,
            Map<UUID, CommandPanelFeaturePresentation> features,
            Predicate<UUID> pendingUnlink,
            long elapsedMs,
            String language
    ) {
        for (int index = 0; index < entries.length; index++) {
            LinkedNpcEntry entry = entries[index];
            if (entry == null) continue;
            String selector = "#TameworkLinkedPanelList[" + index + "]";
            CommandPanelFeaturePresentation feature = features.get(entry.npcUuid());
            if (feature != null && feature.bonded() != null) {
                BondedCompanionPanelPresentation projected = project(
                        feature.bonded(), elapsedMs);
                LinkedNpcPanelCardDynamicPresenter.refresh(
                        commands, events, selector, entry.npcUuid(), entry, entry,
                        feature, CommandPanelFeaturePresentation.bonded(projected),
                        pendingUnlink.test(entry.npcUuid()), null, language);
                continue;
            }
            refreshGeneric(commands, selector, entry, elapsedMs, language);
            if (feature != null) {
                refreshRosterTimer(commands, selector, feature, elapsedMs, language);
            }
        }
    }

    private static void refreshGeneric(
            UICommandBuilder commands,
            String selector,
            LinkedNpcEntry entry,
            long elapsedMs,
            String language
    ) {
        LinkedNpcEntry.AnimalLifecycle lifecycle = entry.animalLifecycle();
        LinkedNpcEntry.AnimalLifecycle projectedLifecycle = new LinkedNpcEntry.AnimalLifecycle(
                lifecycle.stage(), lifecycle.prime(), lifecycle.frozen(),
                lifecycle.nextDeath(), lifecycle.frozen() || entry.captured()
                        ? lifecycle.remainingMs()
                        : decrease(lifecycle.remainingMs(), elapsedMs),
                lifecycle.yieldMultiplier(), lifecycle.frozen() || entry.captured()
                        ? lifecycle.stageProgress()
                        : projectedRatio(lifecycle.remainingMs(), lifecycle.stageProgress(),
                                decrease(lifecycle.remainingMs(), elapsedMs), true),
                lifecycle.nextStage());
        boolean breedingActive = (entry.loaded() || entry.breedingEnabled())
                && entry.breedingCooldownActive();
        long breedingRemaining = !entry.loaded() && !entry.breedingEnabled() ? 0L
                : entry.captured() ? entry.breedingCooldownRemainingMs()
                : decrease(entry.breedingCooldownRemainingMs(), elapsedMs);
        long harvestRemaining = entry.captured() ? entry.harvestCooldownRemainingMs()
                : decrease(entry.harvestCooldownRemainingMs(), elapsedMs);
        LinkedNpcEntry projected = entry.withCountdownProjection(
                decrease(entry.deadRespawnRemainingMs(), elapsedMs),
                breedingRemaining, projectedRatio(entry.breedingCooldownRemainingMs(),
                        entry.breedingCooldownRatio(), breedingRemaining, breedingActive),
                harvestRemaining, projectedRatio(entry.harvestCooldownRemainingMs(),
                        entry.harvestCooldownRatio(), harvestRemaining,
                        entry.harvestCooldownActive()),
                projectedLifecycle);
        if (lifecycle.active()) {
            LinkedNpcPanelCardBinder.bindLifecycle(commands, selector, projected,
                    language);
        }
        if (entry.recallPending() && !entry.loaded() && !entry.dead()
                && !entry.captured() && !entry.inCoop() && !entry.lost()) {
            commands.set(selector + " #RecallCountdown.Text", LocalizedText.format(
                    language, "tamework.ui.linkedPanel.card.recallCountdown",
                    (decrease(entry.recallLostRemainingMs(), elapsedMs) + 999L) / 1_000L));
        }
        if (entry.dead() && entry.deadRespawnRemainingMs() >= 0L) {
            commands.set(selector + " #HealthTooltip.TooltipText",
                    deadHealthTooltip(entry,
                            projected.deadRespawnRemainingMs(), language));
        }
        if (entry.breedingCooldownKnown() || entry.harvestCooldownKnown()) {
            LinkedNpcPanelVitalsBinder.bindCooldowns(commands, selector, projected,
                    language);
        }
    }

    private static String deadHealthTooltip(
            LinkedNpcEntry entry,
            long remainingMs,
            String language
    ) {
        String primary = remainingMs <= 0L
                ? LocalizedText.resolve(language,
                        "tamework.ui.linkedPanel.health.deadReadyToRespawn")
                : LocalizedText.format(language,
                        "tamework.ui.linkedPanel.health.deadRespawnIn",
                        LinkedNpcPanelStatusTextService.formatRemainingTime(remainingMs,
                                language));
        if (entry.deathCauseHint() != null && !entry.deathCauseHint().isBlank()) {
            primary += "\n" + entry.deathCauseHint();
        }
        return LinkedNpcPanelStatusTextService.appendLastKnownTooltip(primary, entry,
                language);
    }

    private static void refreshRosterTimer(
            UICommandBuilder commands,
            String selector,
            CommandPanelFeaturePresentation feature,
            long elapsedMs,
            String language
    ) {
        CommandReviveCostPresentation revival = feature.revival();
        if (feature.managesPaidRevival() && revival != null
                && revival.status() == PaidCommandRevivalQuote.Status.COOLDOWN) {
            commands.set(selector + " #RosterTimer.Text", LocalizedText.format(language,
                    "tamework.ui.linkedPanel.revive.cooldown",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            decrease(revival.cooldownRemainingMs(), elapsedMs), language)));
            return;
        }
        CommandRosterStatusPresentation roster = feature.roster();
        if (roster == null) return;
        if (roster.remainingMs() != null) {
            commands.set(selector + " #RosterTimer.Text", LocalizedText.format(language,
                    "tamework.ui.linkedPanel.roster.remaining",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            decrease(roster.remainingMs(), elapsedMs), language)));
        } else if (roster.cooldownRemainingMs() > 0L) {
            commands.set(selector + " #RosterTimer.Text", LocalizedText.format(language,
                    "tamework.ui.linkedPanel.roster.cooldown",
                    LinkedNpcPanelStatusTextService.formatRemainingTime(
                            decrease(roster.cooldownRemainingMs(), elapsedMs), language)));
        }
    }

    private static BondedCompanionPanelPresentation project(
            BondedCompanionPanelPresentation row,
            long elapsedMs
    ) {
        BondedCompanionStatusPresentation status = row.status();
        BondedCompanionStatusPresentation projectedStatus =
                new BondedCompanionStatusPresentation(status.state(), status.action(),
                        status.actionEnabled(), status.blockReason(),
                        status.unavailableReason(),
                        decrease(status.cooldownRemainingMs(), elapsedMs));
        Map<String, String> attributes = new HashMap<>(row.attributes());
        if (attributes.containsKey("sessionRemainingMs")) {
            attributes.put("sessionRemainingMs", Long.toString(decrease(
                    parseNonNegative(attributes.get("sessionRemainingMs")), elapsedMs)));
        }
        BondedCompanionReviveQuote quote = row.reviveQuote();
        if (quote != null) {
            long remainingMs = decrease(quote.cooldownRemainingSeconds() * 1_000L,
                    elapsedMs);
            quote = new BondedCompanionReviveQuote(quote.profileId(), quote.enabled(),
                    quote.costs(), (remainingMs + 999L) / 1_000L,
                    quote.policyRevision());
        }
        return new BondedCompanionPanelPresentation(row.profileId(), row.rosterId(),
                row.roleId(), row.revision(), row.displayName(), row.species(),
                row.gender(), row.rolePresentation(), attributes, row.extensions(),
                projectedStatus, quote);
    }

    private static long decrease(long value, long elapsedMs) {
        if (value < 0L || value == Long.MAX_VALUE) return value;
        return Math.max(0L, value - Math.max(0L, elapsedMs));
    }

    private static double projectedRatio(
            long originalRemainingMs,
            double originalRatio,
            long projectedRemainingMs,
            boolean active
    ) {
        if (!active || originalRemainingMs <= 0L || projectedRemainingMs < 0L
                || !(originalRatio >= 0.0 && originalRatio < 1.0)) {
            return originalRatio;
        }
        double total = originalRemainingMs / (1.0 - originalRatio);
        if (!(total > 0.0) || !Double.isFinite(total)) return originalRatio;
        return Math.clamp(1.0 - projectedRemainingMs / total, 0.0, 1.0);
    }

    private static long parseNonNegative(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

}
