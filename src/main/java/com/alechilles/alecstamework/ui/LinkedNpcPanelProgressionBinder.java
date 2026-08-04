package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Applies compact linked-panel progression indicators to UI controls.
 */
final class LinkedNpcPanelProgressionBinder {
    private static final double EPSILON = 0.000001;

    private LinkedNpcPanelProgressionBinder() {
    }

    static void bindXpProgressRing(UICommandBuilder commandBuilder,
                                   String ringSelector,
                                   String levelTextSelector,
                                   String tooltipSelector,
                                   LinkedNpcEntry.FutureStat stat) {
        commandBuilder.set(ringSelector + ".Visible", stat != null);
        if (stat == null) {
            return;
        }
        commandBuilder.set(levelTextSelector + ".Text", resolveLevelText(stat.label()));
        commandBuilder.set(tooltipSelector + ".TooltipText", resolveXpTooltip(stat));
        LinkedNpcPanelRingFill.SegmentFill fill = LinkedNpcPanelRingFill.resolve(progressRatio(stat.current(), stat.max()));
        commandBuilder.setObject(ringSelector + " #RingFillBar1.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar1Anchor(fill.bar1()));
        commandBuilder.setObject(ringSelector + " #RingFillBar2.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar2Anchor(fill.bar2()));
        commandBuilder.setObject(ringSelector + " #RingFillBar3.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar3Anchor(fill.bar3()));
        commandBuilder.setObject(ringSelector + " #RingFillBar4.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar4Anchor(fill.bar4()));
        commandBuilder.setObject(ringSelector + " #RingFillBar5.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar5Anchor(fill.bar5()));
    }

    static void bindTalentPointIndicator(UICommandBuilder commandBuilder,
                                         String actionSelector,
                                         String countSelector,
                                         String countShadowSelector,
                                         LinkedNpcEntry.FutureStat stat,
                                         boolean visible) {
        commandBuilder.set(actionSelector + ".Visible", visible);
        if (!visible) {
            return;
        }
        String text = Integer.toString(availableTalentPoints(stat));
        commandBuilder.set(countSelector + ".Text", text);
        commandBuilder.set(countShadowSelector + ".Text", text);
    }

    static int availableTalentPoints(LinkedNpcEntry.FutureStat stat) {
        return stat == null ? 0 : Math.max(0, stat.current());
    }

    private static String resolveLevelText(String label) {
        if (label == null || label.isBlank()) {
            return "?";
        }
        String[] parts = label.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("Level".equalsIgnoreCase(parts[i]) && parts[i + 1].matches("\\d+")) {
                return parts[i + 1];
            }
        }
        for (String part : parts) {
            if (part.matches("\\d+")) {
                return part;
            }
        }
        return "?";
    }

    static String resolveXpTooltip(LinkedNpcEntry.FutureStat stat) {
        String detail = stat.tooltipText();
        String suffix = detail == null || detail.isBlank() ? "" : "\n" + detail.trim();
        String header = stat.tooltipHeaderText();
        if (header != null && !header.isBlank()) {
            return header.trim() + suffix;
        }
        if (stat.label() != null && stat.label().toUpperCase().contains("MAX")) {
            return stat.label() + suffix;
        }
        return stat.current() + "/" + stat.max() + " XP" + suffix;
    }

    @Nullable
    static String resolveLevelBonusTooltip(
            TwLevelingConfig config,
            int level,
            @Nullable String language
    ) {
        if (config == null || !config.isEnabled()) {
            return null;
        }
        int levelOffset = Math.max(0, level - 1);
        List<String> lines = new ArrayList<>();
        for (TwLevelingConfig.GrowthEffect effect : config.getStatGrowth().getEffects()) {
            if (effect == null || effect.getEffectKey() == null) {
                continue;
            }
            double multiplier = Math.max(0.0,
                    1.0 + effect.getPerLevel() * levelOffset);
            if (Math.abs(multiplier - 1.0) <= EPSILON) {
                continue;
            }
            if (lines.isEmpty()) {
                String titleKey = "tamework.ui.linkedPanel.progression.levelBonuses";
                String title = LocalizedText.resolve(language, titleKey);
                lines.add(title.equals(titleKey) ? "Level Bonuses" : title);
            }
            lines.add(labelForEffectKey(effect.getEffectKey(), language)
                    + ": " + formatSignedPercent(multiplier));
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static String labelForEffectKey(String effectKey, @Nullable String language) {
        String normalizedKey = effectKey == null ? "" : effectKey.trim();
        if (!normalizedKey.isBlank()) {
            String languageKey = "tamework.ui.linkedPanel.progression.effect." + normalizedKey;
            String localized = LocalizedText.resolve(language, languageKey);
            if (!localized.equals(languageKey)) {
                return localized;
            }
        }
        if ("MaxHealthMultiplier".equalsIgnoreCase(effectKey)) {
            return "Health";
        }
        if ("MoveSpeedMultiplier".equalsIgnoreCase(effectKey)) {
            return "Speed";
        }
        if ("DamageDealtMultiplier".equalsIgnoreCase(effectKey)) {
            return "Damage Dealt";
        }
        if ("DamageTakenMultiplier".equalsIgnoreCase(effectKey)) {
            return "Damage Taken";
        }
        return normalizedKey.replace("Multiplier", "")
                .replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }

    private static String formatSignedPercent(double multiplier) {
        double percent = (multiplier - 1.0) * 100.0;
        if (!Double.isFinite(percent)) {
            percent = 0.0;
        }
        return String.format(Locale.ROOT, "%+d%%", (int) Math.round(percent));
    }

    private static double progressRatio(int current, int max) {
        if (max <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ((double) current) / (double) max));
    }
}
