package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.Map;
import javax.annotation.Nullable;

/** Resolves the immutable lifecycle label and detail text for a bonded card. */
final class BondedCompanionCardStatePresentation {
    private BondedCompanionCardStatePresentation() {
    }

    static StateCopy resolve(
            BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        BondedCompanionStatusPresentation status = row.status();
        return switch (status.state()) {
            case ACTIVE -> new StateCopy(text(language, "state.inWorld"),
                    text(language, "caption.summoned"),
                    activeDetail(row.attributes(), language), false);
            case STORED -> storedCopy(status, language);
            case DEAD -> deadCopy(status, row.reviveQuote(), language);
        };
    }

    private static StateCopy storedCopy(
            BondedCompanionStatusPresentation status,
            @Nullable String language
    ) {
        String label = text(language, "state.stored");
        if (status.cooldownRemainingMs() > 0L) {
            return new StateCopy(label, text(language, "caption.summonAvailableIn"),
                    remaining(status.cooldownRemainingMs(), language), false);
        }
        if (status.actionEnabled()) {
            return new StateCopy(label, text(language, "detail.readyToSummon"),
                    "", false);
        }
        return new StateCopy(label, "", "", false);
    }

    private static StateCopy deadCopy(
            BondedCompanionStatusPresentation status,
            @Nullable BondedCompanionReviveQuote quote,
            @Nullable String language
    ) {
        long cooldown = reviveCooldownMs(status, quote);
        if (cooldown > 0L) {
            return new StateCopy(text(language, "state.dead"),
                    text(language, "caption.reviveAvailableIn"),
                    remaining(cooldown, language), false);
        }
        if (status.actionEnabled()) {
            return new StateCopy(text(language, "state.ready"),
                    text(language, "caption.reviveReady"),
                    text(language, "detail.reviveReady"), true);
        }
        return new StateCopy(text(language, "state.dead"), "", "", false);
    }

    private static String activeDetail(
            Map<String, String> attributes,
            @Nullable String language
    ) {
        long remaining = nonNegativeLong(attributes.get("sessionRemainingMs"));
        if (remaining == 0L) {
            return text(language, "detail.atYourSide");
        }
        return LocalizedText.format(language, key("value.remaining"),
                remaining(remaining, language));
    }

    private static long reviveCooldownMs(
            BondedCompanionStatusPresentation status,
            @Nullable BondedCompanionReviveQuote quote
    ) {
        long quoteMs = quote == null ? 0L
                : Math.max(0L, quote.cooldownRemainingSeconds()) * 1_000L;
        return Math.max(status.cooldownRemainingMs(), quoteMs);
    }

    private static String remaining(long millis, @Nullable String language) {
        return LinkedNpcPanelStatusTextService.formatRemainingTime(millis, language);
    }

    private static String text(@Nullable String language, String suffix) {
        return LocalizedText.resolve(language, key(suffix));
    }

    private static String key(String suffix) {
        return "tamework.ui.linkedPanel.bonded." + suffix;
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

    record StateCopy(String label, String detail, String detailValue,
                     boolean reviveReady) {
    }
}
