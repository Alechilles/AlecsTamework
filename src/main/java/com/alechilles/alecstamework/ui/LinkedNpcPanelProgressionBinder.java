package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierBreakdownService;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Applies compact linked-panel progression indicators to UI controls.
 */
final class LinkedNpcPanelProgressionBinder {
    private static final double EPSILON = 0.000001;
    private static final List<String> PRIORITIZED_EFFECT_KEYS = List.of(
            "MaxHealthMultiplier",
            "MoveSpeedMultiplier",
            "DamageDealtMultiplier",
            "DamageTakenMultiplier",
            "HarvestDoubleDropChanceMultiplier",
            "FertilityMultiplier",
            "HappinessGainMultiplier",
            "HappinessDecayMultiplier",
            "BreedCooldownMultiplier",
            "NeedsDecayMultiplier",
            "ReviveCooldownMultiplier",
            "TraitMutationChanceMultiplier",
            "AppearanceMutationChanceMultiplier",
            "HarvestCooldownMultiplier",
            "SizeMultiplier"
    );

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
    static String resolveSavedModifierTooltip(
            TwLevelingConfig config,
            int level,
            @Nullable String talentConfigId,
            @Nullable String rawTalents,
            @Nullable String traitConfigId,
            @Nullable String roleId,
            @Nullable String rawTraits,
            double currentMaxHealth,
            @Nullable String language
    ) {
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TwTalentConfig talentConfig = TwTalentConfig.resolveById(talentConfigId);
        TwTraitConfig traitConfig = TwTraitConfig.resolveById(traitConfigId);
        if (traitConfig == null) {
            traitConfig = TwTraitConfig.resolveForRole(roleId);
        }
        String[] talentIds = splitIds(rawTalents);
        TameworkTraitsComponent traits = new TameworkTraitsComponent(
                traitConfig == null ? null : traitConfig.getId(), 0L,
                parseTraits(rawTraits));
        LinkedHashSet<String> effectKeys = new LinkedHashSet<>(PRIORITIZED_EFFECT_KEYS);
        for (TwLevelingConfig.GrowthEffect effect : config.getStatGrowth().getEffects()) {
            if (effect == null || effect.getEffectKey() == null) {
                continue;
            }
            effectKeys.add(effect.getEffectKey());
        }
        if (talentConfig != null) {
            for (String talentId : talentIds) {
                TwTalentConfig.TalentDefinition talent = talentConfig.findTalent(talentId);
                if (talent == null) {
                    continue;
                }
                for (TwTalentConfig.PassiveEffect effect : talent.getEffects()) {
                    if (effect != null && effect.getEffectKey() != null) {
                        effectKeys.add(effect.getEffectKey());
                    }
                }
            }
        }
        if (traitConfig != null) {
            for (TwTraitConfig.TraitDefinition trait : traitConfig.getTraits()) {
                if (trait != null && trait.getEffectKey() != null) {
                    effectKeys.add(trait.getEffectKey());
                }
            }
        }
        List<CompanionProgressionModifierBreakdownService.ModifierBreakdown> breakdowns =
                new ArrayList<>();
        for (String effectKey : effectKeys) {
            double levelMultiplier = resolveLevelMultiplier(config, level, effectKey);
            double talentMultiplier = CompanionTalentService.resolvePurchasedEffectMultiplier(
                    talentConfig, talentIds, effectKey, 1.0);
            double traitMultiplier = TraitModifierService.resolveMultiplier(
                    traits, traitConfig, effectKey, 1.0);
            double totalMultiplier = levelMultiplier * talentMultiplier * traitMultiplier;
            if (isNeutral(totalMultiplier) && isNeutral(levelMultiplier)
                    && isNeutral(talentMultiplier) && isNeutral(traitMultiplier)) {
                continue;
            }
            breakdowns.add(new CompanionProgressionModifierBreakdownService.ModifierBreakdown(
                    effectKey, totalMultiplier, levelMultiplier,
                    talentMultiplier, traitMultiplier));
        }
        if (breakdowns.isEmpty()) {
            return null;
        }
        String headerKey = "tamework.ui.linkedPanel.progression.modifiersBreakdown";
        String header = LocalizedText.resolve(language, headerKey);
        List<String> lines = new ArrayList<>(breakdowns.size() + 1);
        lines.add(header.equals(headerKey)
                ? "Modifiers: Total - [Level - Talents - Traits]" : header);
        for (CompanionProgressionModifierBreakdownService.ModifierBreakdown breakdown : breakdowns) {
            lines.add(formatModifierLine(breakdown, currentMaxHealth, language));
        }
        return String.join("\n", lines);
    }

