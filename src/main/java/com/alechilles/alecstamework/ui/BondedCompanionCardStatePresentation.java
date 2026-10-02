package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionNames;
import com.alechilles.alecstamework.companion.bonded.BondedRecords;
import com.alechilles.alecstamework.items.BondedCompanionActionFeedbackMapper;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves the status column of a bonded card: one sentence that says what is going on, an
 * optional second warning line, and an optional timer bar. Each sentence is one translation with
 * placeholders for its values.
 */
final class BondedCompanionCardStatePresentation {
    private static final String PREFIX = "tamework.ui.linkedPanel.bonded.status.";

    private BondedCompanionCardStatePresentation() {
    }

    /** Which timer bar the status shows. */
    enum Timer { NONE, SESSION, COOLDOWN }

    /**
     * @param text      the status sentence; blank only when nothing is known
     * @param warning   true when the sentence explains why the action is not possible
     * @param secondary a second warning line, or blank
     * @param timer     the bar under the sentence
     * @param ratio     the remaining share the bar shows, 0 to 1
     */
    record Status(String text, boolean warning, String secondary, Timer timer, double ratio) {
        private static Status line(String text, boolean warning) {
            return new Status(text, warning, "", Timer.NONE, 0D);
        }
    }

    static Status resolve(BondedCompanionPanelPresentation row, @Nullable String language) {
        return switch (row.status().state()) {
            case ACTIVE -> active(row, language);
            case STORED -> stored(row, language);
            case DEAD -> dead(row, language);
        };
    }

    /**
     * Whether the primary button is shown and takes a click. Summon and Dismiss need an enabled
     * action. Revive also opens the cost page, which blocks its own Confirm, when the only thing
     * missing is the cost items; a revive blocked by a cooldown, a full family or a stale snapshot
     * has no button, because the cost page could not explain it.
     */
    static boolean primaryActionAvailable(BondedCompanionPanelPresentation row) {
        BondedCompanionStatusPresentation status = row.status();
        if (status.action() == BondedCompanionStatusPresentation.Action.NONE) {
            return false;
        }
        if (status.actionEnabled()) {
            return true;
        }
        BondedCompanionReviveQuote quote = row.reviveQuote();
        return status.action() == BondedCompanionStatusPresentation.Action.REVIVE
                && status.blockReason() == BondedCompanionActionBlockReason.PAYMENT_UNAVAILABLE
                && quote != null && quote.enabled() && reviveCooldownMs(row) == 0L
                && !quote.affordable() && !activeCapacityReached(row.attributes());
    }

    /** {@code m:ss} under an hour, the shared longer format from an hour up. */
    static String clock(long remainingMs, @Nullable String language) {
        long seconds = remainingMs <= 0L ? 0L : 1L + (remainingMs - 1L) / 1_000L;
        if (seconds >= 3_600L) {
            return LinkedNpcPanelStatusTextService.formatRemainingTime(remainingMs, language);
        }
        return (seconds / 60L) + ":" + (seconds % 60L < 10L ? "0" : "") + (seconds % 60L);
    }

    private static Status active(BondedCompanionPanelPresentation row, @Nullable String language) {
        long remaining = nonNegativeLong(row.attributes().get("sessionRemainingMs"));
        if (remaining > 0L) {
            return new Status(LocalizedText.format(language, PREFIX + "sessionEndsIn", clock(remaining, language)),
                    false, "", Timer.SESSION,
                    ratio(remaining, nonNegativeLong(row.attributes().get("sessionDurationMs"))));
        }
        if (!row.status().actionEnabled()) {
            String reason = reason(row.status(), language);
            if (!reason.isEmpty()) {
                return Status.line(reason, true);
            }
        }
        return Status.line(LocalizedText.resolve(language, PREFIX + "active"), false);
    }

    private static Status stored(BondedCompanionPanelPresentation row, @Nullable String language) {
        BondedCompanionStatusPresentation status = row.status();
        if (status.cooldownRemainingMs() > 0L) {
            return new Status(LocalizedText.format(language, PREFIX + "summonIn",
                    clock(status.cooldownRemainingMs(), language)), true, "", Timer.COOLDOWN,
                    ratio(status.cooldownRemainingMs(), cooldownDurationMs(row)));
        }
        if (status.actionEnabled()) {
            return Status.line(LocalizedText.resolve(language, PREFIX + "readyToSummon"), false);
        }
        if (status.blockReason() == BondedCompanionActionBlockReason.CAPACITY_REACHED) {
            String full = familyFull(row.attributes(), language);
            if (!full.isEmpty()) {
                return Status.line(full, true);
            }
        }
        return Status.line(reason(status, language), true);
    }

