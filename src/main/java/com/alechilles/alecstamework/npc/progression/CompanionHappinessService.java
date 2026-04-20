package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies companion happiness progression updates from gameplay events.
 *
 * <p>Primary storage is {@link TameworkHappinessComponent}. Breeding state is mirrored for backward
 * compatibility while migration is in progress.
 */
public final class CompanionHappinessService {
    private static final double EPSILON = 0.000001;
    private static final double DEFAULT_MIN = 0.0;
    private static final double DEFAULT_MAX = 100.0;
    private static final double DEFAULT_VALUE = 50.0;
    private static final double DEFAULT_FEED_GAIN = 5.0;
    private static final double DEFAULT_FEED_DURATION_MINUTES = 15.0;
    private static final String IMPULSE_KEY_HAND_FEED = "feed:hand";
    private static final String IMPULSE_LABEL_HAND_FEED = "Hand-fed";
    private static final String IMPULSE_KEY_FEED_ITEM_PREFIX = "feed:item:";
    private static final String IMPULSE_KEY_FEED_PARAM_PREFIX = "feed:param:";
    private static final String IMPULSE_LABEL_ATE = "Ate";
    private static final String IMPULSE_KEY_PET = "pet";
    private static final String IMPULSE_LABEL_PET = "Petted";
    private static final String IMPULSE_KEY_DAMAGE = "damage";
    private static final String IMPULSE_LABEL_DAMAGE = "Attacked";
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final StdScopeLookupCache SCOPE_LOOKUP_CACHE = new StdScopeLookupCache();

    private CompanionHappinessService() {
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        return applyFeedGain(npcRef, store, null, 1);
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Store<EntityStore> store,
                                        @Nullable String consumedItemId) {
        return applyFeedGain(npcRef, store, consumedItemId, 1);
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Store<EntityStore> store,
                                        @Nullable String consumedItemId,
                                        int consumedCount) {
        if (consumedCount <= 0) {
            return false;
        }
        return reconcileWithFeedEffects(
                npcRef,
                store,
                null,
                true,
                consumedItemId,
                null
        );
    }

    public static boolean applyFeedImpulses(@Nullable Ref<EntityStore> npcRef,
                                            @Nullable Store<EntityStore> store,
                                            @Nullable Map<String, Integer> consumedItemCountsByItemId) {
        return reconcileWithFeedEffects(
                npcRef,
                store,
                null,
                false,
                null,
                consumedItemCountsByItemId
        );
    }

    public static boolean reconcileWithFeedEffects(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                   boolean includeHandFeedImpulse,
                                                   @Nullable String consumedItemId,
                                                   @Nullable Map<String, Integer> consumedItemCountsByItemId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        HappinessRules rules = resolveRules(happinessConfig);
        ArrayList<TimedImpulseActivation> activations = new ArrayList<>();
        if (includeHandFeedImpulse) {
            addHandFeedActivation(activations, happinessConfig, rules.feedGain);
        }
        Map<String, Integer> effectiveConsumedItems = consumedItemCountsByItemId;
        if ((effectiveConsumedItems == null || effectiveConsumedItems.isEmpty())
                && consumedItemId != null
                && !consumedItemId.isBlank()) {
            effectiveConsumedItems = Collections.singletonMap(consumedItemId, 1);
        }
        if (effectiveConsumedItems != null && !effectiveConsumedItems.isEmpty()) {
            for (Map.Entry<String, Integer> consumedEntry : effectiveConsumedItems.entrySet()) {
                if (consumedEntry == null || consumedEntry.getValue() == null || consumedEntry.getValue() <= 0) {
                    continue;
                }
                addFoodConsumptionActivation(
                        activations,
                        npcRef,
                        store,
                        happinessConfig,
                        consumedEntry.getKey()
                );
            }
        }
        return updateHappiness(npcRef, store, commandBuffer, 0.0, false, activations);
    }

    public static boolean applyPetGain(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        ArrayList<TimedImpulseActivation> activations = new ArrayList<>(1);
        addPetActivation(activations, happinessConfig);
        return applyTimedImpulses(npcRef, store, activations);
    }

