package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Produces the complete, read-only happiness explanation from the same inputs as
 * the equilibrium calculation. Active effects are separated so renderers do not
 * need to infer a band from a label or accidentally list it twice.
 */
public final class CompanionHappinessPresentationService {
    private static final double EPSILON = 0.000001D;

    private CompanionHappinessPresentationService() {
    }

    @Nullable
    public static PresentationSnapshot resolve(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String roleId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        CompanionHappinessService.HappinessSnapshot snapshot =
                CompanionHappinessService.resolveSnapshot(npcRef, store);
        if (snapshot == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType == null ? null : store.getComponent(npcRef, happinessType);
        TwHappinessConfig config = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        TwFoodConfig.ResolvedFoodProfile foodProfile = TwFoodConfig.resolveProfileForRole(roleId);
        return resolve(npcRef, store, config, foodProfile, snapshot);
    }

    @Nonnull
    public static PresentationSnapshot resolve(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable TwHappinessConfig happinessConfig,
                                               @Nullable TwFoodConfig.ResolvedFoodProfile foodProfile,
                                               @Nonnull CompanionHappinessService.HappinessSnapshot snapshot) {
        List<EffectEntry> active = new ArrayList<>();
        Set<String> activeIds = new HashSet<>();
        double caretaking = 0.0;
        for (CompanionHappinessModifierService.ModifierEntry modifier : snapshot.modifiers()) {
            if (modifier == null || !Double.isFinite(modifier.value())) {
                continue;
            }
            if (isCaretakingModifier(modifier.id())) {
                caretaking += modifier.value();
                continue;
            }
            addActive(active, activeIds, modifier.id(), modifier.label(), modifier.value(), EffectKind.EQUILIBRIUM);
        }
        if (Double.isFinite(caretaking) && Math.abs(caretaking) > EPSILON) {
            addActive(active, activeIds, "caretaking", "Caretaking", caretaking, EffectKind.EQUILIBRIUM);
        }
        for (CompanionHappinessService.ActiveImpulseSnapshot impulse : snapshot.activeImpulses()) {
            if (impulse == null || !Double.isFinite(impulse.value()) || Math.abs(impulse.value()) <= EPSILON) {
                continue;
            }
            String id = foodEffectId(impulse.itemId());
            EffectKind kind = EffectKind.FOOD;
            if (id == null) {
                id = impulseEffectId(impulse.key());
                kind = EffectKind.IMPULSE;
            }
            addActive(active, activeIds, id, impulse.label(), impulse.value(), kind);
        }

        List<EffectEntry> inactive = new ArrayList<>();
        double disposition = HappinessConfigResolver.isRuntimeEnabled(happinessConfig)
                ? CompanionHappinessModifierService.resolveDispositionMultiplier(npcRef, store, happinessConfig)
                : 1.0;
        addInactiveNeedBands(happinessConfig, disposition, activeIds, inactive, true);
        addInactiveNeedBands(happinessConfig, disposition, activeIds, inactive, false);
        addInactivePopulationBands(happinessConfig, disposition, activeIds, inactive);
        addInactiveTimedEffects(happinessConfig, disposition, activeIds, inactive);
        addInactiveFoodEffects(happinessConfig, foodProfile, disposition, activeIds, inactive);
        return new PresentationSnapshot(
                snapshot.value(), snapshot.min(), snapshot.max(), snapshot.baseSetpoint(), snapshot.target(),
                List.copyOf(active), List.copyOf(inactive),
                HappinessConfigResolver.isRuntimeEnabled(happinessConfig)
                        && happinessConfig.getImpulses().isSingleFoodEffect()
        );
    }

    private static void addInactiveNeedBands(@Nullable TwHappinessConfig happinessConfig,
                                             double disposition,
                                             @Nonnull Set<String> activeIds,
                                             @Nonnull List<EffectEntry> inactive,
                                             boolean hunger) {
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            return;
        }
        TwHappinessConfig.NeedModifierSettings settings = hunger
                ? happinessConfig.getModifiers().getHunger()
                : happinessConfig.getModifiers().getThirst();
        if (settings == null || !settings.isEnabled()) {
            return;
        }
        String prefix = hunger ? "hunger" : "thirst";
        String labelPrefix = hunger ? "Hunger" : "Thirst";
        for (TwHappinessConfig.NeedBandSettings band : settings.getBands()) {
            if (band == null || !Double.isFinite(band.getOffset())) {
                continue;
            }
            String suffix = fallbackBandLabel(band.getLabel(), band.getId());
            String id = prefix + "_" + normalizeBandToken(suffix);
            if (activeIds.contains(id)) {
                continue;
            }
            double value = CompanionHappinessModifierService.applyDispositionToOffset(
                    band.getOffset(), disposition);
            inactive.add(new EffectEntry(id, labelPrefix + ": " + suffix, value, EffectKind.EQUILIBRIUM));
        }
    }

