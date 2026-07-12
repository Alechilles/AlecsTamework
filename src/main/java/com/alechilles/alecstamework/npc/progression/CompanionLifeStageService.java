package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Manages companion life-stage state and growth scaling progression.
 */
public final class CompanionLifeStageService {
    public static final String STAGE_BABY = "Baby";
    public static final String STAGE_ADOLESCENT = "Adolescent";
    public static final String STAGE_ADULT = "Adult";

    private static final long GROWTH_TICK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(3);
    private static final long INITIAL_SCALE_RETRY_INTERVAL_MS = 10L;
    private static final int INITIAL_SCALE_MAX_RETRIES = 120;
    private static final int ROLE_CHANGE_SCALE_MAX_RETRIES = 120;
    private static final int ROLE_CHANGE_SCALE_SETTLE_RETRIES = 20;
    private static final double DEFAULT_BABY_SCALE_FACTOR = 0.33;
    private static final double DEFAULT_ADOLESCENT_SCALE_FACTOR = 0.66;
    private static final double MIN_SCALE = 0.10;
    private static final Set<UUID> ACTIVE_GROWTH_TICKERS = ConcurrentHashMap.newKeySet();

    /** Controls whether offspring initialization may reinterpret a missing planned family. */
    public enum LifecycleFamilyResolution {
        CURRENT_CONFIG_FALLBACK,
        PLANNED_SELECTION_ONLY
    }

    private CompanionLifeStageService() {
    }

    public static void ensureLifeStageComponent(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store) {
        ensureLifeStageComponent(npcRef, store, null);
    }