    private static Status dead(BondedCompanionPanelPresentation row, @Nullable String language) {
        BondedCompanionStatusPresentation status = row.status();
        long cooldown = reviveCooldownMs(row);
        if (cooldown > 0L) {
            return new Status(LocalizedText.format(language, PREFIX + "reviveIn", clock(cooldown, language)),
                    false, "", Timer.COOLDOWN, ratio(cooldown, cooldownDurationMs(row)));
        }
        if (!status.actionEnabled() && activeCapacityReached(row.attributes())) {
            // A revived companion comes back active, so a full family blocks the revive.
            String full = familyFull(row.attributes(), language);
            if (!full.isEmpty()) {
                return Status.line(full, true);
            }
        }
        if (!primaryActionAvailable(row)) {
            return Status.line(reason(status, language), true);
        }
        BondedCompanionReviveQuote quote = row.reviveQuote();
        int required = 0;
        int missing = 0;
        if (quote != null) {
            for (BondedCompanionReviveQuote.CostLine line : quote.costs()) {
                required += line.requiredQuantity();
                missing += Math.max(0, line.requiredQuantity() - line.ownedQuantity());
            }
        }
        if (required == 0) {
            return Status.line(LocalizedText.resolve(language, PREFIX + "reviveReady"), false);
        }
        String cost = counted(language, "reviveCost", required);
        // An enabled revive was already found affordable; only a blocked one names what is missing.
        return new Status(cost, false, status.actionEnabled() || missing == 0 ? ""
                : counted(language, "missingItems", missing), Timer.NONE, 0D);
    }

    /** One sentence per count form: {@code <key>.one} for exactly one item, {@code <key>.many} otherwise. */
    private static String counted(@Nullable String language, String key, int count) {
        return LocalizedText.format(language, PREFIX + key + (count == 1 ? ".one" : ".many"), count);
    }

    /**
     * The sentence for a full family: it names one active companion when the row carries one,
     * else it gives the counts. Empty when the row has no active limit.
     */
    private static String familyFull(Map<String, String> attributes, @Nullable String language) {
        int limit = nonNegativeInt(attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT));
        if (limit == 0) {
            return "";
        }
        int active = nonNegativeInt(attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT));
        String name = attributes.get(BondedRecords.ACTIVE_BLOCKER_NAME);
        if (name == null || name.isBlank()) {
            name = BondedCompanionNames.speciesLabel(null, attributes.get(BondedRecords.ACTIVE_BLOCKER_NAME_KEY),
                    attributes.get(BondedRecords.ACTIVE_BLOCKER_ROLE_ID), language);
        }
        return name == null || name.isBlank()
                ? LocalizedText.format(language, PREFIX + "familyFull", active, limit)
                : LocalizedText.format(language, PREFIX + "dismissFirst", name.trim(), active, limit);
    }

    private static String reason(BondedCompanionStatusPresentation status, @Nullable String language) {
        if (status.blockReason() != null) {
            return BondedCompanionActionFeedbackMapper.resolve(language, status.blockReason());
        }
        return status.unavailableReason() == null ? "" : status.unavailableReason();
    }

    static boolean activeCapacityReached(Map<String, String> attributes) {
        int limit = nonNegativeInt(attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT));
        return limit > 0 && nonNegativeInt(attributes.get(
                BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT)) >= limit;
    }

    private static long reviveCooldownMs(BondedCompanionPanelPresentation row) {
        BondedCompanionReviveQuote quote = row.reviveQuote();
        long quoteMs = quote == null ? 0L : Math.max(0L, quote.cooldownRemainingSeconds()) * 1_000L;
        return Math.max(row.status().cooldownRemainingMs(), quoteMs);
    }

    private static long cooldownDurationMs(BondedCompanionPanelPresentation row) {
        return nonNegativeLong(row.attributes().get(BondedRecords.COOLDOWN_DURATION_MS));
    }

    /** Older or custom profile sources may omit the total; the bar then shows an empty track. */
    private static double ratio(long remaining, long duration) {
        return duration <= 0L ? 0D : Math.min(1D, (double) remaining / duration);
    }

    private static long nonNegativeLong(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int nonNegativeInt(@Nullable String value) {
        return (int) Math.min(Integer.MAX_VALUE, nonNegativeLong(value));
    }
}