    private static void addInactivePopulationBands(@Nullable TwHappinessConfig happinessConfig,
                                                   double disposition,
                                                   @Nonnull Set<String> activeIds,
                                                   @Nonnull List<EffectEntry> inactive) {
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            return;
        }
        TwHappinessConfig.PopulationModifierSettings settings = happinessConfig.getModifiers().getPopulation();
        if (settings == null || !settings.isEnabled() || settings.getRadius() <= 0.0) {
            return;
        }
        for (TwHappinessConfig.PopulationBandSettings band : settings.getBands()) {
            if (band == null || !Double.isFinite(band.getOffset())) {
                continue;
            }
            String suffix = fallbackBandLabel(band.getLabel(), band.getId());
            String id = "population_" + normalizeBandToken(suffix);
            if (activeIds.contains(id)) {
                continue;
            }
            inactive.add(new EffectEntry(
                    id,
                    "Population: " + suffix,
                    CompanionHappinessModifierService.applyDispositionToOffset(band.getOffset(), disposition),
                    EffectKind.EQUILIBRIUM
            ));
        }
    }

    private static void addInactiveTimedEffects(@Nullable TwHappinessConfig happinessConfig,
                                                double disposition,
                                                @Nonnull Set<String> activeIds,
                                                @Nonnull List<EffectEntry> inactive) {
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            return;
        }
        TwHappinessConfig.ImpulseSettings impulses = happinessConfig.getImpulses();
        addInactiveTimedEffect("impulse_hand_feed", "Hand-fed", impulses.getGainOnFeed(), disposition, activeIds, inactive);
        addInactiveTimedEffect("impulse_pet", "Petted", impulses.getGainOnPet(), disposition, activeIds, inactive);
        double damage = impulses.getLoseOnDamage();
        addInactiveTimedEffect("impulse_damage", "Attacked", damage > 0.0 ? -damage : damage,
                disposition, activeIds, inactive);
    }

    private static void addInactiveTimedEffect(@Nonnull String id,
                                               @Nonnull String label,
                                               double rawValue,
                                               double disposition,
                                               @Nonnull Set<String> activeIds,
                                               @Nonnull List<EffectEntry> inactive) {
        if (activeIds.contains(id) || !Double.isFinite(rawValue) || Math.abs(rawValue) <= EPSILON) {
            return;
        }
        inactive.add(new EffectEntry(
                id, label, CompanionHappinessModifierService.applyDispositionToOffset(rawValue, disposition),
                EffectKind.IMPULSE
        ));
    }

    private static void addInactiveFoodEffects(@Nullable TwHappinessConfig happinessConfig,
                                               @Nullable TwFoodConfig.ResolvedFoodProfile foodProfile,
                                               double disposition,
                                               @Nonnull Set<String> activeIds,
                                               @Nonnull List<EffectEntry> inactive) {
        Set<String> profileFoodIds = new HashSet<>();
        if (foodProfile != null) {
            for (TwFoodConfig.FoodEntry food : foodProfile.displayEntries(true)) {
                if (food == null || !Double.isFinite(food.happinessDelta())) {
                    continue;
                }
                String id = foodEffectId(food.itemId());
                if (id == null) {
                    continue;
                }
                profileFoodIds.add(id);
                if (!activeIds.contains(id)) {
                    inactive.add(new EffectEntry(
                            id, food.itemId(),
                            CompanionHappinessModifierService.applyDispositionToOffset(
                                    food.happinessDelta(), disposition),
                            EffectKind.FOOD
                    ));
                }
            }
        }
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            return;
        }
        for (var configured : happinessConfig.getImpulses().getFeedItemImpulses().entrySet()) {
            String id = foodEffectId(configured.getKey());
            if (id == null || profileFoodIds.contains(id) || activeIds.contains(id)) {
                continue;
            }
            double value = configured.getValue() == null ? Double.NaN : configured.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            inactive.add(new EffectEntry(
                    id, configured.getKey(),
                    CompanionHappinessModifierService.applyDispositionToOffset(value, disposition),
                    EffectKind.FOOD
            ));
        }
    }

    private static void addActive(@Nonnull List<EffectEntry> active,
                                  @Nonnull Set<String> activeIds,
                                  @Nullable String id,
                                  @Nullable String label,
                                  double value,
                                  @Nonnull EffectKind kind) {
        String resolvedId = normalize(id);
        if (resolvedId.isBlank()) {
            resolvedId = "effect:" + active.size();
        }
        activeIds.add(resolvedId);
        active.add(new EffectEntry(resolvedId, blankFallback(label, "Effect"), value, kind));
    }

    @Nullable
    private static String foodEffectId(@Nullable String itemId) {
        String normalized = normalizeFoodItemId(itemId);
        return normalized.isBlank() ? null : "food_" + normalized;
    }

    private static boolean isCaretakingModifier(@Nullable String id) {
        return "hunger_care".equals(id) || "thirst_care".equals(id)
                || "population_care".equals(id) || "flat_care".equals(id);
    }

    @Nonnull
    private static String impulseEffectId(@Nullable String key) {
        String normalized = normalize(key);
        return switch (normalized) {
            case "feed_hand" -> "impulse_hand_feed";
            case "pet" -> "impulse_pet";
            case "damage" -> "impulse_damage";
            default -> "impulse_" + normalized;
        };
    }

    @Nonnull
    private static String fallbackBandLabel(@Nullable String label, @Nullable String id) {
        String resolved = blankFallback(label, id);
        return resolved.isBlank() ? "Band" : resolved;
    }

    @Nonnull
    private static String blankFallback(@Nullable String value, @Nullable String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return fallback == null ? "" : fallback.trim();
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : value.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == ' ' || c == '-' || c == '_' || c == ':') {
                out.append('_');
            }
        }
        return out.toString();
    }

    /** Mirrors {@link CompanionHappinessModifierService}'s evaluated band IDs. */
    @Nonnull
    private static String normalizeBandToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder();
        for (char c : value.trim().toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == ' ' || c == '-' || c == '_') {
                out.append('_');
            }
        }
        return out.isEmpty() ? "unknown" : out.toString();
    }

    /** Mirrors the feed-effect item canonicalization before it is made into a row ID. */
    @Nonnull
    private static String normalizeFoodItemId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        int stateIndex = normalized.indexOf("_State_");
        if (stateIndex > 0) {
            normalized = normalized.substring(0, stateIndex);
        }
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    public enum EffectKind {
        EQUILIBRIUM,
        FOOD,
        IMPULSE
    }

    public record EffectEntry(@Nonnull String id,
                              @Nonnull String label,
                              double value,
                              @Nonnull EffectKind kind) {
    }

    public record PresentationSnapshot(double current,
                                       double min,
                                       double max,
                                       double base,
                                       double target,
                                       @Nonnull List<EffectEntry> activeEffects,
                                       @Nonnull List<EffectEntry> inactiveEffects,
                                       boolean foodEffectsExclusive) {
        public PresentationSnapshot(double current,
                                    double min,
                                    double max,
                                    double base,
                                    double target,
                                    @Nonnull List<EffectEntry> activeEffects,
                                    @Nonnull List<EffectEntry> inactiveEffects) {
            this(current, min, max, base, target, activeEffects, inactiveEffects, false);
        }
    }
}
