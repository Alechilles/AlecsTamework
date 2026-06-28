package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.damage.DamageTargetMemoryService;
import com.alechilles.alecstamework.damage.RecentNeedsDeathCauseService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Applies hunger/thirst progression and triggers equilibrium-happiness reconciliation.
 */
public final class CompanionNeedsService {
    private static final Logger LOGGER = Logger.getLogger(CompanionNeedsService.class.getName());
    private static final double EPSILON = 0.000001;
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final double MILLIS_PER_MINUTE = SECONDS_PER_MINUTE * 1000.0;
    private static final String HEALTH_STAT_ID = "Health";
    private static final double NON_LETHAL_MIN_REMAINING_HEALTH = 1.0;
    private static final double MIN_DAMAGE_AMOUNT = 0.0001;
    private static final long MAX_LOADED_TICK_CATCH_UP_MS = 30_000L;
    private static final double REGEN_SUPPRESSION_BASELINE_UNSET = -1.0;
    private static final double MAX_REGEN_SUPPRESSION_ALLOWED_HEAL = 10_000.0;
    private static final long REGEN_HARD_BLOCK_MIN_FUTURE_SECONDS = 30L;
    private static final long REGEN_HARD_BLOCK_TARGET_FUTURE_SECONDS = 120L;
    private static final String NEEDS_DECAY_MULTIPLIER_EFFECT_KEY = "NeedsDecayMultiplier";
    public static final String NEEDS_DAMAGE_SOURCE_TYPE = "tamework.needs";
    private static final CompanionNeedsEnvironmentService ENVIRONMENT_SERVICE = new CompanionNeedsEnvironmentService();

    private CompanionNeedsService() {
    }

    /**
     * Ensures a valid needs component exists for the NPC and seeds config defaults when missing.
     */
    @Nullable
    public static TameworkNeedsComponent ensureNeedsComponent(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable String roleId) {
        return ensureNeedsComponent(npcRef, store, null, roleId);
    }