    private static double resolveLevelMultiplier(
            TwLevelingConfig config,
            int level,
            String effectKey
    ) {
        int levelOffset = Math.max(0, level - 1);
        double multiplier = 1.0;
        boolean matched = false;
        for (TwLevelingConfig.GrowthEffect effect : config.getStatGrowth().getEffects()) {
            if (effect == null || effect.getEffectKey() == null
                    || !effect.getEffectKey().equalsIgnoreCase(effectKey)) {
                continue;
            }
            matched = true;
            multiplier *= Math.max(0.0, 1.0 + effect.getPerLevel() * levelOffset);
        }
        return matched ? multiplier : 1.0;
    }

    private static String[] splitIds(@Nullable String rawIds) {
        if (rawIds == null || rawIds.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(rawIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    private static TameworkTraitsComponent.TraitValue[] parseTraits(
            @Nullable String rawTraits
    ) {
        if (rawTraits == null || rawTraits.isBlank()) {
            return new TameworkTraitsComponent.TraitValue[0];
        }
        List<TameworkTraitsComponent.TraitValue> values = new ArrayList<>();
        for (String entry : rawTraits.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            String id = entry.substring(0, separator).trim();
            try {
                double value = Double.parseDouble(entry.substring(separator + 1).trim());
                if (!id.isEmpty() && Double.isFinite(value)) {
                    values.add(new TameworkTraitsComponent.TraitValue(id, value));
                }
            } catch (NumberFormatException ignored) {
                // A malformed durable value cannot contribute to the display.
            }
        }
        return values.toArray(TameworkTraitsComponent.TraitValue[]::new);
    }

    private static String formatModifierLine(
            CompanionProgressionModifierBreakdownService.ModifierBreakdown breakdown,
            double currentMaxHealth,
            @Nullable String language
    ) {
        return labelForEffectKey(breakdown.effectKey(), language)
                + ": " + formatSignedPercent(breakdown.totalMultiplier())
                + healthAbsoluteBonus(breakdown, currentMaxHealth)
                + " - [" + formatSignedPercent(breakdown.levelMultiplier())
                + " / " + formatSignedPercent(breakdown.talentMultiplier())
                + " / " + formatSignedPercent(breakdown.traitMultiplier()) + "]";
    }

    private static String healthAbsoluteBonus(
            CompanionProgressionModifierBreakdownService.ModifierBreakdown breakdown,
            double currentMaxHealth
    ) {
        if (!"MaxHealthMultiplier".equalsIgnoreCase(breakdown.effectKey())
                || !Double.isFinite(currentMaxHealth) || currentMaxHealth <= 0.0
                || Math.abs(breakdown.totalMultiplier()) <= EPSILON) {
            return "";
        }
        double baseHealth = currentMaxHealth / breakdown.totalMultiplier();
        double bonus = currentMaxHealth - baseHealth;
        if (!Double.isFinite(bonus) || Math.abs(bonus) <= EPSILON) {
            return "";
        }
        return " (" + String.format(Locale.ROOT, "%+d", Math.round(bonus)) + " HP)";
    }

    private static boolean isNeutral(double multiplier) {
        return !Double.isFinite(multiplier) || Math.abs(multiplier - 1.0) <= EPSILON;
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
