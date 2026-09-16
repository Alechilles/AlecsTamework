package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/** Builds the compact trait strip from immutable bonded-profile state. */
final class BondedCompanionCardTraitPresentation {
    private BondedCompanionCardTraitPresentation() {
    }

    static LinkedNpcTraitIndicator[] resolve(
            BondedCompanionPanelPresentation row,
            @Nullable String language
    ) {
        TwTraitConfig config = TwTraitConfig.resolveById(
                row.attributes().get("traitConfigId"));
        if (config == null) {
            config = TwTraitConfig.resolveForRole(row.roleId());
        }
        Map<String, Double> values = values(row.attributes().get("traits"));
        if (config == null || values.isEmpty()) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        ArrayList<LinkedNpcTraitIndicator> indicators = new ArrayList<>(
                LinkedNpcTraitIndicatorBinder.MAX_VISIBLE_TRAIT_INDICATORS);
        TwHappinessConfig happinessConfig = TwHappinessConfig.resolveForRole(row.roleId());
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null || definition.getId() == null) {
                continue;
            }
            Double value = values.get(definition.getId().trim().toLowerCase(Locale.ROOT));
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            double minimum = Math.min(definition.getBreedingMin(), definition.getBreedingMax());
            double maximum = Math.max(definition.getBreedingMin(), definition.getBreedingMax());
            double baseline = clamp(definition.getDefaultValue(), minimum, maximum);
            double current = clamp(value, minimum, maximum);
            boolean belowDefault = current < baseline;
            String label = LocalizedText.resolveConfigValue(language,
                    definition.getDisplayName(), definition.getId());
            Double flatOffset = happinessConfig != null
                    && happinessConfig.getDisposition().getMode() == TwHappinessConfig.DispositionMode.FLAT
                    && "HappinessGainMultiplier".equalsIgnoreCase(definition.getEffectKey())
                    ? CompanionHappinessModifierService.resolveFlatDispositionOffset(value, happinessConfig.getDisposition())
                    : null;
            String traitTooltip = TraitDescriptionFormatter.append(label, definition, value, flatOffset, language);
            if (traitTooltip.equals(label)) {
                traitTooltip = tooltip(language, label, current, minimum, baseline, maximum, belowDefault);
            }
            indicators.add(new LinkedNpcTraitIndicator(iconGlyph(label),
                    definition.getIconPath(), label,
                    traitTooltip,
                    belowDefault ? ratio(baseline - current, baseline - minimum)
                            : ratio(current - baseline, maximum - baseline),
                    !belowDefault, belowDefault));
            if (indicators.size() >= LinkedNpcTraitIndicatorBinder.MAX_VISIBLE_TRAIT_INDICATORS) {
                break;
            }
        }
        return indicators.isEmpty() ? LinkedNpcTraitIndicator.EMPTY
                : indicators.toArray(new LinkedNpcTraitIndicator[0]);
    }

    private static Map<String, Double> values(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            try {
                double value = Double.parseDouble(part.substring(separator + 1).trim());
                if (Double.isFinite(value)) {
                    values.put(part.substring(0, separator).trim().toLowerCase(Locale.ROOT), value);
                }
            } catch (NumberFormatException ignored) {
                // One damaged stored trait must not hide the remaining saved traits.
            }
        }
        return values;
    }

    private static String tooltip(String language, String label, double value, double minimum,
                                  double baseline, double maximum,
                                  boolean belowDefault) {
        double bound = belowDefault ? minimum : maximum;
        int distance = (int) Math.round(100D * (belowDefault
                ? ratio(baseline - value, baseline - minimum)
                : ratio(value - baseline, maximum - baseline)));
        return LocalizedText.format(language, "tamework.ui.linkedPanel.trait.tooltip",
                label, format(value), format(bound),
                LocalizedText.resolve(language, belowDefault
                        ? "tamework.ui.linkedPanel.trait.minimum" : "tamework.ui.linkedPanel.trait.maximum"),
                (belowDefault && distance > 0 ? "-" : "") + distance + "%");
    }

    private static String iconGlyph(String label) {
        for (int index = 0; index < label.length(); index++) {
            char character = label.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                return String.valueOf(Character.toUpperCase(character));
            }
        }
        return "?";
    }

    private static double clamp(double value, double minimum, double maximum) {
        return !Double.isFinite(value) ? minimum : Math.max(minimum, Math.min(maximum, value));
    }

    private static double ratio(double numerator, double denominator) {
        return denominator <= 0D ? 0D : Math.max(0D, Math.min(1D, numerator / denominator));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