    @Nullable
    public static TameworkNeedsComponent ensureNeedsComponent(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                              @Nullable String roleId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return null;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, store.getComponent(npcRef, needsType));
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return null;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        long nowMs = resolveNowMs(config, store);
        TameworkNeedsComponent existing = store.getComponent(npcRef, needsType);
        if (existing == null) {
            TameworkNeedsComponent created = new TameworkNeedsComponent(
                    config.getId(),
                    values.getHungerDefault(),
                    values.getThirstDefault(),
                    0.0,
                    nowMs,
                    nowMs
            );
            putComponent(npcRef, store, commandBuffer, needsType, created);
            return created;
        }
        boolean changed = false;
        String resolvedConfigId = config.getId();
        if (resolvedConfigId != null
                && !resolvedConfigId.isBlank()
                && (existing.getConfigId() == null
                || existing.getConfigId().isBlank()
                || !resolvedConfigId.equalsIgnoreCase(existing.getConfigId()))) {
            existing.setConfigId(resolvedConfigId);
            changed = true;
        }
        double hunger = sanitizeAndClamp(existing.getHunger(), values.getHungerDefault(), values.getHungerMin(), values.getHungerMax());
        double thirst = sanitizeAndClamp(existing.getThirst(), values.getThirstDefault(), values.getThirstMin(), values.getThirstMax());
        if (Math.abs(existing.getHunger() - hunger) > EPSILON || !Double.isFinite(existing.getHunger())) {
            existing.setHunger(hunger);
            changed = true;
        }
        if (Math.abs(existing.getThirst() - thirst) > EPSILON || !Double.isFinite(existing.getThirst())) {
            existing.setThirst(thirst);
            changed = true;
        }
        double appliedPenalty = Double.isFinite(existing.getAppliedHappinessPenalty())
                ? existing.getAppliedHappinessPenalty()
                : 0.0;
        if (!Double.isFinite(existing.getAppliedHappinessPenalty())) {
            existing.setAppliedHappinessPenalty(appliedPenalty);
            changed = true;
        }
        double pendingNeedsDamage = normalizePendingNeedsDamage(existing.getPendingNeedsDamage());
        if (Math.abs(existing.getPendingNeedsDamage() - pendingNeedsDamage) > EPSILON
                || !Double.isFinite(existing.getPendingNeedsDamage())) {
            existing.setPendingNeedsDamage(pendingNeedsDamage);
            changed = true;
        }
        double regenSuppressionBaseline = normalizeRegenSuppressionBaselineHealth(
                existing.getRegenSuppressionBaselineHealth()
        );
        boolean runtimeClockReset = existing.getLastUpdateMs() > nowMs || existing.getLastPassiveSweepMs() > nowMs;
        if (runtimeClockReset) {
            regenSuppressionBaseline = REGEN_SUPPRESSION_BASELINE_UNSET;
        }
        if (Math.abs(existing.getRegenSuppressionBaselineHealth() - regenSuppressionBaseline) > EPSILON
                || !Double.isFinite(existing.getRegenSuppressionBaselineHealth())) {
            existing.setRegenSuppressionBaselineHealth(regenSuppressionBaseline);
            changed = true;
        }
        double regenSuppressionAllowedHeal = normalizeRegenSuppressionAllowedHeal(
                existing.getRegenSuppressionAllowedHeal()
        );
        if (runtimeClockReset) {
            regenSuppressionAllowedHeal = 0.0;
        }
        if (Math.abs(existing.getRegenSuppressionAllowedHeal() - regenSuppressionAllowedHeal) > EPSILON
                || !Double.isFinite(existing.getRegenSuppressionAllowedHeal())) {
            existing.setRegenSuppressionAllowedHeal(regenSuppressionAllowedHeal);
            changed = true;
        }
        double lastManagedHealth = normalizeManagedHealth(existing.getLastManagedHealth());
        if (Math.abs(existing.getLastManagedHealth() - lastManagedHealth) > EPSILON
                || !Double.isFinite(existing.getLastManagedHealth())) {
            existing.setLastManagedHealth(lastManagedHealth);
            changed = true;
        }
        if (existing.getLastUpdateMs() <= 0L || existing.getLastUpdateMs() > nowMs) {
            existing.setLastUpdateMs(nowMs);
            changed = true;
        }
        if (existing.getLastPassiveSweepMs() <= 0L || existing.getLastPassiveSweepMs() > nowMs) {
            existing.setLastPassiveSweepMs(nowMs);
            changed = true;
        }
        if (changed) {
            putComponent(npcRef, store, commandBuffer, needsType, existing);
        }
        return existing;
    }

    /**
     * Runs a needs progression step: decay plus equilibrium happiness reconciliation.
     */
    public static boolean tickNeeds(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable String roleId) {
        return tickNeeds(npcRef, store, null, roleId);
    }

    public static boolean tickNeeds(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable CommandBuffer<EntityStore> commandBuffer,
                                    @Nullable String roleId) {
        return runNeedsUpdate(npcRef, store, roleId, 0.0, 0.0, false, true, commandBuffer, null);
    }

    /**
     * Applies natural regeneration suppression without advancing hunger/thirst decay or needs damage.
     * Intended for high-frequency clamp passes between normal needs sweeps.
     */
    public static boolean tickNaturalRegenSuppressionOnly(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          @Nullable String roleId) {
        return tickNaturalRegenSuppressionOnly(npcRef, store, null, roleId);
    }

    public static boolean tickNaturalRegenSuppressionOnly(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                          @Nullable String roleId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        if (component == null) {
            return false;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return suspendNeedsRuntimeState(npcRef, store, commandBuffer, needsType, component, config);
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(
                component.getHunger(),
                values.getHungerDefault(),
                values.getHungerMin(),
                values.getHungerMax()
        );
        double thirst = sanitizeAndClamp(
                component.getThirst(),
                values.getThirstDefault(),
                values.getThirstMin(),
                values.getThirstMax()
        );
        boolean changed = false;
        if (Math.abs(component.getHunger() - hunger) > EPSILON || !Double.isFinite(component.getHunger())) {
            component.setHunger(hunger);
            changed = true;
        }
        if (Math.abs(component.getThirst() - thirst) > EPSILON || !Double.isFinite(component.getThirst())) {
            component.setThirst(thirst);
            changed = true;
        }
        boolean suppressNaturalRegen = shouldSuppressNaturalRegen(config, values, hunger, thirst);
        boolean diagnosticsEnabled = isNeedsDamageDiagnosticsEnabled();
        String npcId = diagnosticsEnabled ? resolveNpcId(npcRef, store) : "<disabled>";
        double healthBeforeSuppression = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        boolean suppressionChanged = applyNaturalRegenSuppression(
                npcRef,
                store,
                commandBuffer,
                component,
                suppressNaturalRegen,
                !CompanionNeedsRuntimePolicy.resolveDamage(config).isLethal()
        );
        double healthAfterSuppression = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        if (suppressionChanged) {
            changed = true;
        }
        if (changed) {
            putComponent(npcRef, store, commandBuffer, needsType, component);
        }
        if (diagnosticsEnabled) {
            double suppressionDelta = resolveHealthDelta(healthBeforeSuppression, healthAfterSuppression);
            if (suppressionDelta > EPSILON || suppressionChanged) {
                LOGGER.log(Level.INFO, String.format(
                        "Needs suppression-only tick: npc=%s role=%s config=%s hunger=%.3f thirst=%.3f suppress=%s "
                                + "hp=%.3f->%.3f delta=%.3f baseline=%.3f allowance=%.3f changed=%s",
                        npcId,
                        safeLabel(roleId),
                        safeLabel(config.getId()),
                        hunger,
                        thirst,
                        suppressNaturalRegen,
                        healthBeforeSuppression,
                        healthAfterSuppression,
                        suppressionDelta,
                        component.getRegenSuppressionBaselineHealth(),
                        component.getRegenSuppressionAllowedHeal(),
                        changed
                ));
            }
        }
        return changed;
    }

    /**
     * Registers externally-applied healing (for example feed interaction healing) so starvation/dehydration
     * regen suppression can allow that exact heal amount without allowing natural regeneration drift.
     */
    public static void allowExternalHeal(@Nullable Ref<EntityStore> npcRef,
                                         @Nullable Store<EntityStore> store,
                                         double healAmount) {
        if (npcRef == null || store == null || !npcRef.isValid() || !Double.isFinite(healAmount) || healAmount <= 0.0) {
            return;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        if (component == null) {
            return;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, null, component);
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(
                component.getHunger(),
                values.getHungerDefault(),
                values.getHungerMin(),
                values.getHungerMax()
        );
        double thirst = sanitizeAndClamp(
                component.getThirst(),
                values.getThirstDefault(),
                values.getThirstMin(),
                values.getThirstMax()
        );
        if (!shouldSuppressNaturalRegen(config, values, hunger, thirst)) {
            return;
        }
        double allowance = normalizeRegenSuppressionAllowedHeal(component.getRegenSuppressionAllowedHeal());
        allowance = Math.min(MAX_REGEN_SUPPRESSION_ALLOWED_HEAL, allowance + healAmount);
        double baseline = normalizeRegenSuppressionBaselineHealth(component.getRegenSuppressionBaselineHealth());
        if (baseline == REGEN_SUPPRESSION_BASELINE_UNSET) {
            double managedHealth = normalizeManagedHealth(component.getLastManagedHealth());
            if (managedHealth != REGEN_SUPPRESSION_BASELINE_UNSET) {
                baseline = managedHealth;
            } else {
                EntityStatValue health = resolveHealthStat(npcRef, store);
                if (health != null && Double.isFinite(health.get())) {
                    baseline = health.get();
                }
            }
        }
        component.setRegenSuppressionAllowedHeal(allowance);
        component.setRegenSuppressionBaselineHealth(baseline);
        store.putComponent(npcRef, needsType, component);
    }

    /**
     * Applies feed-interaction refill and optional thirst refill from configured water-bucket items.
     */
    public static boolean applyFeedInteractionRefill(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     @Nullable String heldItemId) {
        return runNeedsUpdate(npcRef, store, null, 0.0, 0.0, true, heldItemId);
    }

    public static boolean applyFeedInteractionRefill(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     @Nullable String heldItemId,
                                                     boolean reconcileHappiness) {
        return runNeedsUpdate(
                npcRef,
                store,
                null,
                0.0,
                0.0,
                true,
                reconcileHappiness,
                null,
                heldItemId
        );
    }

    /**
     * Applies an explicit consume attempt for water and/or food from action-driven seek flow.
     */
    public static boolean applyResourceConsume(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String roleId,
                                               @Nullable String resourceType,
                                               @Nullable String[] preferredFoodItemIds) {
        return applyResourceConsume(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                null
        );
    }

    /**
     * Applies an explicit consume attempt for water and/or food from action-driven seek flow.
     */
    public static boolean applyResourceConsume(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String roleId,
                                               @Nullable String resourceType,
                                               @Nullable String[] preferredFoodItemIds,
                                               @Nullable Vector3d consumeOriginOverride) {
        return CompanionNeedsConsumeService.applyResourceConsume(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                consumeOriginOverride
        );
    }

    /**
     * Applies an explicit consume attempt and emits diagnostic logs for failed attempts.
     */
    public static boolean applyResourceConsumeWithDiagnostics(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable String roleId,
                                                              @Nullable String resourceType,
                                                              @Nullable String[] preferredFoodItemIds) {
        return applyResourceConsumeWithDiagnostics(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                null
        );
    }

    /**
     * Applies an explicit consume attempt and emits diagnostic logs for failed attempts.
     */
    public static boolean applyResourceConsumeWithDiagnostics(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable String roleId,
                                                              @Nullable String resourceType,
                                                              @Nullable String[] preferredFoodItemIds,
                                                              @Nullable Vector3d consumeOriginOverride) {
        return CompanionNeedsConsumeService.applyResourceConsumeWithDiagnostics(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                consumeOriginOverride
        );
    }

    /**
     * Emits a runtime-only diagnostic when the asset flow reaches the consume action.
     */
    public static void logResourceConsumeActionReached(@Nullable Ref<EntityStore> npcRef,
                                                       @Nullable Store<EntityStore> store,
                                                       @Nullable String roleId,
                                                       @Nullable String resourceType,
                                                       @Nullable Vector3d consumeOriginOverride) {
        CompanionNeedsConsumeService.logResourceConsumeActionReached(
                npcRef,
                store,
                roleId,
                resourceType,
                consumeOriginOverride
        );
    }

    /**
     * Returns whether this NPC is currently in a needs-damage state (damage enabled and hunger/thirst at minimum).
     */
    public static boolean isNeedsDamageActive(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nullable String roleId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        if (component == null) {
            return false;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return false;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(
                component.getHunger(),
                values.getHungerDefault(),
                values.getHungerMin(),
                values.getHungerMax()
        );
        double thirst = sanitizeAndClamp(
                component.getThirst(),
                values.getThirstDefault(),
                values.getThirstMin(),
                values.getThirstMax()
        );
        return shouldSuppressNaturalRegen(config, values, hunger, thirst);
    }

    /**
     * Returns whether this NPC needs the high-frequency suppression-only pass between normal needs sweeps.
     *
     * <p>Healthy companions with no active suppression state can skip that per-tick work until the next normal
     * sweep updates hunger/thirst again.
     */
    public static boolean requiresFrequentNaturalRegenSuppressionTick(@Nullable Ref<EntityStore> npcRef,
                                                                      @Nullable Store<EntityStore> store,
                                                                      @Nullable String roleId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        if (component == null) {
            return false;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return false;
        }
        return requiresFrequentNaturalRegenSuppressionTick(component, config);
    }

    static boolean runNeedsUpdate(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String roleId,
                                  double explicitHungerGain,
                                  double explicitThirstGain,
                                  boolean includeConfiguredManualGains,
                                  @Nullable String heldItemId) {
        return runNeedsUpdate(
                npcRef,
                store,
                roleId,
                explicitHungerGain,
                explicitThirstGain,
                includeConfiguredManualGains,
                true,
                null,
                heldItemId
        );
    }

    static boolean runNeedsUpdate(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String roleId,
                                  double explicitHungerGain,
                                  double explicitThirstGain,
                                  boolean includeConfiguredManualGains,
                                  boolean reconcileHappiness,
                                  @Nullable CommandBuffer<EntityStore> commandBuffer,
                                  @Nullable String heldItemId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return suspendNeedsRuntimeState(npcRef, store, commandBuffer, needsType, component, config);
        }
        component = ensureNeedsComponent(npcRef, store, commandBuffer, roleId);
        if (component == null) {
            return false;
        }
        long nowMs = resolveNowMs(config, store);
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(component.getHunger(), values.getHungerDefault(), values.getHungerMin(), values.getHungerMax());
        double thirst = sanitizeAndClamp(component.getThirst(), values.getThirstDefault(), values.getThirstMin(), values.getThirstMax());
        double hungerBeforeTick = hunger;
        double thirstBeforeTick = thirst;
        long lastUpdateMs = component.getLastUpdateMs();
        UUID ownerId = resolveOwnerId(npcRef, store);
        long effectiveElapsedMs = capLoadedTickElapsedMs(
                resolveEffectiveElapsedMs(config, ownerId, lastUpdateMs, nowMs)
        );
        boolean diagnosticsEnabled = isNeedsDamageDiagnosticsEnabled();
        String npcId = diagnosticsEnabled ? resolveNpcId(npcRef, store) : "<disabled>";
        double healthBeforeTick = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        double managedHealthBeforeTick = diagnosticsEnabled ? normalizeManagedHealth(component.getLastManagedHealth()) : REGEN_SUPPRESSION_BASELINE_UNSET;
        double pendingNeedsDamageBefore = component.getPendingNeedsDamage();
        double baselineBeforeSuppression = component.getRegenSuppressionBaselineHealth();
        double allowedHealBeforeSuppression = component.getRegenSuppressionAllowedHeal();

        boolean componentChanged = false;
        if (effectiveElapsedMs > 0L) {
            double elapsedMinutes = effectiveElapsedMs / MILLIS_PER_MINUTE;
            TwNeedsConfig.DecaySettings decay = config.getDecay();
            double needsDecayMultiplier = resolveNeedsDecayMultiplier(npcRef, store);
            double hungerDecay = decay.getHungerPerMinute() * elapsedMinutes * needsDecayMultiplier;
            double thirstDecay = decay.getThirstPerMinute() * elapsedMinutes * needsDecayMultiplier;
            hunger = clamp(hunger - hungerDecay, values.getHungerMin(), values.getHungerMax());
            thirst = clamp(thirst - thirstDecay, values.getThirstMin(), values.getThirstMax());
            componentChanged = true;
        }

        double hungerGain = explicitHungerGain;
        double thirstGain = explicitThirstGain;
        if (includeConfiguredManualGains) {
            TwNeedsConfig.ManualRefillSettings manualRefill = config.getManualRefill();
            hungerGain += manualRefill.getHungerGainOnFeedInteraction();
            if (ENVIRONMENT_SERVICE.isConfiguredWaterBucketItem(heldItemId, config)) {
                thirstGain += manualRefill.getThirstGainOnWaterBucket();
            }
        }
        if (hungerGain > 0.0) {
            hunger = clamp(hunger + hungerGain, values.getHungerMin(), values.getHungerMax());
            componentChanged = true;
        }
        if (thirstGain > 0.0) {
            thirst = clamp(thirst + thirstGain, values.getThirstMin(), values.getThirstMax());
            componentChanged = true;
        }

        double healthMax = resolveHealthMax(npcRef, store);
        double needsDamageAmount = resolveNeedsDamageAmount(
                config,
                values,
                hunger,
                thirst,
                effectiveElapsedMs,
                healthMax
        );
        NeedsDamagePoolResolution needsDamagePool = resolveNeedsDamagePooling(
                needsDamageAmount,
                component.getPendingNeedsDamage()
        );
        double pendingNeedsDamage = needsDamagePool.getPendingDamageRemainder();
        if (Math.abs(component.getPendingNeedsDamage() - pendingNeedsDamage) > EPSILON
                || !Double.isFinite(component.getPendingNeedsDamage())) {
            component.setPendingNeedsDamage(pendingNeedsDamage);
            componentChanged = true;
        }
        boolean suppressNaturalRegen = shouldSuppressNaturalRegen(config, values, hunger, thirst);
        double healthBeforeSuppression = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        boolean regenSuppressionChanged = applyNaturalRegenSuppression(
                npcRef,
                store,
                commandBuffer,
                component,
                suppressNaturalRegen,
                !CompanionNeedsRuntimePolicy.resolveDamage(config).isLethal()
        );
        double healthAfterSuppression = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        double suppressionHealthDelta = resolveHealthDelta(healthBeforeSuppression, healthAfterSuppression);
        if (regenSuppressionChanged) {
            componentChanged = true;
        }
        double pooledDamageAmount = needsDamagePool.getDamageToApply();
        double healthBeforeDamage = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        recordRecentNeedsDeathCause(
                npcRef,
                store,
                resolveNeedsDamageCauseHint(config, values, hunger, thirst, effectiveElapsedMs, healthMax),
                pooledDamageAmount
        );
        NeedsDamageExecutionResult damageResult = applyNeedsDamage(
                npcRef,
                store,
                commandBuffer,
                pooledDamageAmount,
                CompanionNeedsRuntimePolicy.resolveDamage(config).isLethal()
        );
        double healthAfterDamage = diagnosticsEnabled ? resolveCurrentHealth(npcRef, store) : Double.NaN;
        boolean damageApplied = damageResult.applied;

        if (!Double.isFinite(component.getAppliedHappinessPenalty())
                || Math.abs(component.getAppliedHappinessPenalty()) > EPSILON) {
            component.setAppliedHappinessPenalty(0.0);
            componentChanged = true;
        }

        if (Math.abs(component.getHunger() - hunger) > EPSILON || !Double.isFinite(component.getHunger())) {
            component.setHunger(hunger);
            componentChanged = true;
        }
        if (Math.abs(component.getThirst() - thirst) > EPSILON || !Double.isFinite(component.getThirst())) {
            component.setThirst(thirst);
            componentChanged = true;
        }
        if (component.getLastUpdateMs() != nowMs) {
            component.setLastUpdateMs(nowMs);
            componentChanged = true;
        }
        if (componentChanged) {
            putComponent(npcRef, store, commandBuffer, needsType, component);
        }
        boolean happinessChanged = reconcileHappiness
                ? CompanionHappinessService.reconcile(npcRef, store, commandBuffer)
                : false;
        boolean externalDamageSuspected = diagnosticsEnabled
                && isExternalDamageSuspected(healthBeforeTick, managedHealthBeforeTick, suppressNaturalRegen, pooledDamageAmount);
        String recentAttackerLabel = externalDamageSuspected ? resolveRecentAttackerLabel(npcRef, store) : "<none>";
        if (diagnosticsEnabled && shouldLogNeedsDamageDiagnostics(
                suppressNaturalRegen,
                regenSuppressionChanged,
                suppressionHealthDelta,
                needsDamageAmount,
                pooledDamageAmount,
                damageResult,
                externalDamageSuspected
        )) {
            logNeedsDamageDiagnostics(
                    npcId,
                    roleId,
                    config.getId(),
                    nowMs,
                    lastUpdateMs,
                    effectiveElapsedMs,
                    hungerBeforeTick,
                    hunger,
                    thirstBeforeTick,
                    thirst,
                    healthMax,
                    healthBeforeTick,
                    healthBeforeSuppression,
                    healthAfterSuppression,
                    healthBeforeDamage,
                    healthAfterDamage,
                    needsDamageAmount,
                    pendingNeedsDamageBefore,
                    pooledDamageAmount,
                    component.getPendingNeedsDamage(),
                    suppressNaturalRegen,
                    baselineBeforeSuppression,
                    component.getRegenSuppressionBaselineHealth(),
                    allowedHealBeforeSuppression,
                    component.getRegenSuppressionAllowedHeal(),
                    suppressionHealthDelta,
                    damageResult,
                    externalDamageSuspected,
                    recentAttackerLabel,
                    componentChanged,
                    happinessChanged
            );
        }
        return componentChanged || happinessChanged || damageApplied;
    }

    private static boolean suspendNeedsRuntimeState(@Nonnull Ref<EntityStore> npcRef,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                    @Nonnull ComponentType<EntityStore, TameworkNeedsComponent> needsType,
                                                    @Nullable TameworkNeedsComponent component,
                                                    @Nullable TwNeedsConfig config) {
        if (component == null || config == null) {
            return false;
        }
        boolean changed = false;
        if (Math.abs(component.getPendingNeedsDamage()) > EPSILON
                || !Double.isFinite(component.getPendingNeedsDamage())) {
            component.setPendingNeedsDamage(0.0);
            changed = true;
        }
        if (!Double.isFinite(component.getAppliedHappinessPenalty())
                || Math.abs(component.getAppliedHappinessPenalty()) > EPSILON) {
            component.setAppliedHappinessPenalty(0.0);
            changed = true;
        }
        long nowMs = resolveNowMs(config, store);
        if (component.getLastUpdateMs() != nowMs) {
            component.setLastUpdateMs(nowMs);
            changed = true;
        }
        if (component.getLastPassiveSweepMs() != nowMs) {
            component.setLastPassiveSweepMs(nowMs);
            changed = true;
        }
        changed = applyNaturalRegenSuppression(npcRef, store, commandBuffer, component, false, false) || changed;
        if (changed) {
            putComponent(npcRef, store, commandBuffer, needsType, component);
        }
        return changed;
    }

    static boolean requiresFrequentNaturalRegenSuppressionTick(@Nullable TameworkNeedsComponent component,
                                                               @Nullable TwNeedsConfig config) {
        return requiresFrequentNaturalRegenSuppressionTick(
                component,
                config,
                TameworkRuntimeSettings.currentOrNull()
        );
    }

    static boolean requiresFrequentNaturalRegenSuppressionTick(@Nullable TameworkNeedsComponent component,
                                                               @Nullable TwNeedsConfig config,
                                                               @Nullable TameworkRuntimeSettings settings) {
        if (component == null || !CompanionNeedsRuntimePolicy.isNeedsEnabled(config, settings)) {
            return false;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(
                component.getHunger(),
                values.getHungerDefault(),
                values.getHungerMin(),
                values.getHungerMax()
        );
        double thirst = sanitizeAndClamp(
                component.getThirst(),
                values.getThirstDefault(),
                values.getThirstMin(),
                values.getThirstMax()
        );
        if (shouldSuppressNaturalRegen(config, values, hunger, thirst, settings)) {
            return true;
        }
        return hasResidualNaturalRegenSuppressionState(component);
    }

    @Nullable
    static TwNeedsConfig resolveNeedsConfig(@Nullable Ref<EntityStore> npcRef,
                                            @Nullable Store<EntityStore> store,
                                            @Nullable String roleId,
                                            @Nullable TameworkNeedsComponent component) {
        if (roleId != null && !roleId.isBlank()) {
            TwNeedsConfig byRoleId = TwNeedsConfig.resolveForRole(roleId);
            if (byRoleId != null) {
                return byRoleId;
            }
        }
        String resolvedRoleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (resolvedRoleId != null && !resolvedRoleId.isBlank()) {
            TwNeedsConfig byResolvedRole = TwNeedsConfig.resolveForRole(resolvedRoleId);
            if (byResolvedRole != null) {
                return byResolvedRole;
            }
        }
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwNeedsConfig byId = TwNeedsConfig.resolveById(component.getConfigId());
            if (byId != null) {
                return byId;
            }
        }
        return null;
    }

    private static long resolveEffectiveElapsedMs(@Nonnull TwNeedsConfig config,
                                                  @Nullable UUID ownerId,
                                                  long lastUpdateMs,
                                                  long nowMs) {
        if (lastUpdateMs <= 0L || nowMs <= lastUpdateMs) {
            return 0L;
        }
        TwNeedsConfig.TickPolicySettings tickPolicy = CompanionNeedsRuntimePolicy.resolveTickPolicy(config);
        if (ownerId == null) {
            return nowMs - lastUpdateMs;
        }
        return OwnerPresenceTimelineService.get().resolveEffectiveElapsedMs(ownerId, lastUpdateMs, nowMs, tickPolicy);
    }

    static long capLoadedTickElapsedMs(long effectiveElapsedMs) {
        if (effectiveElapsedMs <= 0L) {
            return 0L;
        }
        return Math.min(effectiveElapsedMs, MAX_LOADED_TICK_CATCH_UP_MS);
    }

    @Nullable
    static UUID resolveOwnerId(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
            if (owner != null && owner.getOwnerId() != null) {
                return owner.getOwnerId();
            }
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType == null) {
            return null;
        }
        TameworkCommandLinksComponent links = store.getComponent(npcRef, linksType);
        return links != null ? links.getOwnerId() : null;
    }

    static boolean shouldSuppressNaturalRegen(@Nonnull TwNeedsConfig config,
                                              @Nonnull TwNeedsConfig.ValueSettings values,
                                              double hunger,
                                              double thirst) {
        return shouldSuppressNaturalRegen(config, values, hunger, thirst, TameworkRuntimeSettings.currentOrNull());
    }

    static boolean shouldSuppressNaturalRegen(@Nonnull TwNeedsConfig config,
                                              @Nonnull TwNeedsConfig.ValueSettings values,
                                              double hunger,
                                              double thirst,
                                              @Nullable TameworkRuntimeSettings settings) {
        if (!CompanionNeedsRuntimePolicy.isNeedsEnabled(config, settings)) {
            return false;
        }
        TwNeedsConfig.DamageSettings damageSettings = CompanionNeedsRuntimePolicy.resolveDamage(config, settings);
        if (damageSettings == null || !damageSettings.isEnabled()) {
            return false;
        }
        boolean atHungerMin = hunger <= values.getHungerMin() + EPSILON;
        boolean atThirstMin = thirst <= values.getThirstMin() + EPSILON;
        return atHungerMin || atThirstMin;
    }

    private static boolean hasResidualNaturalRegenSuppressionState(@Nonnull TameworkNeedsComponent component) {
        double baseline = normalizeRegenSuppressionBaselineHealth(component.getRegenSuppressionBaselineHealth());
        if (Math.abs(baseline - REGEN_SUPPRESSION_BASELINE_UNSET) > EPSILON
                || !Double.isFinite(component.getRegenSuppressionBaselineHealth())) {
            return true;
        }
        double allowance = normalizeRegenSuppressionAllowedHeal(component.getRegenSuppressionAllowedHeal());
        return allowance > EPSILON || !Double.isFinite(component.getRegenSuppressionAllowedHeal());
    }

    static double resolveNeedsDamageAmount(@Nullable TwNeedsConfig config,
                                           @Nullable TwNeedsConfig.ValueSettings values,
                                           double hunger,
                                           double thirst,
                                           long effectiveElapsedMs,
                                           double healthMax) {
        if (config == null || values == null || effectiveElapsedMs <= 0L) {
            return 0.0;
        }
        return resolveNeedsDamageAmount(
                config,
                values,
                hunger,
                thirst,
                effectiveElapsedMs,
                healthMax,
                TameworkRuntimeSettings.currentOrNull()
        );
    }

    static double resolveNeedsDamageAmount(@Nullable TwNeedsConfig config,
                                           @Nullable TwNeedsConfig.ValueSettings values,
                                           double hunger,
                                           double thirst,
                                           long effectiveElapsedMs,
                                           double healthMax,
                                           @Nullable TameworkRuntimeSettings settings) {
        if (config == null || values == null || effectiveElapsedMs <= 0L
                || !CompanionNeedsRuntimePolicy.isNeedsEnabled(config, settings)) {
            return 0.0;
        }
        TwNeedsConfig.DamageSettings damageSettings = CompanionNeedsRuntimePolicy.resolveDamage(config, settings);
        if (damageSettings == null || !damageSettings.isEnabled()) {
            return 0.0;
        }
        double elapsedMinutes = effectiveElapsedMs / MILLIS_PER_MINUTE;
        if (!Double.isFinite(elapsedMinutes) || elapsedMinutes <= 0.0) {
            return 0.0;
        }
        boolean atHungerMin = hunger <= values.getHungerMin() + EPSILON;
        boolean atThirstMin = thirst <= values.getThirstMin() + EPSILON;
        if (!atHungerMin && !atThirstMin) {
            return 0.0;
        }

        double starvationDamage = atHungerMin
                ? damageSettings.getStarvationDamagePerMinute() * elapsedMinutes
                : 0.0;
        double dehydrationDamage = atThirstMin
                ? damageSettings.getDehydrationDamagePerMinute() * elapsedMinutes
                : 0.0;
        if (!Double.isFinite(starvationDamage) || starvationDamage < 0.0) {
            starvationDamage = 0.0;
        }
        if (!Double.isFinite(dehydrationDamage) || dehydrationDamage < 0.0) {
            dehydrationDamage = 0.0;
        }
        double combined = switch (damageSettings.getDualNeedRule()) {
            case SUM_BOTH -> starvationDamage + dehydrationDamage;
            case USE_HIGHER_ONLY -> Math.max(starvationDamage, dehydrationDamage);
        };
        if (!Double.isFinite(combined) || combined <= 0.0) {
            return 0.0;
        }
        return switch (damageSettings.getModel()) {
            case MIN_ONLY_FLAT -> combined;
            case MIN_ONLY_PERCENT -> {
                if (!Double.isFinite(healthMax) || healthMax <= 0.0) {
                    yield 0.0;
                }
                yield healthMax * (combined / 100.0);
            }
        };
    }

    static NeedsDamagePoolResolution resolveNeedsDamagePooling(double calculatedDamageAmount,
                                                               double existingPendingNeedsDamage) {
        double safeDamageAmount = Double.isFinite(calculatedDamageAmount) && calculatedDamageAmount > 0.0
                ? calculatedDamageAmount
                : 0.0;
        double pendingCarry = normalizePendingNeedsDamage(existingPendingNeedsDamage);
        double totalDamage = safeDamageAmount + pendingCarry;
        if (!Double.isFinite(totalDamage) || totalDamage <= 0.0) {
            return new NeedsDamagePoolResolution(0.0, 0.0);
        }
        if (totalDamage < 1.0) {
            return new NeedsDamagePoolResolution(0.0, totalDamage);
        }
        double damageToApply = Math.floor(totalDamage);
        double pendingRemainder = totalDamage - damageToApply;
        return new NeedsDamagePoolResolution(damageToApply, normalizePendingNeedsDamage(pendingRemainder));
    }

    static double normalizePendingNeedsDamage(double pendingNeedsDamage) {
        if (!Double.isFinite(pendingNeedsDamage) || pendingNeedsDamage <= 0.0) {
            return 0.0;
        }
        return pendingNeedsDamage - Math.floor(pendingNeedsDamage);
    }

    @Nullable
    static CommandLinkedNpcDeathService.DeathCauseKind resolveNeedsDamageCauseHint(@Nullable TwNeedsConfig config,
                                                                                    @Nullable TwNeedsConfig.ValueSettings values,
                                                                                    double hunger,
                                                                                    double thirst,
                                                                                    long effectiveElapsedMs,
                                                                                    double healthMax) {
        if (config == null || values == null || effectiveElapsedMs <= 0L) {
            return null;
        }
        TwNeedsConfig.DamageSettings damageSettings = CompanionNeedsRuntimePolicy.resolveDamage(config);
        if (damageSettings == null || !damageSettings.isEnabled()) {
            return null;
        }
        double elapsedMinutes = effectiveElapsedMs / MILLIS_PER_MINUTE;
        if (!Double.isFinite(elapsedMinutes) || elapsedMinutes <= 0.0) {
            return null;
        }
        boolean atHungerMin = hunger <= values.getHungerMin() + EPSILON;
        boolean atThirstMin = thirst <= values.getThirstMin() + EPSILON;
        if (!atHungerMin && !atThirstMin) {
            return null;
        }
        double starvationDamage = atHungerMin
                ? sanitizeNeedsDamageAmount(damageSettings.getStarvationDamagePerMinute() * elapsedMinutes)
                : 0.0;
        double dehydrationDamage = atThirstMin
                ? sanitizeNeedsDamageAmount(damageSettings.getDehydrationDamagePerMinute() * elapsedMinutes)
                : 0.0;
        double selectedDamage = switch (damageSettings.getDualNeedRule()) {
            case SUM_BOTH -> starvationDamage + dehydrationDamage;
            case USE_HIGHER_ONLY -> Math.max(starvationDamage, dehydrationDamage);
        };
        if (selectedDamage <= 0.0) {
            return null;
        }
        if (damageSettings.getModel() == TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT
                && (!Double.isFinite(healthMax) || healthMax <= 0.0)) {
            return null;
        }
        if (starvationDamage > 0.0 && dehydrationDamage > 0.0) {
            if (damageSettings.getDualNeedRule() == TwNeedsConfig.DualNeedRule.USE_HIGHER_ONLY) {
                if (starvationDamage > dehydrationDamage) {
                    return CommandLinkedNpcDeathService.DeathCauseKind.STARVATION;
                }
                if (dehydrationDamage > starvationDamage) {
                    return CommandLinkedNpcDeathService.DeathCauseKind.DEHYDRATION;
                }
            }
            return CommandLinkedNpcDeathService.DeathCauseKind.STARVATION_AND_DEHYDRATION;
        }
        if (starvationDamage > 0.0) {
            return CommandLinkedNpcDeathService.DeathCauseKind.STARVATION;
        }
        if (dehydrationDamage > 0.0) {
            return CommandLinkedNpcDeathService.DeathCauseKind.DEHYDRATION;
        }
        return null;
    }

    private static void recordRecentNeedsDeathCause(@Nonnull Ref<EntityStore> npcRef,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nullable CommandLinkedNpcDeathService.DeathCauseKind causeKind,
                                                    double pooledDamageAmount) {
        if (causeKind == null || !Double.isFinite(pooledDamageAmount) || pooledDamageAmount <= MIN_DAMAGE_AMOUNT) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        RecentNeedsDeathCauseService.getInstance().record(npc.getUuid(), causeKind, System.currentTimeMillis());
    }

    private static double sanitizeNeedsDamageAmount(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }

    private static NeedsDamageExecutionResult applyNeedsDamage(@Nonnull Ref<EntityStore> npcRef,
                                                               @Nonnull Store<EntityStore> store,
                                                               @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                               double requestedDamageAmount,
                                                               boolean lethal) {
        if (!Double.isFinite(requestedDamageAmount) || requestedDamageAmount <= MIN_DAMAGE_AMOUNT) {
            return NeedsDamageExecutionResult.skipped(requestedDamageAmount);
        }
        DamageCause cause = resolveNeedsDamageCause();
        if (cause == null) {
            return NeedsDamageExecutionResult.skipped(requestedDamageAmount);
        }
        float appliedDamage = resolveAppliedDamageAmount(npcRef, store, requestedDamageAmount, lethal);
        if (!(appliedDamage > 0.0f)) {
            return NeedsDamageExecutionResult.skipped(requestedDamageAmount);
        }
        if (commandBuffer != null) {
            commandBuffer.run(bufferStore -> {
                if (!npcRef.isValid()) {
                    return;
                }
                Damage deferredDamage = new Damage(
                        new Damage.EnvironmentSource(NEEDS_DAMAGE_SOURCE_TYPE),
                        cause,
                        appliedDamage
                );
                DamageSystems.executeDamage(npcRef, bufferStore, deferredDamage);
            });
            return new NeedsDamageExecutionResult(
                    requestedDamageAmount,
                    appliedDamage,
                    appliedDamage,
                    false,
                    true
            );
        }
        Damage damage = new Damage(
                new Damage.EnvironmentSource(NEEDS_DAMAGE_SOURCE_TYPE),
                cause,
                appliedDamage
        );
        if (commandBuffer != null) {
            DamageSystems.executeDamage(npcRef, commandBuffer, damage);
        } else {
            DamageSystems.executeDamage(npcRef, store, damage);
        }
        boolean cancelled = damage.isCancelled();
        float finalDamageAmount = damage.getAmount();
        boolean applied = !cancelled && finalDamageAmount > 0.0f;
        return new NeedsDamageExecutionResult(
                requestedDamageAmount,
                appliedDamage,
                finalDamageAmount,
                cancelled,
                applied
        );
    }

    private static float resolveAppliedDamageAmount(@Nonnull Ref<EntityStore> npcRef,
                                                    @Nonnull Store<EntityStore> store,
                                                    double requestedDamageAmount,
                                                    boolean lethal) {
        double currentHealth = Double.NaN;
        if (!lethal) {
            EntityStatValue health = resolveHealthStat(npcRef, store);
            if (health != null) {
                currentHealth = health.get();
            }
        }
        return resolveAppliedDamageAmountFromHealth(requestedDamageAmount, lethal, currentHealth);
    }

    private static double resolveHealthMax(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return Double.NaN;
        }
        EntityStatValue health = resolveHealthStat(npcRef, store);
        if (health == null) {
            return Double.NaN;
        }
        return health.getMax();
    }

    private static double resolveCurrentHealth(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return Double.NaN;
        }
        EntityStatValue health = resolveHealthStat(npcRef, store);
        if (health == null) {
            return Double.NaN;
        }
        return health.get();
    }

    private static boolean applyNaturalRegenSuppression(@Nonnull Ref<EntityStore> npcRef,
                                                        @Nonnull Store<EntityStore> store,
                                                        @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                        @Nonnull TameworkNeedsComponent component,
                                                        boolean suppressNaturalRegen,
                                                        boolean enforceNonLethalFloor) {
        boolean changed = syncNaturalRegenHardBlockDamageTimestamp(
                npcRef,
                store,
                commandBuffer,
                suppressNaturalRegen
        );
        EntityStatContext healthContext = resolveHealthStatContext(npcRef, store);
        if (healthContext == null || healthContext.value == null) {
            if (!suppressNaturalRegen) {
                changed = resetNaturalRegenSuppression(component) || changed;
            }
            return changed;
        }

        double currentHealth = healthContext.value.get();
        if (!Double.isFinite(currentHealth)) {
            if (!suppressNaturalRegen) {
                changed = resetNaturalRegenSuppression(component) || changed;
            }
            return changed;
        }
        double currentManagedHealth = normalizeManagedHealth(component.getLastManagedHealth());
        if (!suppressNaturalRegen) {
            changed = resetNaturalRegenSuppression(component) || changed;
            double targetManagedHealth = normalizeManagedHealth(currentHealth);
            if (Math.abs(component.getLastManagedHealth() - targetManagedHealth) > EPSILON
                    || !Double.isFinite(component.getLastManagedHealth())) {
                component.setLastManagedHealth(targetManagedHealth);
                changed = true;
            }
            return changed;
        }

        NaturalRegenSuppressionResolution resolution = resolveNaturalRegenSuppression(
                suppressNaturalRegen,
                currentHealth,
                component.getRegenSuppressionBaselineHealth(),
                component.getRegenSuppressionAllowedHeal(),
                currentManagedHealth
        );

        double healthOverflowToRemove = resolution.healthOverflowToRemove;
        if (enforceNonLethalFloor) {
            double maxRemovable = Math.max(0.0, currentHealth - NON_LETHAL_MIN_REMAINING_HEALTH);
            healthOverflowToRemove = Math.min(healthOverflowToRemove, maxRemovable);
        }
        if (healthOverflowToRemove > EPSILON) {
            healthContext.statMap.addStatValue(healthContext.healthIndex, (float) (-healthOverflowToRemove));
            currentHealth = healthContext.value.get();
            changed = true;
        }

        double targetBaseline = Double.isFinite(currentHealth)
                ? currentHealth
                : REGEN_SUPPRESSION_BASELINE_UNSET;
        double targetAllowedHeal = suppressNaturalRegen ? resolution.nextAllowedExternalHeal : 0.0;

        if (Math.abs(component.getRegenSuppressionBaselineHealth() - targetBaseline) > EPSILON
                || !Double.isFinite(component.getRegenSuppressionBaselineHealth())) {
            component.setRegenSuppressionBaselineHealth(targetBaseline);
            changed = true;
        }
        if (Math.abs(component.getRegenSuppressionAllowedHeal() - targetAllowedHeal) > EPSILON
                || !Double.isFinite(component.getRegenSuppressionAllowedHeal())) {
            component.setRegenSuppressionAllowedHeal(targetAllowedHeal);
            changed = true;
        }
        double targetManagedHealth = normalizeManagedHealth(currentHealth);
        if (Math.abs(component.getLastManagedHealth() - targetManagedHealth) > EPSILON
                || !Double.isFinite(component.getLastManagedHealth())) {
            component.setLastManagedHealth(targetManagedHealth);
            changed = true;
        }
        return changed;
    }

    private static boolean syncNaturalRegenHardBlockDamageTimestamp(@Nonnull Ref<EntityStore> npcRef,
                                                                    @Nonnull Store<EntityStore> store,
                                                                    @Nullable CommandBuffer<EntityStore> commandBuffer,
                                                                    boolean suppressNaturalRegen) {
        ComponentType<EntityStore, DamageDataComponent> damageDataType = DamageDataComponent.getComponentType();
        if (damageDataType == null) {
            return false;
        }
        DamageDataComponent damageData = store.getComponent(npcRef, damageDataType);
        if (damageData == null) {
            return false;
        }
        Instant now = resolveCurrentInstant(store);
        Instant currentLastDamageTime = damageData.getLastDamageTime();
        Instant nextLastDamageTime = resolveHardRegenBlockLastDamageTime(
                suppressNaturalRegen,
                currentLastDamageTime,
                now
        );
        if (nextLastDamageTime.equals(currentLastDamageTime)) {
            return false;
        }
        damageData.setLastDamageTime(nextLastDamageTime);
        putComponent(npcRef, store, commandBuffer, damageDataType, damageData);
        return true;
    }

    @Nonnull
    static Instant resolveHardRegenBlockLastDamageTime(boolean suppressNaturalRegen,
                                                       @Nullable Instant lastDamageTime,
                                                       @Nonnull Instant now) {
        Instant currentLastDamageTime = lastDamageTime == null ? Instant.MIN : lastDamageTime;
        if (!suppressNaturalRegen) {
            return currentLastDamageTime.isAfter(now) ? now : currentLastDamageTime;
        }
        Instant minimumFuture = safePlusSeconds(now, REGEN_HARD_BLOCK_MIN_FUTURE_SECONDS);
        if (currentLastDamageTime.isBefore(minimumFuture)) {
            return safePlusSeconds(now, REGEN_HARD_BLOCK_TARGET_FUTURE_SECONDS);
        }
        return currentLastDamageTime;
    }

    private static boolean resetNaturalRegenSuppression(@Nonnull TameworkNeedsComponent component) {
        boolean changed = false;
        if (Math.abs(component.getRegenSuppressionBaselineHealth() - REGEN_SUPPRESSION_BASELINE_UNSET) > EPSILON
                || !Double.isFinite(component.getRegenSuppressionBaselineHealth())) {
            component.setRegenSuppressionBaselineHealth(REGEN_SUPPRESSION_BASELINE_UNSET);
            changed = true;
        }
        if (Math.abs(component.getRegenSuppressionAllowedHeal()) > EPSILON
                || !Double.isFinite(component.getRegenSuppressionAllowedHeal())) {
            component.setRegenSuppressionAllowedHeal(0.0);
            changed = true;
        }
        return changed;
    }

    static NaturalRegenSuppressionResolution resolveNaturalRegenSuppression(boolean suppressNaturalRegen,
                                                                            double currentHealth,
                                                                            double baselineHealth,
                                                                            double allowedExternalHeal,
                                                                            double managedHealthAnchor) {
        if (!Double.isFinite(currentHealth)) {
            return new NaturalRegenSuppressionResolution(
                    REGEN_SUPPRESSION_BASELINE_UNSET,
                    0.0,
                    0.0
            );
        }
        if (!suppressNaturalRegen) {
            return new NaturalRegenSuppressionResolution(
                    REGEN_SUPPRESSION_BASELINE_UNSET,
                    0.0,
                    0.0
            );
        }

        double baseline = normalizeRegenSuppressionBaselineHealth(baselineHealth);
        if (baseline == REGEN_SUPPRESSION_BASELINE_UNSET) {
            double managedAnchor = normalizeManagedHealth(managedHealthAnchor);
            if (managedAnchor == REGEN_SUPPRESSION_BASELINE_UNSET) {
                baseline = currentHealth;
            } else {
                baseline = Math.min(currentHealth, managedAnchor);
            }
        }
        double allowance = normalizeRegenSuppressionAllowedHeal(allowedExternalHeal);
        double maxAllowedHealth = baseline + allowance;
        double clampedHealth = Math.min(currentHealth, maxAllowedHealth);
        double healthOverflowToRemove = Math.max(0.0, currentHealth - clampedHealth);

        double gainedHealth = Math.max(0.0, clampedHealth - baseline);
        double consumedAllowance = Math.min(allowance, gainedHealth);
        double remainingAllowance = allowance - consumedAllowance;

        return new NaturalRegenSuppressionResolution(
                clampedHealth,
                normalizeRegenSuppressionAllowedHeal(remainingAllowance),
                healthOverflowToRemove
        );
    }

    private static double normalizeRegenSuppressionBaselineHealth(double baselineHealth) {
        // Legacy saved components may miss this field and deserialize as 0.0; treat as unset to avoid false clamps.
        if (!Double.isFinite(baselineHealth) || baselineHealth <= EPSILON) {
            return REGEN_SUPPRESSION_BASELINE_UNSET;
        }
        return baselineHealth;
    }

    private static double normalizeRegenSuppressionAllowedHeal(double allowedExternalHeal) {
        if (!Double.isFinite(allowedExternalHeal) || allowedExternalHeal <= 0.0) {
            return 0.0;
        }
        return Math.min(MAX_REGEN_SUPPRESSION_ALLOWED_HEAL, allowedExternalHeal);
    }

    private static double resolveNeedsDecayMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable Store<EntityStore> store) {
        double value = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                NEEDS_DECAY_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return value;
    }

    private static double normalizeManagedHealth(double managedHealth) {
        if (!Double.isFinite(managedHealth) || managedHealth <= EPSILON) {
            return REGEN_SUPPRESSION_BASELINE_UNSET;
        }
        return managedHealth;
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

    @Nonnull
    private static Instant resolveCurrentInstant(@Nonnull Store<EntityStore> store) {
        com.hypixel.hytale.server.core.modules.time.TimeResource timeResource =
                (com.hypixel.hytale.server.core.modules.time.TimeResource) store.getResource(
                        com.hypixel.hytale.server.core.modules.time.TimeResource.getResourceType()
                );
        if (timeResource != null) {
            return timeResource.getNow();
        }
        return Instant.now();
    }

    @Nonnull
    private static Instant safePlusSeconds(@Nonnull Instant base, long seconds) {
        try {
            return base.plusSeconds(seconds);
        } catch (RuntimeException ignored) {
            return base;
        }
    }

    private static boolean isNeedsDamageDiagnosticsEnabled() {
        if (!LOGGER.isLoggable(Level.INFO)) {
            return false;
        }
        Tamework plugin = Tamework.getInstance();
        return plugin != null && plugin.isDebugNeedsDamageDiagnosticsEnabled();
    }

    @Nonnull
    private static String resolveNpcId(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return "<invalid>";
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid().toString();
        }
        return npcRef.toString();
    }

    private static double resolveHealthDelta(double before, double after) {
        if (!Double.isFinite(before) || !Double.isFinite(after)) {
            return 0.0;
        }
        return before - after;
    }

    private static void logNeedsDamageDiagnostics(@Nonnull String npcId,
                                                  @Nullable String roleId,
                                                  @Nullable String configId,
                                                  long nowMs,
                                                  long lastUpdateMs,
                                                  long effectiveElapsedMs,
                                                  double hungerBefore,
                                                  double hungerAfter,
                                                  double thirstBefore,
                                                  double thirstAfter,
                                                  double healthMax,
                                                  double healthBeforeTick,
                                                  double healthBeforeSuppression,
                                                  double healthAfterSuppression,
                                                  double healthBeforeDamage,
                                                  double healthAfterDamage,
                                                  double calculatedDamageAmount,
                                                  double pendingBefore,
                                                  double pooledDamageAmount,
                                                  double pendingAfter,
                                                  boolean suppressNaturalRegen,
                                                  double baselineBefore,
                                                  double baselineAfter,
                                                  double allowedHealBefore,
                                                  double allowedHealAfter,
                                                  double suppressionHealthDelta,
                                                  @Nonnull NeedsDamageExecutionResult damageResult,
                                                  boolean externalDamageSuspected,
                                                  @Nonnull String recentAttackerLabel,
                                                  boolean componentChanged,
                                                  boolean happinessChanged) {
        LOGGER.log(Level.INFO, String.format(
                "Needs damage tick: npc=%s role=%s config=%s nowMs=%d lastMs=%d effectiveMs=%d "
                        + "hunger=%.3f->%.3f thirst=%.3f->%.3f maxHp=%.3f hpTick=%.3f hpSuppress=%.3f->%.3f "
                        + "hpDamage=%.3f->%.3f suppress=%s baseline=%.3f->%.3f allowance=%.3f->%.3f "
                        + "suppressionDelta=%.3f damageRaw=%.6f pending=%.6f->%.6f apply=%.6f "
                        + "damageRequested=%.6f damagePlanned=%.6f damageFinal=%.6f damageCancelled=%s damageApplied=%s "
                        + "externalDamageSuspected=%s recentAttacker=%s "
                        + "componentChanged=%s happinessChanged=%s",
                npcId,
                safeLabel(roleId),
                safeLabel(configId),
                nowMs,
                lastUpdateMs,
                effectiveElapsedMs,
                hungerBefore,
                hungerAfter,
                thirstBefore,
                thirstAfter,
                healthMax,
                healthBeforeTick,
                healthBeforeSuppression,
                healthAfterSuppression,
                healthBeforeDamage,
                healthAfterDamage,
                suppressNaturalRegen,
                baselineBefore,
                baselineAfter,
                allowedHealBefore,
                allowedHealAfter,
                suppressionHealthDelta,
                calculatedDamageAmount,
                pendingBefore,
                pendingAfter,
                pooledDamageAmount,
                damageResult.requestedDamageAmount,
                damageResult.plannedDamageAmount,
                damageResult.finalDamageAmount,
                damageResult.cancelled,
                damageResult.applied,
                externalDamageSuspected,
                recentAttackerLabel,
                componentChanged,
                happinessChanged
        ));
    }

    private static boolean shouldLogNeedsDamageDiagnostics(boolean suppressNaturalRegen,
                                                           boolean regenSuppressionChanged,
                                                           double suppressionHealthDelta,
                                                           double calculatedDamageAmount,
                                                           double pooledDamageAmount,
                                                           @Nonnull NeedsDamageExecutionResult damageResult) {
        if (regenSuppressionChanged || suppressionHealthDelta > EPSILON) {
            return true;
        }
        if (suppressNaturalRegen) {
            return true;
        }
        if (calculatedDamageAmount > MIN_DAMAGE_AMOUNT || pooledDamageAmount > MIN_DAMAGE_AMOUNT) {
            return true;
        }
        return damageResult.applied
                || damageResult.cancelled
                || damageResult.plannedDamageAmount > MIN_DAMAGE_AMOUNT
                || damageResult.finalDamageAmount > MIN_DAMAGE_AMOUNT;
    }

    private static boolean shouldLogNeedsDamageDiagnostics(boolean suppressNaturalRegen,
                                                           boolean regenSuppressionChanged,
                                                           double suppressionHealthDelta,
                                                           double calculatedDamageAmount,
                                                           double pooledDamageAmount,
                                                           @Nonnull NeedsDamageExecutionResult damageResult,
                                                           boolean externalDamageSuspected) {
        if (externalDamageSuspected) {
            return true;
        }
        return shouldLogNeedsDamageDiagnostics(
                suppressNaturalRegen,
                regenSuppressionChanged,
                suppressionHealthDelta,
                calculatedDamageAmount,
                pooledDamageAmount,
                damageResult
        );
    }

    private static boolean isExternalDamageSuspected(double currentHealth,
                                                     double previousManagedHealth,
                                                     boolean suppressNaturalRegen,
                                                     double pooledDamageAmount) {
        if (suppressNaturalRegen || pooledDamageAmount > MIN_DAMAGE_AMOUNT) {
            return false;
        }
        if (!Double.isFinite(currentHealth)
                || previousManagedHealth == REGEN_SUPPRESSION_BASELINE_UNSET
                || !Double.isFinite(previousManagedHealth)) {
            return false;
        }
        return currentHealth + EPSILON < previousManagedHealth;
    }

    @Nonnull
    private static String resolveRecentAttackerLabel(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return "<none>";
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        DamageTargetMemoryService.RecentAttackerSnapshot snapshot = DamageTargetMemoryService.getInstance()
                .getRecentAttacker(npcUuid, 60_000L, System.currentTimeMillis());
        if (snapshot == null) {
            return "<none>";
        }
        String attackerName = snapshot.attackerName();
        if (attackerName != null && !attackerName.isBlank()) {
            return snapshot.attackerKind() + ":" + attackerName;
        }
        return snapshot.attackerKind().name();
    }

    @Nonnull
    private static String safeLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    static float resolveAppliedDamageAmountFromHealth(double requestedDamageAmount,
                                                      boolean lethal,
                                                      double currentHealth) {
        float requested = (float) requestedDamageAmount;
        if (!lethal && Double.isFinite(currentHealth)) {
            float maxAllowed = Math.max(0.0f, (float) (currentHealth - NON_LETHAL_MIN_REMAINING_HEALTH));
            requested = Math.min(requested, maxAllowed);
        }
        return requested > 0.0f ? requested : 0.0f;
    }

    @Nullable
    private static EntityStatValue resolveHealthStat(@Nonnull Ref<EntityStore> npcRef,
                                                     @Nonnull Store<EntityStore> store) {
        EntityStatContext context = resolveHealthStatContext(npcRef, store);
        return context == null ? null : context.value;
    }

    @Nullable
    private static EntityStatContext resolveHealthStatContext(@Nonnull Ref<EntityStore> npcRef,
                                                              @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, EntityStatMap> statMapType = EntityStatMap.getComponentType();
        if (statMapType == null) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statMapType);
        if (statMap == null) {
            return null;
        }
        if (EntityStatType.getAssetMap() == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex(HEALTH_STAT_ID);
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue healthValue = statMap.get(healthIndex);
        if (healthValue == null) {
            return null;
        }
        return new EntityStatContext(statMap, healthIndex, healthValue);
    }

    @Nullable
    private static DamageCause resolveNeedsDamageCause() {
        if (DamageCause.ENVIRONMENT != null) {
            return DamageCause.ENVIRONMENT;
        }
        if (DamageCause.PHYSICAL != null) {
            return DamageCause.PHYSICAL;
        }
        return DamageCause.COMMAND;
    }

    private static long resolveNowMs(@Nonnull TwNeedsConfig config, @Nullable Store<EntityStore> store) {
        return switch (config.getTiming().getTimerBasis()) {
            case WORLD_TIME_SCALED -> BreedingTimeService.resolveCurrentTimeMs(store);
            case REAL_TIME -> CompanionRuntimeClock.nowMs();
        };
    }

    private static double sanitizeAndClamp(double value, double fallback, double min, double max) {
        double safe = Double.isFinite(value) ? value : fallback;
        return clamp(safe, min, max);
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

    static final class NeedsDamagePoolResolution {
        private final double damageToApply;
        private final double pendingDamageRemainder;

        private NeedsDamagePoolResolution(double damageToApply, double pendingDamageRemainder) {
            this.damageToApply = damageToApply;
            this.pendingDamageRemainder = pendingDamageRemainder;
        }

        double getDamageToApply() {
            return damageToApply;
        }

        double getPendingDamageRemainder() {
            return pendingDamageRemainder;
        }
    }

    static final class NaturalRegenSuppressionResolution {
        private final double nextBaselineHealth;
        private final double nextAllowedExternalHeal;
        private final double healthOverflowToRemove;

        private NaturalRegenSuppressionResolution(double nextBaselineHealth,
                                                  double nextAllowedExternalHeal,
                                                  double healthOverflowToRemove) {
            this.nextBaselineHealth = nextBaselineHealth;
            this.nextAllowedExternalHeal = nextAllowedExternalHeal;
            this.healthOverflowToRemove = healthOverflowToRemove;
        }

        double getNextBaselineHealth() {
            return nextBaselineHealth;
        }

        double getNextAllowedExternalHeal() {
            return nextAllowedExternalHeal;
        }

        double getHealthOverflowToRemove() {
            return healthOverflowToRemove;
        }
    }

    private static final class NeedsDamageExecutionResult {
        private final double requestedDamageAmount;
        private final double plannedDamageAmount;
        private final double finalDamageAmount;
        private final boolean cancelled;
        private final boolean applied;

        private NeedsDamageExecutionResult(double requestedDamageAmount,
                                           double plannedDamageAmount,
                                           double finalDamageAmount,
                                           boolean cancelled,
                                           boolean applied) {
            this.requestedDamageAmount = requestedDamageAmount;
            this.plannedDamageAmount = plannedDamageAmount;
            this.finalDamageAmount = finalDamageAmount;
            this.cancelled = cancelled;
            this.applied = applied;
        }

        @Nonnull
        private static NeedsDamageExecutionResult skipped(double requestedDamageAmount) {
            return new NeedsDamageExecutionResult(requestedDamageAmount, 0.0, 0.0, false, false);
        }
    }

    private static final class EntityStatContext {
        private final EntityStatMap statMap;
        private final int healthIndex;
        private final EntityStatValue value;

        private EntityStatContext(@Nonnull EntityStatMap statMap,
                                  int healthIndex,
                                  @Nonnull EntityStatValue value) {
            this.statMap = statMap;
            this.healthIndex = healthIndex;
            this.value = value;
        }
    }

}