    public static void ensureLifeStageComponent(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nullable String roleIdHint) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkLifeStageComponent existing = store.getComponent(npcRef, type);
        String roleId = roleIdHint;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (existing == null) {
            TameworkLifeStageComponent created = createInitialLifeStageComponent(npcRef, store, roleId);
            store.putComponent(npcRef, type, created);
            return;
        }
        if (roleId != null
                && !roleId.isBlank()
                && isUninitializedAdultComponent(existing)) {
            TameworkLifeStageComponent replacement = createInitialLifeStageComponent(npcRef, store, roleId);
            String replacementStage = normalizeStage(replacement.getStage());
            if (STAGE_BABY.equals(replacementStage) || STAGE_ADOLESCENT.equals(replacementStage)) {
                store.putComponent(npcRef, type, replacement);
                return;
            }
        }
        boolean changed = normalizeComponentDefaults(existing, npcRef, store);
        if (changed) {
            store.putComponent(npcRef, type, existing);
        }
    }

    public static void initializeOffspringLifeStage(@Nullable Ref<EntityStore> childRef,
                                                    @Nullable NPCEntity childNpc,
                                                    @Nullable Store<EntityStore> store,
                                                    @Nullable String spawnedRoleId,
                                                    @Nullable TwBreedingConfig breedingConfig,
                                                    @Nullable String selectedAdultRoleId,
                                                    @Nullable TwBreedingConfig.RoleFamily preResolvedFamily) {
        initializeOffspringLifeStage(
                childRef,
                childNpc,
                store,
                spawnedRoleId,
                breedingConfig,
                selectedAdultRoleId,
                preResolvedFamily,
                LifecycleFamilyResolution.CURRENT_CONFIG_FALLBACK
        );
    }

    /** Initializes offspring while honoring the caller's durable family-resolution policy. */
    public static void initializeOffspringLifeStage(@Nullable Ref<EntityStore> childRef,
                                                    @Nullable NPCEntity childNpc,
                                                    @Nullable Store<EntityStore> store,
                                                    @Nullable String spawnedRoleId,
                                                    @Nullable TwBreedingConfig breedingConfig,
                                                    @Nullable String selectedAdultRoleId,
                                                    @Nullable TwBreedingConfig.RoleFamily preResolvedFamily,
                                                    @Nonnull LifecycleFamilyResolution familyResolution) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }

        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        String adultRoleId = resolveOffspringAdultRoleId(
                spawnedRoleId, selectedAdultRoleId, preResolvedFamily
        );
        double adultScale = resolveAdultScale(childRef, store, adultRoleId);
        LifecycleComputation lifecycle = computeLifecycle(
                nowMs,
                adultScale,
                breedingConfig,
                preResolvedFamily,
                spawnedRoleId,
                store,
                familyResolution
        );
        TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                STAGE_BABY,
                nowMs,
                lifecycle.adolescentAtMs(),
                lifecycle.adultAtMs(),
                lifecycle.fullyGrownAtMs(),
                lifecycle.babyStartScale(),
                lifecycle.adolescentStartScale(),
                lifecycle.adolescentSwitchScale(),
                lifecycle.adultStartScale(),
                lifecycle.adultSwitchScale(),
                lifecycle.adultFinalScale(),
                true
        );
        if (preResolvedFamily != null) {
            component.setAdultRoleId(adultRoleId);
            component.setBabyRoleId(preResolvedFamily.getBabyRoleId());
            component.setAdolescentRoleId(preResolvedFamily.getAdolescentRoleId());
        } else {
            component.setAdultRoleId(adultRoleId);
            component.setBabyRoleId(spawnedRoleId);
        }
        store.putComponent(childRef, type, component);
        double initialScale = lifecycle.babyStartScale();
        CompanionModelScaleService.applyScale(childRef, childNpc, store, initialScale);
        scheduleInitialScaleRetry(childRef, childNpc, store, INITIAL_SCALE_MAX_RETRIES, null);
        ensureGrowthTickScheduled(childRef, childNpc, store);
    }

    @Nullable
    private static String resolveAdultRoleId(@Nullable String spawnedRoleId,
                                             @Nullable TwBreedingConfig.RoleFamily family) {
        if (family != null && family.getAdultRoleId() != null && !family.getAdultRoleId().isBlank()) {
            return family.getAdultRoleId();
        }
        return spawnedRoleId;
    }

    @Nullable
    static String resolveOffspringAdultRoleId(@Nullable String spawnedRoleId,
                                              @Nullable String selectedAdultRoleId,
                                              @Nullable TwBreedingConfig.RoleFamily family) {
        return selectedAdultRoleId != null && !selectedAdultRoleId.isBlank()
                ? selectedAdultRoleId
                : resolveAdultRoleId(spawnedRoleId, family);
    }

    @Nullable
    private static TwBreedingConfig.RoleFamily resolveLifecycleFamilyForProgression(
            @Nullable TwBreedingConfig config,
            @Nullable String roleId) {
        if (config == null || roleId == null || roleId.isBlank()) {
            return null;
        }
        TwBreedingConfig.RoleFamily lineFamily = config.resolveLifecycleLineFamilyForRole(roleId);
        return lineFamily != null ? lineFamily : config.resolveLifecycleFamilyForRole(roleId);
    }

    private static TameworkLifeStageComponent createInitialLifeStageComponent(Ref<EntityStore> npcRef,
                                                                              Store<EntityStore> store,
                                                                              @Nullable String roleId) {
        String stage = STAGE_ADULT;
        TwBreedingConfig.RoleFamily family = null;
        String adultRoleId = roleId;

        if (roleId != null && !roleId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveForRole(roleId);
            if (config != null && config.isEnabled()) {
                TwBreedingConfig.OffspringLifecycleSettings lifecycleSettings =
                        config.resolveOffspringLifecycle(roleId);
                if (lifecycleSettings != null && lifecycleSettings.isEnabled()) {
                    family = resolveLifecycleFamilyForProgression(config, roleId);
                    if (family != null && family.getAdultRoleId() != null && !family.getAdultRoleId().isBlank()) {
                        adultRoleId = family.matchesAdultRole(roleId) ? roleId : family.getAdultRoleId();
                    }
                    String inferredStage = inferStageFromFamilyRole(roleId, family);
                    if (STAGE_BABY.equals(inferredStage) || STAGE_ADOLESCENT.equals(inferredStage)) {
                        stage = inferredStage;
                    }
                }
            }
        }

        double adultScale = resolveAdultScale(npcRef, store, adultRoleId);
        double defaultAdolescentScale = resolveAdolescentScale(adultScale);
        double babyScale = resolveBabyScale(adultScale);
        double adolescentScale = defaultAdolescentScale;
        double adolescentSwitchScale = defaultAdolescentScale;
        double adultStartScale = defaultAdolescentScale;
        double adultSwitchScale = adultScale;
        double adultFinalScale = adultScale;
        long bornAtMs = 0L;
        long adolescentAtMs = 0L;
        long adultAtMs = 0L;
        long fullyGrownAtMs = 0L;
        boolean growthScalingEnabled = false;

        if ((STAGE_BABY.equals(stage) || STAGE_ADOLESCENT.equals(stage)) && roleId != null && !roleId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveForRole(roleId);
            if (config != null && config.isEnabled()) {
                long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
                LifecycleComputation lifecycle = computeLifecycle(
                        nowMs,
                        adultScale,
                        config,
                        family,
                        roleId,
                        store
                );
                babyScale = lifecycle.babyStartScale();
                adolescentScale = lifecycle.adolescentStartScale();
                adolescentSwitchScale = lifecycle.adolescentSwitchScale();
                adultStartScale = lifecycle.adultStartScale();
                adultSwitchScale = lifecycle.adultSwitchScale();
                adultFinalScale = lifecycle.adultFinalScale();
                growthScalingEnabled = true;
                bornAtMs = nowMs;
                adolescentAtMs = lifecycle.adolescentAtMs();
                adultAtMs = lifecycle.adultAtMs();
                fullyGrownAtMs = lifecycle.fullyGrownAtMs();
                if (STAGE_ADOLESCENT.equals(stage)) {
                    long babyDurationMs = Math.max(1L, BreedingTimeService.saturatingSubtract(
                            lifecycle.adolescentAtMs(), nowMs
                    ));
                    long adolescentDurationMs = Math.max(1L, BreedingTimeService.saturatingSubtract(
                            lifecycle.adultAtMs(), lifecycle.adolescentAtMs()
                    ));
                    long adultDurationMs = Math.max(1L, BreedingTimeService.saturatingSubtract(
                            lifecycle.fullyGrownAtMs(), lifecycle.adultAtMs()
                    ));
                    bornAtMs = BreedingTimeService.saturatingSubtract(nowMs, babyDurationMs);
                    adolescentAtMs = nowMs;
                    adultAtMs = BreedingTimeService.saturatingAdd(nowMs, adolescentDurationMs);
                    fullyGrownAtMs = BreedingTimeService.saturatingAdd(
                            adultAtMs, adultDurationMs
                    );
                }
            }
        }

        TameworkLifeStageComponent created = new TameworkLifeStageComponent(
                stage,
                bornAtMs,
                adolescentAtMs,
                adultAtMs,
                fullyGrownAtMs,
                babyScale,
                adolescentScale,
                adolescentSwitchScale,
                adultStartScale,
                adultSwitchScale,
                adultFinalScale,
                growthScalingEnabled
        );
        if (family != null) {
            if (adultRoleId != null && !adultRoleId.isBlank()) {
                created.setAdultRoleId(adultRoleId);
            }
            if (family.getBabyRoleId() != null && !family.getBabyRoleId().isBlank()) {
                created.setBabyRoleId(family.getBabyRoleId());
            }
            if (family.getAdolescentRoleId() != null && !family.getAdolescentRoleId().isBlank()) {
                created.setAdolescentRoleId(family.getAdolescentRoleId());
            }
        }
        return created;
    }

    public static void refreshLifeStage(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable NPCEntity npc,
                                        @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
        if (stage == null) {
            return;
        }

        String previousStage = normalizeStage(stage.getStage());
        boolean changed = normalizeComponentDefaults(stage, npcRef, store);
        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        String resolvedStage = resolveStageId(stage, nowMs);
        if (!resolvedStage.equals(stage.getStage())) {
            stage.setStage(resolvedStage);
            changed = true;
        }
        if (enteredAdultStage(previousStage, resolvedStage) && shouldRemoveFromFlockOnAdult(npc)) {
            changed |= leaveFlockMembership(npcRef, store);
        }

        if (stage.isGrowthScalingEnabled()) {
            double targetScale = resolveScale(stage, nowMs);
            CompanionModelScaleService.applyScale(npcRef, npc, store, targetScale);
            if (hasTimelineTimestamp(stage.getFullyGrownAtMs()) && nowMs >= stage.getFullyGrownAtMs()) {
                stage.setGrowthScalingEnabled(false);
                changed = true;
            }
        }

        if (applyLifecycleRoleForStage(npcRef, npc, store, resolvedStage, stage)) {
            changed = true;
        }

        if (changed) {
            store.putComponent(npcRef, type, stage);
        }
    }

    /**
     * Ensures periodic growth processing is running for entities with active growth scaling.
     *
     * <p>This is used after load/bootstrap to resume lifecycle progression that otherwise only started at spawn.
     */
    public static void ensureGrowthTickScheduled(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable NPCEntity npc,
                                                 @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
        if (stage == null || !stage.isGrowthScalingEnabled()) {
            return;
        }
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            NPCEntity resolvedNpc = store.getComponent(npcRef, NPCEntity.getComponentType());
            npcUuid = resolvedNpc != null ? resolvedNpc.getUuid() : null;
        }
        if (npcUuid == null || !ACTIVE_GROWTH_TICKERS.add(npcUuid)) {
            return;
        }
        scheduleGrowthTick(npcRef, npc, store);
    }

    public static boolean isAdult(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String roleIdFallback) {
        // Keep this path read-only; it is called from chunk iteration contexts (e.g. partner scans).
        if (npcRef != null && npcRef.isValid() && store != null) {
            ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
            if (type != null) {
                TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
                if (stage != null) {
                    return STAGE_ADULT.equals(resolveStageId(stage, BreedingTimeService.resolveCurrentTimeMs(store)));
                }
            }
        }
        return isAdultRoleFallback(roleIdFallback);
    }

    public static String resolveCurrentStage(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             @Nullable String roleIdFallback) {
        // Keep this path read-only; callers may run inside store iteration callbacks.
        if (npcRef != null && npcRef.isValid() && store != null) {
            ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
            if (type != null) {
                TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
                if (stage != null) {
                    return resolveStageId(stage, BreedingTimeService.resolveCurrentTimeMs(store));
                }
            }
        }
        return isAdultRoleFallback(roleIdFallback) ? STAGE_ADULT : STAGE_BABY;
    }

    /**
     * Applies a relative size-multiplier change to active model scale and tracked life-stage scale targets.
     *
     * <p>This is intended for explicit trait mutation flows (for example, debug commands) where the previous and
     * next SizeMultiplier values are both known.
     */
    public static void applySizeMultiplierDelta(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                double previousMultiplier,
                                                double nextMultiplier) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        double safePrevious = sanitizeMultiplier(previousMultiplier);
        double safeNext = sanitizeMultiplier(nextMultiplier);
        if (Math.abs(safePrevious - safeNext) <= 0.000001) {
            return;
        }
        double ratio = safeNext / safePrevious;
        if (!Double.isFinite(ratio) || ratio <= 0.0) {
            return;
        }

        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageType = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent stage = lifeStageType != null ? store.getComponent(npcRef, lifeStageType) : null;
        if (stage != null) {
            stage.setAdultScale(clampScale(stage.getAdultScale() * ratio));
            stage.setBabyScale(clampScale(stage.getBabyScale() * ratio));
            stage.setAdolescentScale(clampScale(stage.getAdolescentScale() * ratio));
            stage.setAdolescentSwitchScale(clampScale(stage.getAdolescentSwitchScale() * ratio));
            stage.setAdultStartScale(clampScale(stage.getAdultStartScale() * ratio));
            stage.setAdultSwitchScale(clampScale(stage.getAdultSwitchScale() * ratio));
            long now = BreedingTimeService.resolveCurrentTimeMs(store);
            String resolvedStage = resolveStageId(stage, now);
            if (!resolvedStage.equals(stage.getStage())) {
                stage.setStage(resolvedStage);
            }
            double targetScale = resolveScale(stage, now);
            CompanionModelScaleService.applyScale(npcRef, npc, store, targetScale);
            store.putComponent(npcRef, lifeStageType, stage);
            return;
        }

        double current = CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0);
        CompanionModelScaleService.applyScale(npcRef, npc, store, current * ratio);
    }

    static String resolveStageId(@Nullable TameworkLifeStageComponent component, long nowMs) {
        if (component == null) {
            return STAGE_ADULT;
        }
        if (!component.isGrowthScalingEnabled()) {
            String normalized = normalizeStage(component.getStage());
            return normalized != null ? normalized : STAGE_ADULT;
        }
        long adultAtMs = component.getAdultAtMs();
        long adolescentAtMs = component.getAdolescentAtMs();
        long bornAtMs = component.getBornAtMs();

        if (hasTimelineTimestamp(adultAtMs) && nowMs >= adultAtMs) {
            return STAGE_ADULT;
        }
        if (hasTimelineTimestamp(adolescentAtMs)
                && adultAtMs > adolescentAtMs
                && nowMs >= adolescentAtMs) {
            return STAGE_ADOLESCENT;
        }
        if (hasTimelineTimestamp(bornAtMs)) {
            return STAGE_BABY;
        }
        String normalized = normalizeStage(component.getStage());
        return normalized != null ? normalized : STAGE_ADULT;
    }

    static double resolveScale(@Nullable TameworkLifeStageComponent component, long nowMs) {
        if (component == null) {
            return 1.0;
        }
        double babyStart = clampScale(component.getBabyScale());
        double adolescentStart = clampScale(component.getAdolescentScale());
        double adolescentSwitch = clampScale(component.getAdolescentSwitchScale());
        double adultStart = clampScale(component.getAdultStartScale());
        double adultSwitch = clampScale(component.getAdultSwitchScale());
        double adultFinal = clampScale(component.getAdultScale());

        if (!component.isGrowthScalingEnabled()) {
            return adultFinal;
        }

        long bornAtMs = component.getBornAtMs();
        long adolescentAtMs = component.getAdolescentAtMs();
        long adultAtMs = component.getAdultAtMs();
        long fullyGrownAtMs = component.getFullyGrownAtMs();
        boolean hasAdolescentStage = adultAtMs > adolescentAtMs;
        double babySwitch = hasAdolescentStage ? adolescentSwitch : adultSwitch;
        if (fullyGrownAtMs <= bornAtMs) {
            return adultFinal;
        }
        if (nowMs <= bornAtMs) {
            return babyStart;
        }
        if (adolescentAtMs > bornAtMs && nowMs < adolescentAtMs) {
            double progress = (double) (nowMs - bornAtMs) / (double) (adolescentAtMs - bornAtMs);
            return lerp(babyStart, babySwitch, progress);
        }
        if (hasAdolescentStage && nowMs < adultAtMs) {
            double progress = (double) (nowMs - adolescentAtMs) / (double) (adultAtMs - adolescentAtMs);
            return lerp(adolescentStart, adultSwitch, progress);
        }
        long adultStartAtMs = hasTimelineTimestamp(adultAtMs) ? adultAtMs : Math.max(adolescentAtMs, bornAtMs);
        if (fullyGrownAtMs > adultStartAtMs && nowMs < fullyGrownAtMs) {
            double progress = (double) (nowMs - adultStartAtMs) / (double) (fullyGrownAtMs - adultStartAtMs);
            return lerp(adultStart, adultFinal, progress);
        }
        return adultFinal;
    }

    private static boolean normalizeComponentDefaults(TameworkLifeStageComponent component,
                                                      Ref<EntityStore> npcRef,
                                                      Store<EntityStore> store) {
        boolean changed = false;
        String stage = normalizeStage(component.getStage());
        if (stage == null) {
            component.setStage(STAGE_ADULT);
            changed = true;
        } else if (!stage.equals(component.getStage())) {
            component.setStage(stage);
            changed = true;
        }

        if (!Double.isFinite(component.getAdultScale()) || component.getAdultScale() <= 0.0) {
            component.setAdultScale(resolveAdultScale(npcRef, store, component.getAdultRoleId()));
            changed = true;
        }
        if (!Double.isFinite(component.getBabyScale()) || component.getBabyScale() <= 0.0) {
            component.setBabyScale(resolveBabyScale(component.getAdultScale()));
            changed = true;
        }
        if (!Double.isFinite(component.getAdolescentScale()) || component.getAdolescentScale() <= 0.0) {
            component.setAdolescentScale(resolveAdolescentScale(component.getAdultScale()));
            changed = true;
        }
        if (!Double.isFinite(component.getAdolescentSwitchScale()) || component.getAdolescentSwitchScale() <= 0.0) {
            component.setAdolescentSwitchScale(component.getAdolescentScale());
            changed = true;
        }
        if (!Double.isFinite(component.getAdultStartScale()) || component.getAdultStartScale() <= 0.0) {
            component.setAdultStartScale(component.getAdolescentScale());
            changed = true;
        }
        if (!Double.isFinite(component.getAdultSwitchScale()) || component.getAdultSwitchScale() <= 0.0) {
            component.setAdultSwitchScale(component.getAdultScale());
            changed = true;
        }

        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId != null && !roleId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveForRole(roleId);
            TwBreedingConfig.RoleFamily family = resolveLifecycleFamilyForProgression(config, roleId);
            if (family != null) {
                if ((component.getAdultRoleId() == null || component.getAdultRoleId().isBlank())
                        && family.getAdultRoleId() != null
                        && !family.getAdultRoleId().isBlank()) {
                    component.setAdultRoleId(family.matchesAdultRole(roleId) ? roleId : family.getAdultRoleId());
                    changed = true;
                }
                if ((component.getBabyRoleId() == null || component.getBabyRoleId().isBlank())
                        && family.getBabyRoleId() != null
                        && !family.getBabyRoleId().isBlank()) {
                    component.setBabyRoleId(family.getBabyRoleId());
                    changed = true;
                }
                if ((component.getAdolescentRoleId() == null || component.getAdolescentRoleId().isBlank())
                        && family.getAdolescentRoleId() != null
                        && !family.getAdolescentRoleId().isBlank()) {
                    component.setAdolescentRoleId(family.getAdolescentRoleId());
                    changed = true;
                }
            }
        }

        changed |= migrateLegacyTimelineBasis(component, npcRef, store);

        if (component.getFullyGrownAtMs() < component.getAdultAtMs()) {
            component.setFullyGrownAtMs(component.getAdultAtMs());
            changed = true;
        }
        if (component.isGrowthScalingEnabled()
                && hasTimelineTimestamp(component.getBornAtMs())
                && hasTimelineTimestamp(component.getFullyGrownAtMs())
                && component.getFullyGrownAtMs() <= component.getBornAtMs()) {
            component.setGrowthScalingEnabled(false);
            component.setStage(STAGE_ADULT);
            changed = true;
        }
        return changed;
    }

    private static boolean migrateLegacyTimelineBasis(TameworkLifeStageComponent component,
                                                      Ref<EntityStore> npcRef,
                                                      Store<EntityStore> store) {
        if (component == null || npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        long nowGameMs = BreedingTimeService.resolveCurrentTimeMs(store);
        if (!hasLegacyTimelineTimestamp(component, nowGameMs)) {
            return false;
        }

        if (!component.isGrowthScalingEnabled()) {
            boolean cleared = false;
            String normalizedStage = normalizeStage(component.getStage());
            if (!STAGE_ADULT.equals(normalizedStage)) {
                component.setStage(STAGE_ADULT);
                cleared = true;
            }
            if (component.getBornAtMs() != 0L) {
                component.setBornAtMs(0L);
                cleared = true;
            }
            if (component.getAdolescentAtMs() != 0L) {
                component.setAdolescentAtMs(0L);
                cleared = true;
            }
            if (component.getAdultAtMs() != 0L) {
                component.setAdultAtMs(0L);
                cleared = true;
            }
            if (component.getFullyGrownAtMs() != 0L) {
                component.setFullyGrownAtMs(0L);
                cleared = true;
            }
            return cleared;
        }

        long nowRealMs = System.currentTimeMillis();
        TwBreedingConfig.TimerBasis timerBasis = resolveTimerBasis(npcRef, store);

        long born = migrateLegacyAbsoluteTimestamp(component.getBornAtMs(), nowRealMs, nowGameMs, timerBasis, store);
        long adolescent = migrateLegacyAbsoluteTimestamp(
                component.getAdolescentAtMs(),
                nowRealMs,
                nowGameMs,
                timerBasis,
                store
        );
        long adult = migrateLegacyAbsoluteTimestamp(component.getAdultAtMs(), nowRealMs, nowGameMs, timerBasis, store);
        long fullyGrown = migrateLegacyAbsoluteTimestamp(
                component.getFullyGrownAtMs(),
                nowRealMs,
                nowGameMs,
                timerBasis,
                store
        );

        if (born > 0L) {
            if (adolescent > 0L && adolescent <= born) {
                adolescent = born + 1L;
            }
            if (adult > 0L && adult <= adolescent) {
                adult = adolescent + 1L;
            }
            if (fullyGrown > 0L && fullyGrown <= adult) {
                fullyGrown = adult + 1L;
            }
        }

        boolean changed = false;
        if (born != component.getBornAtMs()) {
            component.setBornAtMs(born);
            changed = true;
        }
        if (adolescent != component.getAdolescentAtMs()) {
            component.setAdolescentAtMs(adolescent);
            changed = true;
        }
        if (adult != component.getAdultAtMs()) {
            component.setAdultAtMs(adult);
            changed = true;
        }
        if (fullyGrown != component.getFullyGrownAtMs()) {
            component.setFullyGrownAtMs(fullyGrown);
            changed = true;
        }
        return changed;
    }

    private static boolean hasLegacyTimelineTimestamp(TameworkLifeStageComponent component, long nowGameMs) {
        return LegacyWorldTimestampClassifier.shouldTranslate(component.getBornAtMs(), nowGameMs)
                || LegacyWorldTimestampClassifier.shouldTranslate(component.getAdolescentAtMs(), nowGameMs)
                || LegacyWorldTimestampClassifier.shouldTranslate(component.getAdultAtMs(), nowGameMs)
                || LegacyWorldTimestampClassifier.shouldTranslate(component.getFullyGrownAtMs(), nowGameMs);
    }

    private static long migrateLegacyAbsoluteTimestamp(long legacyTimestampMs,
                                                       long nowRealMs,
                                                       long nowGameMs,
                                                       TwBreedingConfig.TimerBasis timerBasis,
                                                       Store<EntityStore> store) {
        if (!LegacyWorldTimestampClassifier.shouldTranslate(legacyTimestampMs, nowGameMs)) {
            return legacyTimestampMs;
        }
        long deltaRealMs = legacyTimestampMs - nowRealMs;
        long deltaGameMs = BreedingTimeService.toGameDurationMs(
                Math.abs(deltaRealMs) / 1000.0,
                timerBasis,
                store
        );
        if (deltaGameMs <= 0L) {
            return nowGameMs;
        }
        return deltaRealMs < 0L
                ? nowGameMs - deltaGameMs
                : nowGameMs + deltaGameMs;
    }

    private static TwBreedingConfig.TimerBasis resolveTimerBasis(Ref<EntityStore> npcRef,
                                                                 Store<EntityStore> store) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwBreedingConfig config = roleId != null ? TwBreedingConfig.resolveForRole(roleId) : null;
        return config != null
                ? config.resolveTiming(roleId).getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
    }

    private static boolean applyLifecycleRoleForStage(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable NPCEntity providedNpc,
                                                      @Nullable Store<EntityStore> store,
                                                      @Nullable String stage,
                                                      @Nullable TameworkLifeStageComponent lifeStage) {
        if (npcRef == null || !npcRef.isValid() || store == null || stage == null || stage.isBlank()) {
            return false;
        }
        NPCEntity npc = providedNpc != null ? providedNpc : store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        if (isMounted(npcRef, store)) {
            return false;
        }
        String currentRoleId = resolveRoleId(npc);
        if (currentRoleId == null || currentRoleId.isBlank()) {
            return false;
        }
        if ("Empty_Role".equalsIgnoreCase(currentRoleId)) {
            return false;
        }
        String targetRoleId = resolveTargetRoleIdForStage(stage, lifeStage, null);
        if (targetRoleId == null || targetRoleId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveForRole(currentRoleId);
            if (config == null || !config.isEnabled()) {
                return false;
            }
            TwBreedingConfig.OffspringLifecycleSettings lifecycle = config.resolveOffspringLifecycle(currentRoleId);
            if (lifecycle == null || !lifecycle.isEnabled()) {
                return false;
            }
            TwBreedingConfig.RoleFamily family = resolveLifecycleFamilyForProgression(config, currentRoleId);
            targetRoleId = resolveTargetRoleIdForStage(stage, lifeStage, family);
            if (targetRoleId == null || targetRoleId.isBlank()) {
                family = resolveLifecycleFamilyForProgression(config, resolveRoleIdFromIndex(npc));
                targetRoleId = resolveTargetRoleIdForStage(stage, lifeStage, family);
            }
        }
        if (targetRoleId == null || targetRoleId.isBlank()) {
            return false;
        }
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            return false;
        }
        int targetRoleIndex = plugin.getIndex(targetRoleId);
        if (targetRoleIndex < 0) {
            return false;
        }
        if (npc.getRoleIndex() == targetRoleIndex) {
            return false;
        }
        Role role = npc.getRole();
        if (role == null) {
            return false;
        }
        RoleChangeSystem.requestRoleChange(npcRef, role, targetRoleIndex, true, store);
        scheduleInitialScaleRetry(npcRef, npc, store, ROLE_CHANGE_SCALE_MAX_RETRIES, targetRoleId);
        return true;
    }

    @Nullable
    private static String resolveTargetRoleIdForStage(@Nullable String stage,
                                                      @Nullable TameworkLifeStageComponent lifeStage,
                                                      @Nullable TwBreedingConfig.RoleFamily family) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        if (STAGE_BABY.equals(stage)) {
            if (lifeStage != null && lifeStage.getBabyRoleId() != null && !lifeStage.getBabyRoleId().isBlank()) {
                return lifeStage.getBabyRoleId();
            }
            if (family == null) {
                return null;
            }
            if (family.getBabyRoleId() != null && !family.getBabyRoleId().isBlank()) {
                return family.getBabyRoleId();
            }
            return family.getAdultRoleId();
        }
        if (STAGE_ADOLESCENT.equals(stage)) {
            if (lifeStage != null
                    && lifeStage.getAdolescentRoleId() != null
                    && !lifeStage.getAdolescentRoleId().isBlank()) {
                return lifeStage.getAdolescentRoleId();
            }
            if (lifeStage != null && lifeStage.getAdultRoleId() != null && !lifeStage.getAdultRoleId().isBlank()) {
                return lifeStage.getAdultRoleId();
            }
            if (family == null) {
                return null;
            }
            if (family.getAdolescentRoleId() != null && !family.getAdolescentRoleId().isBlank()) {
                return family.getAdolescentRoleId();
            }
            return family.getAdultRoleId();
        }
        if (lifeStage != null && lifeStage.getAdultRoleId() != null && !lifeStage.getAdultRoleId().isBlank()) {
            return lifeStage.getAdultRoleId();
        }
        if (family == null) {
            return null;
        }
        return family.getAdultRoleId();
    }

    private static LifecycleComputation computeLifecycle(long nowMs,
                                                         double adultScale,
                                                         @Nullable TwBreedingConfig breedingConfig,
                                                         @Nullable TwBreedingConfig.RoleFamily preResolvedFamily,
                                                         @Nullable String spawnedRoleId,
                                                         @Nullable Store<EntityStore> store) {
        return computeLifecycle(
                nowMs,
                adultScale,
                breedingConfig,
                preResolvedFamily,
                spawnedRoleId,
                store,
                LifecycleFamilyResolution.CURRENT_CONFIG_FALLBACK
        );
    }

    private static LifecycleComputation computeLifecycle(long nowMs,
                                                         double adultScale,
                                                         @Nullable TwBreedingConfig breedingConfig,
                                                         @Nullable TwBreedingConfig.RoleFamily preResolvedFamily,
                                                         @Nullable String spawnedRoleId,
                                                         @Nullable Store<EntityStore> store,
                                                         @Nonnull LifecycleFamilyResolution familyResolution) {
        TwBreedingConfig.RoleFamily family = resolveLifecycleFamilyForInitialization(
                breedingConfig, preResolvedFamily, spawnedRoleId, familyResolution
        );
        CompanionOffspringLifecycleComputation.Result result =
                CompanionOffspringLifecycleComputation.compute(
                        nowMs, adultScale, breedingConfig, family, spawnedRoleId, store
                );
        return new LifecycleComputation(
                result.babyStartScale(), result.adolescentStartScale(),
                result.adolescentSwitchScale(), result.adultStartScale(),
                result.adultSwitchScale(), result.adultFinalScale(),
                result.adolescentAtMs(), result.adultAtMs(), result.fullyGrownAtMs()
        );
    }

    @Nullable
    static TwBreedingConfig.RoleFamily resolveLifecycleFamilyForInitialization(
            @Nullable TwBreedingConfig breedingConfig,
            @Nullable TwBreedingConfig.RoleFamily preResolvedFamily,
            @Nullable String spawnedRoleId,
            @Nonnull LifecycleFamilyResolution familyResolution) {
        if (preResolvedFamily != null
                || familyResolution == LifecycleFamilyResolution.PLANNED_SELECTION_ONLY) {
            return preResolvedFamily;
        }
        return resolveLifecycleFamilyForProgression(breedingConfig, spawnedRoleId);
    }

    private static void scheduleGrowthTick(Ref<EntityStore> npcRef,
                                           @Nullable NPCEntity npc,
                                           Store<EntityStore> store) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (world == null || npcUuid == null) {
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> onGrowthTick(world, npcUuid)),
                CompletableFuture.delayedExecutor(GROWTH_TICK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private static void scheduleInitialScaleRetry(Ref<EntityStore> npcRef,
                                                  @Nullable NPCEntity npc,
                                                  Store<EntityStore> store,
                                                  int remainingRetries,
                                                  @Nullable String expectedRoleId) {
        int settleRetries = expectedRoleId != null && !expectedRoleId.isBlank()
                ? ROLE_CHANGE_SCALE_SETTLE_RETRIES
                : 0;
        scheduleInitialScaleRetry(npcRef, npc, store, remainingRetries, expectedRoleId, settleRetries);
    }

    private static void scheduleInitialScaleRetry(Ref<EntityStore> npcRef,
                                                  @Nullable NPCEntity npc,
                                                  Store<EntityStore> store,
                                                  int remainingRetries,
                                                  @Nullable String expectedRoleId,
                                                  int settleRetriesRemaining) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (world == null || npcUuid == null || remainingRetries <= 0) {
            return;
        }
        int nextSettleRetries = Math.max(0, settleRetriesRemaining);
        CompletableFuture.runAsync(
                () -> world.execute(() -> onInitialScaleRetry(
                        world,
                        npcUuid,
                        remainingRetries,
                        expectedRoleId,
                        nextSettleRetries
                )),
                CompletableFuture.delayedExecutor(INITIAL_SCALE_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private static void onInitialScaleRetry(@Nullable World world,
                                            @Nullable UUID npcUuid,
                                            int remainingRetries,
                                            @Nullable String expectedRoleId,
                                            int settleRetriesRemaining) {
        if (world == null || npcUuid == null || remainingRetries <= 0) {
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
        if (stage == null) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        String currentRoleId = resolveRoleId(npc);
        boolean targetRoleReady = expectedRoleId == null
                || expectedRoleId.isBlank()
                || (currentRoleId != null && expectedRoleId.equalsIgnoreCase(currentRoleId));
        double targetScale = resolveScale(stage, BreedingTimeService.resolveCurrentTimeMs(store));
        boolean applied = CompanionModelScaleService.applyScale(npcRef, npc, store, targetScale);
        boolean closeEnough = isScaleClose(npcRef, store, targetScale);
        boolean roleSpecificRetry = expectedRoleId != null && !expectedRoleId.isBlank();
        if (!roleSpecificRetry) {
            if (remainingRetries <= 1 || applied || closeEnough) {
                return;
            }
            scheduleInitialScaleRetry(npcRef, npc, store, remainingRetries - 1, expectedRoleId, 0);
            return;
        }
        int nextSettleRetries = settleRetriesRemaining;
        if (!targetRoleReady) {
            nextSettleRetries = ROLE_CHANGE_SCALE_SETTLE_RETRIES;
        } else if (applied || closeEnough) {
            nextSettleRetries = Math.max(0, settleRetriesRemaining - 1);
        } else {
            nextSettleRetries = ROLE_CHANGE_SCALE_SETTLE_RETRIES;
        }
        if (remainingRetries <= 1) {
            return;
        }
        if (targetRoleReady && (applied || closeEnough) && nextSettleRetries <= 0) {
            return;
        }
        scheduleInitialScaleRetry(
                npcRef,
                npc,
                store,
                remainingRetries - 1,
                expectedRoleId,
                nextSettleRetries
        );
    }

    private static void onGrowthTick(World world, UUID npcUuid) {
        if (world == null || npcUuid == null) {
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            ACTIVE_GROWTH_TICKERS.remove(npcUuid);
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            ACTIVE_GROWTH_TICKERS.remove(npcUuid);
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        refreshLifeStage(npcRef, npc, store);
        if (isGrowthInProgress(npcRef, store)) {
            scheduleGrowthTick(npcRef, npc, store);
            return;
        }
        ACTIVE_GROWTH_TICKERS.remove(npcUuid);
    }

    private static boolean isGrowthInProgress(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
        return stage != null && stage.isGrowthScalingEnabled();
    }

    private static boolean isUninitializedAdultComponent(@Nullable TameworkLifeStageComponent component) {
        if (component == null) {
            return false;
        }
        if (!STAGE_ADULT.equals(normalizeStage(component.getStage()))) {
            return false;
        }
        if (component.isGrowthScalingEnabled()) {
            return false;
        }
        return component.getBornAtMs() == 0L
                && component.getAdolescentAtMs() == 0L
                && component.getAdultAtMs() == 0L
                && component.getFullyGrownAtMs() == 0L;
    }

    @Nullable
    private static String inferStageFromFamilyRole(@Nullable String roleId,
                                                   @Nullable TwBreedingConfig.RoleFamily family) {
        if (roleId == null || roleId.isBlank() || family == null) {
            return null;
        }
        if (roleIdsMatch(roleId, family.getBabyRoleId())) {
            return STAGE_BABY;
        }
        if (roleIdsMatch(roleId, family.getAdolescentRoleId())) {
            return STAGE_ADOLESCENT;
        }
        if (family.matchesAdultRole(roleId)) {
            return STAGE_ADULT;
        }
        return null;
    }

    private static boolean roleIdsMatch(@Nullable String left, @Nullable String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        return normalizeRoleId(left).equals(normalizeRoleId(right));
    }

    private static String normalizeRoleId(String roleId) {
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf(':');
        if (separator >= 0 && separator < normalized.length() - 1) {
            return normalized.substring(separator + 1);
        }
        return normalized;
    }

    private static boolean isAdultRoleFallback(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return true;
        }
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        return !normalized.contains("baby") && !normalized.contains("adolescent");
    }

    @Nullable
    private static String normalizeStage(@Nullable String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if ("baby".equals(normalized)) {
            return STAGE_BABY;
        }
        if ("adolescent".equals(normalized) || "juvenile".equals(normalized)) {
            return STAGE_ADOLESCENT;
        }
        if ("adult".equals(normalized)) {
            return STAGE_ADULT;
        }
        return null;
    }

    @Nullable
    private static String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String fromIndex = resolveRoleIdFromIndex(npc);
        if (fromIndex != null && !fromIndex.isBlank()) {
            return fromIndex;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        return null;
    }

    @Nullable
    private static String resolveRoleIdFromIndex(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex < 0) {
            return null;
        }
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            return null;
        }
        String resolved = plugin.getName(roleIndex);
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    private static boolean isMounted(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        try {
            ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
            return mountType != null && store.getComponent(npcRef, mountType) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double resolveAdultScale(Ref<EntityStore> npcRef,
                                            Store<EntityStore> store,
                                            @Nullable String adultRoleId) {
        double fallback = clampScale(CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0));
        return clampScale(CompanionAdultScaleResolver.resolveAdultScale(npcRef, store, adultRoleId, fallback));
    }

    private static double resolveBabyScale(double adultScale) {
        return clampScale(adultScale * DEFAULT_BABY_SCALE_FACTOR);
    }

    private static double resolveAdolescentScale(double adultScale) {
        return clampScale(adultScale * DEFAULT_ADOLESCENT_SCALE_FACTOR);
    }

    private static double clampScale(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, value);
    }

    private static double sanitizeMultiplier(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return value;
    }

    private static boolean isScaleClose(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Store<EntityStore> store,
                                        double targetScale) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        double currentScale = CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0);
        return Math.abs(currentScale - targetScale) <= 0.0001;
    }

    private static boolean hasTimelineTimestamp(long value) {
        return value != 0L;
    }

    private static boolean enteredAdultStage(@Nullable String previousStage, @Nullable String resolvedStage) {
        return STAGE_ADULT.equals(resolvedStage)
                && previousStage != null
                && !STAGE_ADULT.equals(previousStage);
    }

    /**
     * Returns whether flock membership should be cleared when this NPC enters adult stage.
     *
     * <p>This is controlled by the role/template sensor-scope parameter {@code FlockRemoveOnAdult}.
     * Missing/invalid values default to {@code false}.
     */
    private static boolean shouldRemoveFromFlockOnAdult(@Nullable NPCEntity npc) {
        if (npc == null) {
            return false;
        }
        Role role = npc.getRole();
        if (role == null || role.getEntitySupport() == null) {
            return false;
        }
        StdScope scope = role.getEntitySupport().getSensorScope();
        if (scope == null) {
            return false;
        }
        try {
            BooleanSupplier supplier = scope.getBooleanSupplier("FlockRemoveOnAdult");
            return supplier != null && supplier.getAsBoolean();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static boolean leaveFlockMembership(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        ComponentType<EntityStore, FlockMembership> flockType = FlockMembership.getComponentType();
        if (flockType == null || store.getComponent(npcRef, flockType) == null) {
            return false;
        }
        store.tryRemoveComponent(npcRef, flockType);
        return true;
    }

    private static double lerp(double start, double end, double progress) {
        double clamped = progress;
        if (clamped < 0.0) {
            clamped = 0.0;
        } else if (clamped > 1.0) {
            clamped = 1.0;
        }
        return start + (end - start) * clamped;
    }

    private record LifecycleComputation(double babyStartScale,
                                        double adolescentStartScale,
                                        double adolescentSwitchScale,
                                        double adultStartScale,
                                        double adultSwitchScale,
                                        double adultFinalScale,
                                        long adolescentAtMs,
                                        long adultAtMs,
                                        long fullyGrownAtMs) {
    }
}
