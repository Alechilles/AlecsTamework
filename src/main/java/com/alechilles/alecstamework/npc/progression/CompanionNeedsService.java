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

        double needsDamageAmount = resolveNeedsDamageAmount(config, values, hunger, thirst, effectiveElapsedMs);
        boolean damageApplied = false;
        if (needsDamageAmount > MIN_DAMAGE_AMOUNT) {
            damageApplied = applyNeedsDamage(npcRef, store, needsDamageAmount, config.getDamage().isLethal());
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

    static double resolveNeedsDamageAmount(@Nullable TwNeedsConfig config,
                                           @Nullable TwNeedsConfig.ValueSettings values,
                                           double hunger,
                                           double thirst,
                                           long effectiveElapsedMs) {
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
        return switch (damageSettings.getDualNeedRule()) {
            case SUM_BOTH -> starvationDamage + dehydrationDamage;
            case USE_HIGHER_ONLY -> Math.max(starvationDamage, dehydrationDamage);
        };
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
        return statMap.get(healthIndex);
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
            case REAL_TIME -> System.currentTimeMillis();
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

}
