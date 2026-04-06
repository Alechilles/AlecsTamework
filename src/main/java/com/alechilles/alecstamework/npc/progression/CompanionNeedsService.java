package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies hunger/thirst progression and triggers equilibrium-happiness reconciliation.
 */
public final class CompanionNeedsService {
    private static final double EPSILON = 0.000001;
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final double MILLIS_PER_MINUTE = SECONDS_PER_MINUTE * 1000.0;
    private static final String HEALTH_STAT_ID = "Health";
    private static final double NON_LETHAL_MIN_REMAINING_HEALTH = 1.0;
    private static final double MIN_DAMAGE_AMOUNT = 0.0001;
    private static final double REGEN_SUPPRESSION_BASELINE_UNSET = -1.0;
    private static final double MAX_REGEN_SUPPRESSION_ALLOWED_HEAL = 10_000.0;
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
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return null;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, store.getComponent(npcRef, needsType));
        if (config == null || !config.isEnabled()) {
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
            store.putComponent(npcRef, needsType, created);
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
        if (Math.abs(existing.getRegenSuppressionBaselineHealth() - regenSuppressionBaseline) > EPSILON
                || !Double.isFinite(existing.getRegenSuppressionBaselineHealth())) {
            existing.setRegenSuppressionBaselineHealth(regenSuppressionBaseline);
            changed = true;
        }
        double regenSuppressionAllowedHeal = normalizeRegenSuppressionAllowedHeal(
                existing.getRegenSuppressionAllowedHeal()
        );
        if (Math.abs(existing.getRegenSuppressionAllowedHeal() - regenSuppressionAllowedHeal) > EPSILON
                || !Double.isFinite(existing.getRegenSuppressionAllowedHeal())) {
            existing.setRegenSuppressionAllowedHeal(regenSuppressionAllowedHeal);
            changed = true;
        }
        if (existing.getLastUpdateMs() <= 0L) {
            existing.setLastUpdateMs(nowMs);
            changed = true;
        }
        if (existing.getLastPassiveSweepMs() <= 0L) {
            existing.setLastPassiveSweepMs(nowMs);
            changed = true;
        }
        if (changed) {
            store.putComponent(npcRef, needsType, existing);
        }
        return existing;
    }

    /**
     * Runs a needs progression step: decay plus equilibrium happiness reconciliation.
     */
    public static boolean tickNeeds(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable String roleId) {
        return runNeedsUpdate(npcRef, store, roleId, 0.0, 0.0, false, null);
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
        if (config == null || !config.isEnabled()) {
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
            EntityStatValue health = resolveHealthStat(npcRef, store);
            if (health != null && Double.isFinite(health.get())) {
                baseline = health.get();
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

    static boolean runNeedsUpdate(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String roleId,
                                  double explicitHungerGain,
                                  double explicitThirstGain,
                                  boolean includeConfiguredManualGains,
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
        if (config == null || !config.isEnabled()) {
            return false;
        }
        component = ensureNeedsComponent(npcRef, store, roleId);
        if (component == null) {
            return false;
        }
        long nowMs = resolveNowMs(config, store);
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(component.getHunger(), values.getHungerDefault(), values.getHungerMin(), values.getHungerMax());
        double thirst = sanitizeAndClamp(component.getThirst(), values.getThirstDefault(), values.getThirstMin(), values.getThirstMax());
        long lastUpdateMs = component.getLastUpdateMs();
        UUID ownerId = resolveOwnerId(npcRef, store);
        long effectiveElapsedMs = resolveEffectiveElapsedMs(config, ownerId, lastUpdateMs, nowMs);

        boolean componentChanged = false;
        if (effectiveElapsedMs > 0L) {
            double elapsedMinutes = effectiveElapsedMs / MILLIS_PER_MINUTE;
            TwNeedsConfig.DecaySettings decay = config.getDecay();
            double hungerDecay = decay.getHungerPerMinute() * elapsedMinutes;
            double thirstDecay = decay.getThirstPerMinute() * elapsedMinutes;
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
        boolean damageApplied = false;
        double pooledDamageAmount = needsDamagePool.getDamageToApply();
        if (pooledDamageAmount > MIN_DAMAGE_AMOUNT) {
            damageApplied = applyNeedsDamage(npcRef, store, pooledDamageAmount, config.getDamage().isLethal());
        }
        boolean regenSuppressionChanged = applyNaturalRegenSuppression(
                npcRef,
                store,
                component,
                shouldSuppressNaturalRegen(config, values, hunger, thirst)
        );
        if (regenSuppressionChanged) {
            componentChanged = true;
        }

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
            store.putComponent(npcRef, needsType, component);
        }
        boolean happinessChanged = CompanionHappinessService.reconcile(npcRef, store);
        return componentChanged || happinessChanged || damageApplied;
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
        TwNeedsConfig.TickPolicySettings tickPolicy = config.getTickPolicy();
        if (ownerId == null) {
            return nowMs - lastUpdateMs;
        }
        return OwnerPresenceTimelineService.get().resolveEffectiveElapsedMs(ownerId, lastUpdateMs, nowMs, tickPolicy);
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
        TwNeedsConfig.DamageSettings damageSettings = config.getDamage();
        if (damageSettings == null || !damageSettings.isEnabled()) {
            return false;
        }
        boolean atHungerMin = hunger <= values.getHungerMin() + EPSILON;
        boolean atThirstMin = thirst <= values.getThirstMin() + EPSILON;
        return atHungerMin || atThirstMin;
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
        TwNeedsConfig.DamageSettings damageSettings = config.getDamage();
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

    private static boolean applyNeedsDamage(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Store<EntityStore> store,
                                            double requestedDamageAmount,
                                            boolean lethal) {
        if (!Double.isFinite(requestedDamageAmount) || requestedDamageAmount <= MIN_DAMAGE_AMOUNT) {
            return false;
        }
        DamageCause cause = resolveNeedsDamageCause();
        if (cause == null) {
            return false;
        }
        float appliedDamage = resolveAppliedDamageAmount(npcRef, store, requestedDamageAmount, lethal);
        if (!(appliedDamage > 0.0f)) {
            return false;
        }
        Damage damage = new Damage(
                new Damage.EnvironmentSource(NEEDS_DAMAGE_SOURCE_TYPE),
                cause,
                appliedDamage
        );
        DamageSystems.executeDamage(npcRef, store, damage);
        return !damage.isCancelled() && damage.getAmount() > 0.0f;
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

    private static boolean applyNaturalRegenSuppression(@Nonnull Ref<EntityStore> npcRef,
                                                        @Nonnull Store<EntityStore> store,
                                                        @Nonnull TameworkNeedsComponent component,
                                                        boolean suppressNaturalRegen) {
        EntityStatContext healthContext = resolveHealthStatContext(npcRef, store);
        if (healthContext == null || healthContext.value == null) {
            if (!suppressNaturalRegen) {
                return resetNaturalRegenSuppression(component);
            }
            return false;
        }

        double currentHealth = healthContext.value.get();
        if (!Double.isFinite(currentHealth)) {
            if (!suppressNaturalRegen) {
                return resetNaturalRegenSuppression(component);
            }
            return false;
        }

        NaturalRegenSuppressionResolution resolution = resolveNaturalRegenSuppression(
                suppressNaturalRegen,
                currentHealth,
                component.getRegenSuppressionBaselineHealth(),
                component.getRegenSuppressionAllowedHeal()
        );

        boolean changed = false;
        if (resolution.healthOverflowToRemove > EPSILON) {
            healthContext.statMap.addStatValue(healthContext.healthIndex, (float) (-resolution.healthOverflowToRemove));
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
        return changed;
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
                                                                            double allowedExternalHeal) {
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
            baseline = currentHealth;
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
        if (!Double.isFinite(baselineHealth) || baselineHealth < 0.0) {
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
