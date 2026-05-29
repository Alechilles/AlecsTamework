package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierBreakdownService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds linked-panel view details for companion progression, talents, traits, and stat modifiers.
 */
final class CommandLinkedPanelProgressionPresentationService {
    private static final int MAX_TRAIT_INDICATORS = 4;
    private static final double EPSILON = 0.000001;
    private static final String HEALTH_EFFECT_KEY = "MaxHealthMultiplier";
    private static final String SPEED_EFFECT_KEY = "MoveSpeedMultiplier";

    LinkedNpcEntry.FutureStat buildLevelFutureStat(@Nonnull CompanionLevelingService.LevelingSnapshot snapshot,
                                                   @Nullable String language,
                                                   @Nullable String modifierTooltip) {
        String prefix = LocalizedText.resolve(language, "tamework.ui.linkedPanel.futureStat.levelPrefix");
        if (snapshot.atMaxLevel()) {
            return new LinkedNpcEntry.FutureStat(
                    prefix + " " + snapshot.level() + " MAX",
                    1,
                    1,
                    "Level: " + snapshot.level() + "/" + snapshot.maxLevel() + " - MAX XP",
                    modifierTooltip
            );
        }
        int current = Math.max(0, (int) Math.round(snapshot.currentXp()));
        int max = Math.max(1, (int) Math.round(snapshot.nextLevelDeltaXp()));
        return new LinkedNpcEntry.FutureStat(
                prefix + " " + snapshot.level() + " XP",
                current,
                max,
                "Level: " + snapshot.level() + "/" + snapshot.maxLevel() + " - " + current + "/" + max + " XP",
                modifierTooltip
        );
    }

    LinkedNpcEntry.FutureStat buildTalentPointFutureStat(int availablePoints,
                                                         int totalEarnedPoints,
                                                         @Nullable String language) {
        String label = LocalizedText.resolve(language, "tamework.ui.linkedPanel.futureStat.talentPoints");
        return new LinkedNpcEntry.FutureStat(label, Math.max(0, availablePoints), Math.max(1, totalEarnedPoints));
    }