    public static boolean applyDamageLoss(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store,
                                          @Nullable Damage damage) {
        return applyDamageLoss(npcRef, store, null, damage);
    }

    public static boolean applyDamageLoss(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store,
                                          @Nullable CommandBuffer<EntityStore> commandBuffer,
                                          @Nullable Damage damage) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        if (damage != null
                && damage.getSource() instanceof Damage.EnvironmentSource environmentSource
                && environmentSource.getType() != null
                && environmentSource.getType().equalsIgnoreCase(CompanionNeedsService.NEEDS_DAMAGE_SOURCE_TYPE)) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        ArrayList<TimedImpulseActivation> activations = new ArrayList<>(1);
        addDamageActivation(activations, happinessConfig);
        return applyTimedImpulses(npcRef, store, commandBuffer, activations);
    }

    public static boolean reconcile(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store) {
        return reconcile(npcRef, store, null);
    }

    public static boolean reconcile(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable CommandBuffer<EntityStore> commandBuffer) {
        return updateHappiness(npcRef, store, commandBuffer, 0.0);
    }

    public static boolean applyImpulse(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store,
                                       double impulseDelta) {
        return updateHappiness(npcRef, store, impulseDelta);
    }

    public static boolean applyDelta(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     double delta) {
        return applyImpulse(npcRef, store, delta);
    }

    @Nullable
    public static HappinessSnapshot resolveSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        TameworkBreedingComponent breeding = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        if (happiness == null && breeding == null && (happinessConfig == null || !happinessConfig.isEnabled())) {
            return null;
        }
        HappinessRules rules = resolveRules(happinessConfig);
        double current = resolveCurrent(happiness, breeding, rules.defaultValue);
        CompanionHappinessModifierService.ModifierSnapshot modifierSnapshot =
                CompanionHappinessModifierService.resolve(npcRef, store, happinessConfig);
        double target = clamp(modifierSnapshot.target(), rules.min, rules.max);
        double baseSetpoint = clamp(modifierSnapshot.baseSetpoint(), rules.min, rules.max);
        List<ActiveImpulseSnapshot> activeImpulses = resolveActiveImpulseSnapshots(happiness, System.currentTimeMillis());
        return new HappinessSnapshot(
                clamp(current, rules.min, rules.max),
                rules.min,
                rules.max,
                baseSetpoint,
                target,
                modifierSnapshot.modifiers(),
                activeImpulses
        );
    }

    private static boolean applyTimedImpulses(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nullable List<TimedImpulseActivation> timedActivations) {
        return applyTimedImpulses(npcRef, store, null, timedActivations);
    }

    private static boolean applyTimedImpulses(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nullable CommandBuffer<EntityStore> commandBuffer,
                                              @Nullable List<TimedImpulseActivation> timedActivations) {
        if (timedActivations == null || timedActivations.isEmpty()) {
            return false;
        }
        return updateHappiness(npcRef, store, commandBuffer, 0.0, false, timedActivations);
    }

    private static boolean updateHappiness(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           double impulseDelta) {
        return updateHappiness(npcRef, store, null, impulseDelta, true, List.of());
    }

    private static boolean updateHappiness(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable CommandBuffer<EntityStore> commandBuffer,
                                           double impulseDelta) {
        return updateHappiness(npcRef, store, commandBuffer, impulseDelta, true, List.of());
    }

    private static boolean updateHappiness(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable CommandBuffer<EntityStore> commandBuffer,
                                           double immediateImpulseDelta,
                                           boolean applyDispositionToImmediateImpulse,
                                           @Nullable List<TimedImpulseActivation> timedActivations) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
        TameworkBreedingComponent breeding = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        if (happiness == null && breeding == null && (happinessConfig == null || !happinessConfig.isEnabled())) {
            return false;
        }
        TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        HappinessRules rules = resolveRules(happinessConfig);
        if (!Double.isFinite(immediateImpulseDelta)) {
            immediateImpulseDelta = 0.0;
        }
        long now = System.currentTimeMillis();
        boolean happinessChanged = false;
        if (happiness == null) {
            double seedValue = resolveCurrent(happiness, breeding, rules.defaultValue);
            String configId = happinessConfig != null ? happinessConfig.getId() : null;
            happiness = new TameworkHappinessComponent(
                    configId,
                    clamp(seedValue, rules.min, rules.max),
                    now
            );
            happinessChanged = true;
        }
        if (happinessConfig != null
                && happinessConfig.getId() != null
                && !happinessConfig.getId().isBlank()) {
            String resolvedConfigId = happinessConfig.getId();
            if (happiness.getConfigId() == null
                    || happiness.getConfigId().isBlank()
                    || !resolvedConfigId.equalsIgnoreCase(happiness.getConfigId())) {
                happiness.setConfigId(resolvedConfigId);
                happinessChanged = true;
            }
        }
        double dispositionMultiplier = resolveDispositionMultiplier(npcRef, store);
        if (applyDispositionToImmediateImpulse) {
            immediateImpulseDelta = applyDispositionToImpulse(immediateImpulseDelta, dispositionMultiplier);
        }
        TimedImpulseMutationResult timedMutation = applyTimedImpulseMutations(
                happiness,
                now,
                timedActivations,
                dispositionMultiplier
        );
        if (timedMutation.changed()) {
            happiness.setActiveImpulses(timedMutation.activeImpulses());
            happinessChanged = true;
        }
        double impulseDelta = immediateImpulseDelta + timedMutation.delta();
        double previous = resolveCurrent(happiness, breeding, rules.defaultValue);
        CompanionHappinessModifierService.ModifierSnapshot modifierSnapshot =
                CompanionHappinessModifierService.resolve(npcRef, store, happinessConfig);
        double target = clamp(modifierSnapshot.target(), rules.min, rules.max);
        long lastUpdateMs = happiness.getLastUpdateMs();
        long elapsedMs = lastUpdateMs > 0L ? Math.max(0L, now - lastUpdateMs) : 0L;
        double elapsedMinutes = elapsedMs / (SECONDS_PER_MINUTE * 1000.0);
        double convergenceStep = rules.convergencePerMinute * elapsedMinutes;
        double converged = moveToward(previous, target, convergenceStep);
        double next = clamp(converged + impulseDelta, rules.min, rules.max);
        if (Math.abs(next - previous) > EPSILON) {
            happiness.setValue(next);
            happinessChanged = true;
        }
        if ((happinessChanged || Math.abs(impulseDelta) > EPSILON || elapsedMs > 0L)
                && happiness.getLastUpdateMs() != now) {
            happiness.setLastUpdateMs(now);
            happinessChanged = true;
        }
        if (happinessChanged) {
            putComponent(npcRef, store, commandBuffer, happinessType, happiness);
        }

        boolean breedingChanged = false;
        if (breeding != null && breedingType != null) {
            if (!Double.isFinite(breeding.getHappiness()) || Math.abs(breeding.getHappiness() - next) > EPSILON) {
                breeding.setHappiness(next);
                breedingChanged = true;
            }
            if ((breedingChanged || Math.abs(impulseDelta) > EPSILON || elapsedMs > 0L)
                    && breeding.getLastHappinessUpdateMs() != now) {
                breeding.setLastHappinessUpdateMs(now);
                breedingChanged = true;
            }
            if (breedingConfig != null) {
                String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
                boolean ready = breeding.isEnabled() && next >= breedingConfig.resolveHappiness(roleId).getThreshold();
                if (breeding.isReady() != ready) {
                    breeding.setReady(ready);
                    breedingChanged = true;
                }
                if (breedingConfig.getId() != null && !breedingConfig.getId().isBlank()) {
                    String resolvedBreedingConfigId = breedingConfig.getId();
                    if (breeding.getConfigId() == null
                            || breeding.getConfigId().isBlank()
                            || !resolvedBreedingConfigId.equalsIgnoreCase(breeding.getConfigId())) {
                        breeding.setConfigId(resolvedBreedingConfigId);
                        breedingChanged = true;
                    }
                }
            }
            if (breedingChanged) {
                putComponent(npcRef, store, commandBuffer, breedingType, breeding);
            }
        }
        return happinessChanged || breedingChanged;
    }

    private static <T extends Component<EntityStore>> void putComponent(@Nonnull Ref<EntityStore> npcRef,
                                                                        @Nonnull Store<EntityStore> store,
                                                                        @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                                        @Nonnull ComponentType<EntityStore, T> componentType,
                                                                        @Nonnull T component) {
        if (commandBuffer != null) {
            commandBuffer.putComponent(npcRef, componentType, component);
            return;
        }
        store.putComponent(npcRef, componentType, component);
    }

    public static double resolveCurrentValue(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             double fallback) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return fallback;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType != null) {
            TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
            if (happiness != null && Double.isFinite(happiness.getValue())) {
                return happiness.getValue();
            }
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType != null) {
            TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
            if (breeding != null && Double.isFinite(breeding.getHappiness())) {
                return breeding.getHappiness();
            }
        }
        return fallback;
    }

    private static HappinessRules resolveRules(@Nullable TwHappinessConfig happinessConfig) {
        if (happinessConfig != null && happinessConfig.isEnabled()) {
            TwHappinessConfig.ValueSettings values = happinessConfig.getValues();
            double min = values.getMin();
            double max = values.getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            double defaultValue = clamp(values.getCurrentDefault(), min, max);
            double convergencePerMinute = happinessConfig.getEquilibrium().getConvergencePerMinute();
            double feedGain = happinessConfig.getImpulses().getGainOnFeed();
            return new HappinessRules(min, max, defaultValue, convergencePerMinute, feedGain);
        }
        return new HappinessRules(DEFAULT_MIN, DEFAULT_MAX, DEFAULT_VALUE, 0.0, DEFAULT_FEED_GAIN);
    }

    private static double resolveCurrent(@Nullable TameworkHappinessComponent happiness,
                                         @Nullable TameworkBreedingComponent breeding,
                                         double fallback) {
        if (happiness != null && Double.isFinite(happiness.getValue())) {
            return happiness.getValue();
        }
        if (breeding != null && Double.isFinite(breeding.getHappiness())) {
            return breeding.getHappiness();
        }
        return fallback;
    }

    private static double resolveDispositionMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                       @Nullable Store<EntityStore> store) {
        return CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                "HappinessGainMultiplier",
                1.0
        );
    }

    private static double applyDispositionToImpulse(double impulseDelta,
                                                    double dispositionMultiplier) {
        if (!Double.isFinite(impulseDelta) || Math.abs(impulseDelta) <= EPSILON) {
            return 0.0;
        }
        return CompanionHappinessModifierService.applyDispositionToOffset(impulseDelta, dispositionMultiplier);
    }

    private static void addHandFeedActivation(@Nonnull List<TimedImpulseActivation> activations,
                                              @Nullable TwHappinessConfig happinessConfig,
                                              double fallbackHandFeedGain) {
        double gain = fallbackHandFeedGain;
        double durationMinutes = DEFAULT_FEED_DURATION_MINUTES;
        if (happinessConfig != null && happinessConfig.isEnabled()) {
            TwHappinessConfig.ImpulseSettings impulses = happinessConfig.getImpulses();
            gain = impulses.getGainOnFeed();
            durationMinutes = impulses.getHandFeedDurationMinutes();
        }
        if (!Double.isFinite(gain) || Math.abs(gain) <= EPSILON) {
            return;
        }
        long durationMs = toDurationMs(durationMinutes);
        if (durationMs <= 0L) {
            return;
        }
        activations.add(
                new TimedImpulseActivation(
                        IMPULSE_KEY_HAND_FEED,
                        IMPULSE_LABEL_HAND_FEED,
                        gain,
                        System.currentTimeMillis() + durationMs,
                        null
                )
        );
    }

    private static void addFoodConsumptionActivation(@Nonnull List<TimedImpulseActivation> activations,
                                                     @Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     @Nullable TwHappinessConfig happinessConfig,
                                                     @Nullable String consumedItemId) {
        ResolvedFeedImpulse resolved = resolveFeedConsumptionImpulse(npcRef, store, happinessConfig, consumedItemId);
        if (resolved == null) {
            return;
        }
        activations.add(
                new TimedImpulseActivation(
                        resolved.key,
                        resolved.label,
                        resolved.rawValue,
                        resolved.expiresAtMs,
                        resolved.itemId
                )
        );
    }

    private static void addPetActivation(@Nonnull List<TimedImpulseActivation> activations,
                                         @Nullable TwHappinessConfig happinessConfig) {
        if (happinessConfig == null || !happinessConfig.isEnabled()) {
            return;
        }
        double gain = happinessConfig.getImpulses().getGainOnPet();
        if (!Double.isFinite(gain) || Math.abs(gain) <= EPSILON) {
            return;
        }
        long durationMs = toDurationMs(happinessConfig.getImpulses().getFeedImpulseDurationMinutes());
        if (durationMs <= 0L) {
            return;
        }
        activations.add(
                new TimedImpulseActivation(
                        IMPULSE_KEY_PET,
                        IMPULSE_LABEL_PET,
                        gain,
                        System.currentTimeMillis() + durationMs,
                        null
                )
        );
    }

    private static void addDamageActivation(@Nonnull List<TimedImpulseActivation> activations,
                                            @Nullable TwHappinessConfig happinessConfig) {
        if (happinessConfig == null || !happinessConfig.isEnabled()) {
            return;
        }
        double configuredLoss = happinessConfig.getImpulses().getLoseOnDamage();
        if (!Double.isFinite(configuredLoss) || Math.abs(configuredLoss) <= EPSILON) {
            return;
        }
        double loss = configuredLoss > 0.0 ? -configuredLoss : configuredLoss;
        long durationMs = toDurationMs(happinessConfig.getImpulses().getFeedImpulseDurationMinutes());
        if (durationMs <= 0L) {
            return;
        }
        activations.add(
                new TimedImpulseActivation(
                        IMPULSE_KEY_DAMAGE,
                        IMPULSE_LABEL_DAMAGE,
                        loss,
                        System.currentTimeMillis() + durationMs,
                        null
                )
        );
    }

    @Nullable
    private static ResolvedFeedImpulse resolveFeedConsumptionImpulse(@Nullable Ref<EntityStore> npcRef,
                                                                     @Nullable Store<EntityStore> store,
                                                                     @Nullable TwHappinessConfig happinessConfig,
                                                                     @Nullable String consumedItemId) {
        if (happinessConfig == null || !happinessConfig.isEnabled()) {
            return null;
        }
        if (consumedItemId == null || consumedItemId.isBlank()) {
            return null;
        }
        String normalizedItemId = normalizeFoodItemId(consumedItemId);
        if (normalizedItemId == null || normalizedItemId.isBlank()) {
            return null;
        }
        String displayItemId = resolveDisplayItemId(consumedItemId);
        TwHappinessConfig.ImpulseSettings impulses = happinessConfig.getImpulses();
        long durationMs = toDurationMs(impulses.getFeedImpulseDurationMinutes());
        if (durationMs <= 0L) {
            return null;
        }
        Double explicitItemImpulse = impulses.getFeedItemImpulses().get(normalizedItemId);
        if (explicitItemImpulse != null && Double.isFinite(explicitItemImpulse)) {
            return new ResolvedFeedImpulse(
                    IMPULSE_KEY_FEED_ITEM_PREFIX + normalizedItemId,
                    IMPULSE_LABEL_ATE,
                    explicitItemImpulse,
                    System.currentTimeMillis() + durationMs,
                    displayItemId
            );
        }
        Map<String, Double> feedParamImpulses = impulses.getFeedParamImpulses();
        if (feedParamImpulses.isEmpty()) {
            return null;
        }
        ArrayList<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(feedParamImpulses.entrySet());
        sortedEntries.sort((left, right) -> left.getKey().compareToIgnoreCase(right.getKey()));
        for (Map.Entry<String, Double> entry : sortedEntries) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            double rawValue = entry.getValue();
            if (!Double.isFinite(rawValue) || Math.abs(rawValue) <= EPSILON) {
                continue;
            }
            String paramName = entry.getKey().trim();
            if (!roleParamContainsItemId(npcRef, store, paramName, normalizedItemId)) {
                continue;
            }
            String normalizedParamName = paramName.toLowerCase(Locale.ROOT);
            return new ResolvedFeedImpulse(
                    IMPULSE_KEY_FEED_PARAM_PREFIX + normalizedParamName,
                    IMPULSE_LABEL_ATE,
                    rawValue,
                    System.currentTimeMillis() + durationMs,
                    displayItemId
            );
        }
        return null;
    }

    static double resolveFeedImpulseTotal(@Nullable TwHappinessConfig happinessConfig,
                                          double fallbackImpulse,
                                          @Nullable Map<String, Integer> consumedItemCountsByItemId) {
        if (consumedItemCountsByItemId == null || consumedItemCountsByItemId.isEmpty()) {
            return 0.0;
        }
        Set<String> seenImpulseKeys = new HashSet<>();
        double totalImpulse = 0.0;
        for (Map.Entry<String, Integer> consumedEntry : consumedItemCountsByItemId.entrySet()) {
            if (consumedEntry == null || consumedEntry.getValue() == null || consumedEntry.getValue() <= 0) {
                continue;
            }
            ResolvedFeedImpulse resolved = resolveFeedConsumptionImpulse(
                    null,
                    null,
                    happinessConfig,
                    consumedEntry.getKey()
            );
            if (resolved == null) {
                continue;
            }
            String normalizedKey = normalizeImpulseKey(resolved.key());
            if (normalizedKey == null || !seenImpulseKeys.add(normalizedKey)) {
                continue;
            }
            totalImpulse += resolved.rawValue();
        }
        if (!Double.isFinite(totalImpulse)) {
            return 0.0;
        }
        return totalImpulse;
    }

    static double resolveFeedItemPreferenceScore(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable Store<EntityStore> store,
                                                 @Nullable TwHappinessConfig happinessConfig,
                                                 @Nullable String consumedItemId,
                                                 double fallbackScore) {
        ResolvedFeedImpulse resolved = resolveFeedConsumptionImpulse(
                npcRef,
                store,
                happinessConfig,
                consumedItemId
        );
        if (resolved == null || !Double.isFinite(resolved.rawValue())) {
            return fallbackScore;
        }
        return resolved.rawValue();
    }

    private static boolean roleParamContainsItemId(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nullable String paramName,
                                                   @Nullable String normalizedItemId) {
        if (paramName == null || paramName.isBlank() || normalizedItemId == null || normalizedItemId.isBlank()) {
            return false;
        }
        StdScope sensorScope = resolveRoleSensorScope(npcRef, store);
        if (sensorScope == null) {
            return false;
        }
        String[] values = resolveRoleStringArrayParam(sensorScope, paramName);
        if (values.length == 0 && "FoodFavorite".equalsIgnoreCase(paramName)) {
            values = resolveRoleStringArrayParam(sensorScope, "AttractiveItemSet");
        }
        if (values.length == 0) {
            return false;
        }
        for (String value : values) {
            String normalizedValue = normalizeFoodItemId(value);
            if (normalizedValue == null || normalizedValue.isBlank()) {
                continue;
            }
            if (normalizedItemId.equals(normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static StdScope resolveRoleSensorScope(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, npcType);
        if (npc == null || npc.getRole() == null || npc.getRole().getEntitySupport() == null) {
            return null;
        }
        EntitySupport support = npc.getRole().getEntitySupport();
        return support.getSensorScope();
    }

    @Nonnull
    private static String[] resolveRoleStringArrayParam(@Nonnull StdScope sensorScope,
                                                        @Nonnull String paramName) {
        String[] values = SCOPE_LOOKUP_CACHE.getStringArrayOrString(sensorScope, paramName);
        return values != null ? values : new String[0];
    }

    private static TimedImpulseMutationResult applyTimedImpulseMutations(@Nonnull TameworkHappinessComponent happiness,
                                                                         long nowMs,
                                                                         @Nullable List<TimedImpulseActivation> timedActivations,
                                                                         double dispositionMultiplier) {
        boolean changed = false;
        double delta = 0.0;
        LinkedHashMap<String, TameworkHappinessComponent.ActiveImpulse> activeByKey = new LinkedHashMap<>();
        for (TameworkHappinessComponent.ActiveImpulse activeImpulse : happiness.getActiveImpulses()) {
            if (activeImpulse == null) {
                changed = true;
                continue;
            }
            String normalizedKey = normalizeImpulseKey(activeImpulse.getKey());
            if (normalizedKey == null) {
                changed = true;
                continue;
            }
            double activeValue = activeImpulse.getValue();
            long expiresAtMs = activeImpulse.getExpiresAtMs();
            if (expiresAtMs > 0L && expiresAtMs <= nowMs) {
                if (Double.isFinite(activeValue) && Math.abs(activeValue) > EPSILON) {
                    delta -= activeValue;
                }
                changed = true;
                continue;
            }
            if (!Double.isFinite(activeValue)) {
                changed = true;
                continue;
            }
            TameworkHappinessComponent.ActiveImpulse normalized = activeImpulse.clone();
            if (!normalizedKey.equals(activeImpulse.getKey())) {
                normalized.setKey(normalizedKey);
                changed = true;
            }
            activeByKey.put(normalizedKey, normalized);
        }
        if (timedActivations != null) {
            for (TimedImpulseActivation activation : timedActivations) {
                if (activation == null) {
                    continue;
                }
                String normalizedKey = normalizeImpulseKey(activation.key);
                if (normalizedKey == null) {
                    continue;
                }
                if (!Double.isFinite(activation.rawValue) || Math.abs(activation.rawValue) <= EPSILON) {
                    continue;
                }
                long expiresAtMs = activation.expiresAtMs > nowMs ? activation.expiresAtMs : nowMs + 1L;
                double adjustedValue = applyDispositionToImpulse(activation.rawValue, dispositionMultiplier);
                if (!Double.isFinite(adjustedValue) || Math.abs(adjustedValue) <= EPSILON) {
                    continue;
                }
                TameworkHappinessComponent.ActiveImpulse existing = activeByKey.get(normalizedKey);
                if (existing == null) {
                    TameworkHappinessComponent.ActiveImpulse created = new TameworkHappinessComponent.ActiveImpulse();
                    created.setKey(normalizedKey);
                    created.setLabel(activation.label);
                    created.setValue(adjustedValue);
                    created.setExpiresAtMs(expiresAtMs);
                    created.setItemId(activation.itemId);
                    activeByKey.put(normalizedKey, created);
                    delta += adjustedValue;
                    changed = true;
                    continue;
                }
                if (Math.abs(existing.getValue() - adjustedValue) > EPSILON) {
                    delta += adjustedValue - existing.getValue();
                    existing.setValue(adjustedValue);
                    changed = true;
                }
                if (existing.getExpiresAtMs() != expiresAtMs) {
                    existing.setExpiresAtMs(expiresAtMs);
                    changed = true;
                }
                if (!stringEquals(existing.getLabel(), activation.label)) {
                    existing.setLabel(activation.label);
                    changed = true;
                }
                if (!stringEquals(existing.getItemId(), activation.itemId)) {
                    existing.setItemId(activation.itemId);
                    changed = true;
                }
            }
        }
        TameworkHappinessComponent.ActiveImpulse[] activeImpulses =
                activeByKey.values().toArray(new TameworkHappinessComponent.ActiveImpulse[0]);
        return new TimedImpulseMutationResult(changed, delta, activeImpulses);
    }

    @Nonnull
    private static List<ActiveImpulseSnapshot> resolveActiveImpulseSnapshots(@Nullable TameworkHappinessComponent happiness,
                                                                             long nowMs) {
        if (happiness == null) {
            return List.of();
        }
        ArrayList<ActiveImpulseSnapshot> activeImpulses = new ArrayList<>();
        for (TameworkHappinessComponent.ActiveImpulse activeImpulse : happiness.getActiveImpulses()) {
            if (activeImpulse == null
                    || activeImpulse.getKey() == null
                    || activeImpulse.getKey().isBlank()
                    || !Double.isFinite(activeImpulse.getValue())
                    || Math.abs(activeImpulse.getValue()) <= EPSILON) {
                continue;
            }
            long expiresAtMs = activeImpulse.getExpiresAtMs();
            if (expiresAtMs > 0L && expiresAtMs <= nowMs) {
                continue;
            }
            activeImpulses.add(
                    new ActiveImpulseSnapshot(
                            activeImpulse.getKey(),
                            activeImpulse.getLabel(),
                            activeImpulse.getValue(),
                            expiresAtMs,
                            activeImpulse.getItemId()
                    )
            );
        }
        if (activeImpulses.isEmpty()) {
            return List.of();
        }
        return List.copyOf(activeImpulses);
    }

    @Nullable
    private static String normalizeImpulseKey(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static long toDurationMs(double durationMinutes) {
        if (!Double.isFinite(durationMinutes) || durationMinutes <= 0.0) {
            return 0L;
        }
        double durationMs = durationMinutes * 60_000.0;
        if (!Double.isFinite(durationMs) || durationMs <= 0.0) {
            return 0L;
        }
        return Math.max(1L, Math.round(durationMs));
    }

    @Nullable
    private static String normalizeFoodItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String normalized = itemId.trim();
        if (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        int stateIndex = normalized.indexOf("_State_");
        if (stateIndex > 0) {
            normalized = normalized.substring(0, stateIndex);
        }
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String resolveDisplayItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String resolved = itemId.trim();
        if (resolved.startsWith("*")) {
            resolved = resolved.substring(1);
        }
        int stateIndex = resolved.indexOf("_State_");
        if (stateIndex > 0) {
            resolved = resolved.substring(0, stateIndex);
        }
        return resolved.isBlank() ? null : resolved;
    }

    private static boolean stringEquals(@Nullable String left, @Nullable String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static double moveToward(double current, double target, double maxStep) {
        if (!Double.isFinite(current)) {
            return target;
        }
        if (!Double.isFinite(target)) {
            return current;
        }
        if (!Double.isFinite(maxStep) || maxStep <= 0.0) {
            return current;
        }
        if (current < target) {
            return Math.min(target, current + maxStep);
        }
        if (current > target) {
            return Math.max(target, current - maxStep);
        }
        return current;
    }

    private record HappinessRules(double min,
                                  double max,
                                  double defaultValue,
                                  double convergencePerMinute,
                                  double feedGain) {
    }

    private record TimedImpulseActivation(String key,
                                          String label,
                                          double rawValue,
                                          long expiresAtMs,
                                          String itemId) {
    }

    private record ResolvedFeedImpulse(String key,
                                       String label,
                                       double rawValue,
                                       long expiresAtMs,
                                       String itemId) {
    }

    private record TimedImpulseMutationResult(boolean changed,
                                              double delta,
                                              TameworkHappinessComponent.ActiveImpulse[] activeImpulses) {
    }

    /**
     * Read-only active impulse entry used for UI and diagnostics.
     */
    public record ActiveImpulseSnapshot(String key,
                                        String label,
                                        double value,
                                        long expiresAtMs,
                                        String itemId) {
    }

    /**
     * Read-only happiness state used by UI and debug command output.
     */
    public record HappinessSnapshot(double value,
                                    double min,
                                    double max,
                                    double baseSetpoint,
                                    double target,
                                    List<CompanionHappinessModifierService.ModifierEntry> modifiers,
                                    List<ActiveImpulseSnapshot> activeImpulses) {
        public HappinessSnapshot(double value,
                                double min,
                                double max,
                                double baseSetpoint,
                                double target,
                                List<CompanionHappinessModifierService.ModifierEntry> modifiers) {
            this(value, min, max, baseSetpoint, target, modifiers, List.of());
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
