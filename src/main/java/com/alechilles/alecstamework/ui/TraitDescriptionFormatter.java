package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import java.text.NumberFormat;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Formats per-trait effects, independently of the breeding-range indicator percentage. */
public final class TraitDescriptionFormatter {
    private TraitDescriptionFormatter() {
    }

    /** Appends a description on its own line; unknown effects without a description keep their header. */
    @Nonnull
    public static String append(@Nonnull String header, @Nonnull TwTraitConfig.TraitDefinition definition,
                                double value, @Nullable Double flatDispositionOffset,
                                @Nullable String language) {
        if (!Double.isFinite(value)) {
            return header;
        }
        String effect = definition.getEffectKey() == null ? "" : definition.getEffectKey().toLowerCase(Locale.ROOT);
        String suffix = switch (effect) {
            case "damagedealtmultiplier" -> "damageDealt";
            case "damagetakenmultiplier" -> "damageTaken";
            case "maxhealthmultiplier" -> "maxHealth";
            case "sizemultiplier" -> "size";
            case "movespeedmultiplier" -> "moveSpeed";
            case "happinessgainmultiplier" -> flatDispositionOffset == null ? "happinessGain" : "happinessFlat";
            case "fertilitymultiplier" -> "fertility";
            case "harvestdoubledropchancemultiplier" -> "harvest";
            default -> null;
        };
        String fallback = suffix == null ? "" : LocalizedText.resolve(language, "tamework.traits.description." + suffix);
        String template = LocalizedText.resolveConfigValue(language, definition.getDescription(), fallback);
        if (template.isBlank()) {
            return header;
        }
        double delta = value - 1.0;
        if ("damagetakenmultiplier".equals(effect)) {
            double inverse = value > 0.0 ? 1.0 / value : 1.0;
            delta = Double.isFinite(inverse) ? inverse - 1.0 : 0.0;
        }
        if ("harvestdoubledropchancemultiplier".equals(effect)) {
            delta = Math.max(0.0, Math.min(1.0, delta));
        }
        double points = flatDispositionOffset != null && Double.isFinite(flatDispositionOffset)
                ? flatDispositionOffset : 0.0;
        double direction = flatDispositionOffset == null ? delta : points;
        double percent = Math.abs(delta) * 100.0;
        // Round before choosing the direction so floating-point noise displays as neutral.
        double roundedDirection = Math.rint(direction * 10000.0) / 10000.0;
        String directionText = LocalizedText.resolve(language, "tamework.traits.description.direction."
                + (roundedDirection > 0 ? "increase" : roundedDirection < 0 ? "decrease" : "neutral"));
        NumberFormat numbers = NumberFormat.getNumberInstance(
                Locale.forLanguageTag(language == null || language.isBlank() ? "en-US" : language));
        numbers.setGroupingUsed(false);
        numbers.setMaximumFractionDigits(2);
        String magnitude = numbers.format(percent);
        String description = template.replace("{direction}", directionText)
                .replace("{value}", numbers.format(value))
                .replace("{percent}", magnitude)
                .replace("{signedPercent}", (delta > 0 ? "+" : delta < 0 ? "-" : "") + magnitude)
                .replace("{points}", numbers.format(Math.abs(points)));
        String result = header + "\n" + description;
        if ("sizemultiplier".equals(effect)) {
            double bonus = TraitModifierService.resolveSizeMeatHideYieldBonus(definition, value);
            String signed = (bonus > 0 ? "+" : bonus < 0 ? "-" : "")
                    + numbers.format(Math.abs(bonus) * 100.0);
            result += "\n" + LocalizedText.format(language, "tamework.traits.description.sizeYield", signed);
        }
        return result;
    }
}