    @Nullable
    String buildModifierTooltip(@Nullable Ref<EntityStore> npcRef,
                                @Nullable Store<EntityStore> store,
                                @Nullable NPCEntity npc) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        List<CompanionProgressionModifierBreakdownService.ModifierBreakdown> breakdowns =
                CompanionProgressionModifierBreakdownService.resolveBreakdowns(
                        npcRef,
                        store,
                        CompanionRoleIdResolver.resolveRoleId(npcRef, store)
                );
        if (breakdowns.isEmpty()) {
            return null;
        }
        return buildModifierTooltip(
                breakdowns,
                resolveBaseHealth(npc),
                resolveBaseSpeed(npc)
        );
    }

    @Nullable
    static String buildModifierTooltip(
            @Nonnull List<CompanionProgressionModifierBreakdownService.ModifierBreakdown> breakdowns,
            double baseHealth,
            double baseSpeed) {
        if (breakdowns.isEmpty()) {
            return null;
        }
        ArrayList<String> lines = new ArrayList<>(breakdowns.size() + 1);
        lines.add("Modifiers: Total - [Level - Talents - Traits]");
        for (CompanionProgressionModifierBreakdownService.ModifierBreakdown breakdown : breakdowns) {
            if (breakdown == null) {
                continue;
            }
            lines.add(formatModifierLine(breakdown, baseHealth, baseSpeed));
        }
        return lines.size() == 1 ? null : String.join("\n", lines);
    }

    LinkedNpcTraitIndicator[] readLoadedTraitIndicators(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store) {
        return readTraitIndicators(npcRef, store, TameworkTraitsComponent.getComponentType());
    }

    private LinkedNpcTraitIndicator[] readTraitIndicators(Ref<EntityStore> npcRef,
                                                          Store<EntityStore> store,
                                                          ComponentType<EntityStore, TameworkTraitsComponent> traitType) {
        if (npcRef == null || !npcRef.isValid() || store == null || traitType == null) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        TameworkTraitsComponent traits = safeGetComponent(store, npcRef, traitType);
        if (traits == null) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        TwTraitConfig config = resolveTraitConfig(npcRef, store, traits);
        if (config == null) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        Map<String, Double> rolledValues = buildRolledValueMap(traits);
        if (rolledValues.isEmpty()) {
            return LinkedNpcTraitIndicator.EMPTY;
        }
        ArrayList<LinkedNpcTraitIndicator> indicators = new ArrayList<>(MAX_TRAIT_INDICATORS);
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null) {
                continue;
            }
            String traitId = normalize(definition.getId());
            if (traitId == null) {
                continue;
            }
            Double value = rolledValues.get(traitId);
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            double min = Math.min(definition.getBreedingMin(), definition.getBreedingMax());
            double max = Math.max(definition.getBreedingMin(), definition.getBreedingMax());
            double defaultValue = clamp(definition.getDefaultValue(), min, max);
            boolean belowDefault = value < defaultValue;
            double fillRatio = belowDefault
                    ? ratioToLowerBound(value, min, defaultValue)
                    : ratioToUpperBound(value, defaultValue, max);
            String label = resolveLabel(definition);
            indicators.add(new LinkedNpcTraitIndicator(
                    resolveIconGlyph(definition),
                    resolveIconTexturePath(definition),
                    label,
                    buildTraitTooltip(label, value, min, defaultValue, max),
                    fillRatio,
                    !belowDefault,
                    belowDefault
            ));
            if (indicators.size() >= MAX_TRAIT_INDICATORS) {
                break;
            }
        }
        return indicators.isEmpty()
                ? LinkedNpcTraitIndicator.EMPTY
                : indicators.toArray(new LinkedNpcTraitIndicator[0]);
    }

    private static String formatModifierLine(
            CompanionProgressionModifierBreakdownService.ModifierBreakdown breakdown,
            double baseHealth,
            double baseSpeed) {
        String absolute = formatAbsoluteBonus(breakdown.effectKey(), breakdown.totalMultiplier(), baseHealth, baseSpeed);
        return labelForEffectKey(breakdown.effectKey())
                + ": "
                + formatSignedPercent(breakdown.totalMultiplier())
                + absolute
                + " - ["
                + formatSignedPercent(breakdown.levelMultiplier())
                + " / "
                + formatSignedPercent(breakdown.talentMultiplier())
                + " / "
                + formatSignedPercent(breakdown.traitMultiplier())
                + "]";
    }

    private static String formatAbsoluteBonus(String effectKey, double totalMultiplier, double baseHealth, double baseSpeed) {
        if (HEALTH_EFFECT_KEY.equalsIgnoreCase(effectKey)) {
            double bonus = baseHealth * (totalMultiplier - 1.0);
            if (Double.isFinite(bonus) && Math.abs(bonus) > EPSILON) {
                return " (" + formatWholeDelta(bonus) + " HP)";
            }
        }
        if (SPEED_EFFECT_KEY.equalsIgnoreCase(effectKey)) {
            double bonus = baseSpeed * (totalMultiplier - 1.0);
            if (Double.isFinite(bonus) && Math.abs(bonus) > EPSILON) {
                return " (" + formatDecimalDelta(bonus) + " m/s)";
            }
        }
        return "";
    }

    private static String formatSignedPercent(double multiplier) {
        double percent = (multiplier - 1.0) * 100.0;
        if (!Double.isFinite(percent)) {
            percent = 0.0;
        }
        int rounded = (int) Math.round(percent);
        return String.format(Locale.ROOT, "%+d%%", rounded);
    }

    private static String labelForEffectKey(String effectKey) {
        if (HEALTH_EFFECT_KEY.equalsIgnoreCase(effectKey)) {
            return "Health";
        }
        if (SPEED_EFFECT_KEY.equalsIgnoreCase(effectKey)) {
            return "Speed";
        }
        if ("DamageDealtMultiplier".equalsIgnoreCase(effectKey)) {
            return "Damage Dealt";
        }
        if ("DamageTakenMultiplier".equalsIgnoreCase(effectKey)) {
            return "Damage Taken";
        }
        if ("HarvestDoubleDropChanceMultiplier".equalsIgnoreCase(effectKey)) {
            return "Harvest Bonus";
        }
        if ("FertilityMultiplier".equalsIgnoreCase(effectKey)) {
            return "Fertility";
        }
        if ("HappinessGainMultiplier".equalsIgnoreCase(effectKey)) {
            return "Happiness Gain";
        }
        if ("HappinessDecayMultiplier".equalsIgnoreCase(effectKey)) {
            return "Happiness Decay";
        }
        if ("BreedCooldownMultiplier".equalsIgnoreCase(effectKey)) {
            return "Breeding Cooldown";
        }
        if ("NeedsDecayMultiplier".equalsIgnoreCase(effectKey)) {
            return "Needs Decay";
        }
        if ("ReviveCooldownMultiplier".equalsIgnoreCase(effectKey)) {
            return "Revive Cooldown";
        }
        if ("TraitMutationChanceMultiplier".equalsIgnoreCase(effectKey)) {
            return "Trait Mutation";
        }
        if ("AppearanceMutationChanceMultiplier".equalsIgnoreCase(effectKey)) {
            return "Appearance Mutation";
        }
        if ("HarvestCooldownMultiplier".equalsIgnoreCase(effectKey)) {
            return "Harvest Reset";
        }
        if ("SizeMultiplier".equalsIgnoreCase(effectKey)) {
            return "Size";
        }
        return humanizeEffectKey(effectKey);
    }

    private static String humanizeEffectKey(String effectKey) {
        if (effectKey == null || effectKey.isBlank()) {
            return "Modifier";
        }
        String trimmed = effectKey.trim();
        if (trimmed.endsWith("Multiplier")) {
            trimmed = trimmed.substring(0, trimmed.length() - "Multiplier".length());
        }
        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c == '_' || c == '-' || c == ':') && !out.isEmpty()) {
                out.append(' ');
                previous = ' ';
                continue;
            }
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(previous)) {
                out.append(' ');
            }
            out.append(c);
            previous = c;
        }
        return out.toString().trim();
    }

    private static String formatWholeDelta(double value) {
        long rounded = Math.round(value);
        return rounded > 0L
                ? Long.toString(rounded)
                : String.format(Locale.ROOT, "%d", rounded);
    }

    private static String formatDecimalDelta(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.startsWith("-") ? formatted : formatted;
    }

    private static double resolveBaseHealth(@Nullable NPCEntity npc) {
        Role role = npc != null ? npc.getRole() : null;
        int initialMaxHealth = role != null ? role.getInitialMaxHealth() : 0;
        return initialMaxHealth > 0 ? initialMaxHealth : 0.0;
    }

    private static double resolveBaseSpeed(@Nullable NPCEntity npc) {
        Role role = npc != null ? npc.getRole() : null;
        MotionController motionController = role != null ? role.getActiveMotionController() : null;
        if (motionController == null) {
            return 0.0;
        }
        double maximumSpeed = motionController.getMaximumSpeed();
        return Double.isFinite(maximumSpeed) && maximumSpeed > 0.0 ? maximumSpeed : 0.0;
    }

    @Nullable
    private TwTraitConfig resolveTraitConfig(Ref<EntityStore> npcRef,
                                             Store<EntityStore> store,
                                             TameworkTraitsComponent traits) {
        String configId = traits.getConfigId();
        if (configId != null && !configId.isBlank()) {
            TwTraitConfig config = TwTraitConfig.resolveById(configId);
            if (config != null) {
                return config;
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return TwTraitConfig.resolveForRole(roleId);
    }

    private Map<String, Double> buildRolledValueMap(TameworkTraitsComponent traits) {
        HashMap<String, Double> values = new HashMap<>();
        for (TameworkTraitsComponent.TraitValue traitValue : traits.getTraitValues()) {
            if (traitValue == null) {
                continue;
            }
            String traitId = normalize(traitValue.getId());
            if (traitId == null || values.containsKey(traitId)) {
                continue;
            }
            double value = traitValue.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            values.put(traitId, value);
        }
        return values;
    }

    private String resolveIconGlyph(TwTraitConfig.TraitDefinition definition) {
        String source = resolveLabel(definition);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "?";
    }

    private String resolveIconTexturePath(TwTraitConfig.TraitDefinition definition) {
        if (definition == null) {
            return null;
        }
        String iconPath = definition.getIconPath();
        if (iconPath == null || iconPath.isBlank()) {
            return null;
        }
        return iconPath;
    }

    private String resolveLabel(TwTraitConfig.TraitDefinition definition) {
        String displayName = definition.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String id = definition.getId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return "Trait";
    }

    private double ratioToUpperBound(double value, double defaultValue, double max) {
        double distance = max - defaultValue;
        if (distance <= 0.0) {
            return 0.0;
        }
        return clamp((value - defaultValue) / distance, 0.0, 1.0);
    }

    private double ratioToLowerBound(double value, double min, double defaultValue) {
        double distance = defaultValue - min;
        if (distance <= 0.0) {
            return 0.0;
        }
        return clamp((defaultValue - value) / distance, 0.0, 1.0);
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String buildTraitTooltip(String label,
                                     double value,
                                     double min,
                                     double defaultValue,
                                     double max) {
        double safeMin = Double.isFinite(min) ? min : 0.0;
        double safeMax = Double.isFinite(max) ? max : 0.0;
        if (safeMax < safeMin) {
            double swap = safeMin;
            safeMin = safeMax;
            safeMax = swap;
        }
        double safeDefault = clamp(defaultValue, safeMin, safeMax);
        double safeValue = clamp(value, safeMin, safeMax);
        boolean belowDefault = safeValue < safeDefault;
        double normalized = belowDefault
                ? ratioToLowerBound(safeValue, safeMin, safeDefault)
                : ratioToUpperBound(safeValue, safeDefault, safeMax);
        String boundLabel = belowDefault ? "min" : "max";
        double boundValue = belowDefault ? safeMin : safeMax;
        return label
                + ": "
                + format(safeValue)
                + " / "
                + format(boundValue)
                + " "
                + boundLabel
                + " ("
                + formatPercent(normalized, belowDefault)
                + ")";
    }

    private String format(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatPercent(double ratio, boolean negativeDirection) {
        if (!Double.isFinite(ratio)) {
            return "0%";
        }
        int percent = (int) Math.round(clamp(ratio, 0.0, 1.0) * 100.0);
        if (negativeDirection && percent > 0) {
            return "-" + percent + "%";
        }
        return percent + "%";
    }

    private <T extends com.hypixel.hytale.component.Component<EntityStore>> T safeGetComponent(
            Store<EntityStore> store,
            Ref<EntityStore> npcRef,
            ComponentType<EntityStore, T> componentType) {
        if (store == null || npcRef == null || !npcRef.isValid() || componentType == null) {
            return null;
        }
        try {
            return store.getComponent(npcRef, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
