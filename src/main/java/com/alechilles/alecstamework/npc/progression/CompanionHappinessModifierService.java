package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves active equilibrium happiness modifiers (currently hunger/thirst bands) for an NPC.
 */
public final class CompanionHappinessModifierService {
    private static final double PERCENT_EPSILON = 0.000001;

    private CompanionHappinessModifierService() {
    }

    @Nonnull
    public static ModifierSnapshot resolve(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable TwHappinessConfig happinessConfig) {
        if (happinessConfig == null || !happinessConfig.isEnabled()) {
            return new ModifierSnapshot(50.0, 50.0, List.of());
        }
        double baseSetpoint = happinessConfig.getEquilibrium().getBaseSetpoint();
        ArrayList<ModifierEntry> modifiers = new ArrayList<>();
        double offsetTotal = 0.0;

        TameworkNeedsComponent needs = resolveNeedsComponent(npcRef, store);
        TwNeedsConfig needsConfig = NeedsConfigResolver.resolveConfig(npcRef, store, needs);
        if (needs != null && needsConfig != null && needsConfig.isEnabled()) {
            offsetTotal += resolveNeedOffset(
                    "hunger",
                    "Hunger",
                    happinessConfig.getModifiers().getHunger(),
                    needs.getHunger(),
                    needsConfig.getValues().getHungerMin(),
                    needsConfig.getValues().getHungerMax(),
                    modifiers
            );
            offsetTotal += resolveNeedOffset(
                    "thirst",
                    "Thirst",
                    happinessConfig.getModifiers().getThirst(),
                    needs.getThirst(),
                    needsConfig.getValues().getThirstMin(),
                    needsConfig.getValues().getThirstMax(),
                    modifiers
            );
        }

        double ownerNearbyOffset = happinessConfig.getModifiers().getOwnerNearbyOffset();
        if (Math.abs(ownerNearbyOffset) > PERCENT_EPSILON) {
            modifiers.add(new ModifierEntry("owner_nearby", "Owner Nearby", ownerNearbyOffset));
            offsetTotal += ownerNearbyOffset;
        }

        double target = baseSetpoint + offsetTotal;
        return new ModifierSnapshot(baseSetpoint, target, List.copyOf(modifiers));
    }

    @Nullable
    private static TameworkNeedsComponent resolveNeedsComponent(@Nullable Ref<EntityStore> npcRef,
                                                                @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return null;
        }
        return store.getComponent(npcRef, needsType);
    }

    private static double resolveNeedOffset(@Nonnull String idPrefix,
                                            @Nonnull String labelPrefix,
                                            @Nonnull TwHappinessConfig.NeedModifierSettings modifierSettings,
                                            double currentValue,
                                            double minValue,
                                            double maxValue,
                                            @Nonnull List<ModifierEntry> outModifiers) {
        if (!modifierSettings.isEnabled()) {
            return 0.0;
        }
        if (!Double.isFinite(currentValue) || !Double.isFinite(minValue) || !Double.isFinite(maxValue)) {
            return 0.0;
        }
        double span = maxValue - minValue;
        if (span <= 0.0) {
            return 0.0;
        }
        double percent = clamp(((currentValue - minValue) / span) * 100.0, 0.0, 100.0);
        TwHappinessConfig.NeedBandSettings band = findBand(modifierSettings, percent);
        if (band == null) {
            return 0.0;
        }
        double offset = band.getOffset();
        if (!Double.isFinite(offset) || Math.abs(offset) <= PERCENT_EPSILON) {
            return 0.0;
        }
        String suffix = band.getLabel();
        if (suffix == null || suffix.isBlank()) {
            suffix = band.getId();
        }
        if (suffix == null || suffix.isBlank()) {
            suffix = "Band";
        }
        String entryId = idPrefix + "_" + normalizeToken(suffix);
        String entryLabel = labelPrefix + ": " + suffix;
        outModifiers.add(new ModifierEntry(entryId, entryLabel, offset));
        return offset;
    }

    @Nullable
    private static TwHappinessConfig.NeedBandSettings findBand(@Nonnull TwHappinessConfig.NeedModifierSettings settings,
                                                               double percent) {
        TwHappinessConfig.NeedBandSettings[] bands = settings.getBands();
        for (TwHappinessConfig.NeedBandSettings band : bands) {
            if (band == null) {
                continue;
            }
            if (matchesBand(percent, band)) {
                return band;
            }
        }
        return null;
    }

    private static boolean matchesBand(double percent, @Nonnull TwHappinessConfig.NeedBandSettings band) {
        double min = band.getMinPercent();
        double max = band.getMaxPercent();
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        boolean upperInclusive = Math.abs(max - 100.0) <= PERCENT_EPSILON;
        if (percent < min) {
            return false;
        }
        if (upperInclusive) {
            return percent <= max;
        }
        return percent < max;
    }

    @Nonnull
    private static String normalizeToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase();
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            } else if (c == ' ' || c == '-' || c == '_') {
                builder.append('_');
            }
        }
        if (builder.length() == 0) {
            return "unknown";
        }
        return builder.toString();
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /**
     * One active equilibrium modifier contribution.
     */
    public record ModifierEntry(String id, String label, double value) {
    }

    /**
     * Effective base/target snapshot used by happiness updates and UI presentation.
     */
    public record ModifierSnapshot(double baseSetpoint, double target, List<ModifierEntry> modifiers) {
    }
}

