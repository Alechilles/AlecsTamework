package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Manages companion life-stage state and growth scaling progression.
 */
public final class CompanionLifeStageService {
    public static final String STAGE_BABY = "Baby";
    public static final String STAGE_ADOLESCENT = "Adolescent";
    public static final String STAGE_ADULT = "Adult";

    private static final long DEFAULT_GROWTH_DURATION_SECONDS = TimeUnit.MINUTES.toSeconds(7);
    private static final long GROWTH_TICK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(3);
    private static final long GROWTH_START_RETRY_INTERVAL_MS = 50L;
    private static final int GROWTH_START_MAX_RETRIES = 40;
    private static final long INITIAL_SCALE_RETRY_INTERVAL_MS = 10L;
    private static final int INITIAL_SCALE_MAX_RETRIES = 120;
    private static final int ROLE_CHANGE_SCALE_MAX_RETRIES = 120;
    private static final int ROLE_CHANGE_SCALE_SETTLE_RETRIES = 20;
    private static final double DEFAULT_BABY_SCALE_FACTOR = 0.33;
    private static final double DEFAULT_ADOLESCENT_SCALE_FACTOR = 0.66;
    private static final double MIN_SCALE = 0.10;
    private static final Set<UUID> ACTIVE_GROWTH_TICKERS = ConcurrentHashMap.newKeySet();

    private CompanionLifeStageService() {
    }

    public static void ensureLifeStageComponent(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkLifeStageComponent existing = store.getComponent(npcRef, type);
        if (existing == null) {
            double adultScale = resolveAdultScale(npcRef, store);
            double adolescentScale = resolveAdolescentScale(adultScale);
            TameworkLifeStageComponent created = new TameworkLifeStageComponent(
                    STAGE_ADULT,
                    0L,
                    0L,
                    0L,
                    0L,
                    resolveBabyScale(adultScale),
                    adolescentScale,
                    adolescentScale,
                    adolescentScale,
                    adultScale,
                    adultScale,
                    false
            );
            store.putComponent(npcRef, type, created);
            return;
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
                                                    @Nullable TwBreedingConfig.RoleFamily preResolvedFamily) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }

        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        double adultScale = resolveAdultScale(childRef, store);
        LifecycleComputation lifecycle = computeLifecycle(
                nowMs,
                adultScale,
                breedingConfig,
                preResolvedFamily,
                spawnedRoleId,
                store
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
        store.putComponent(childRef, type, component);
        double initialScale = lifecycle.babyStartScale();
        CompanionModelScaleService.applyScale(childRef, childNpc, store, initialScale);
        scheduleInitialScaleRetry(childRef, childNpc, store, INITIAL_SCALE_MAX_RETRIES, null);
        ensureGrowthTickScheduled(childRef, childNpc, store);
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

        boolean changed = normalizeComponentDefaults(stage, npcRef, store);
        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        String resolvedStage = resolveStageId(stage, nowMs);
        if (!resolvedStage.equals(stage.getStage())) {
            stage.setStage(resolvedStage);
            changed = true;
        }

        if (stage.isGrowthScalingEnabled()) {
            double targetScale = resolveScale(stage, nowMs);
            CompanionModelScaleService.applyScale(npcRef, npc, store, targetScale);
            if (stage.getFullyGrownAtMs() > 0L && nowMs >= stage.getFullyGrownAtMs()) {
                stage.setGrowthScalingEnabled(false);
                changed = true;
            }
        }

        if (applyLifecycleRoleForStage(npcRef, npc, store, resolvedStage)) {
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
        if (npcUuid == null) {
            scheduleGrowthStartRetry(npcRef, store, GROWTH_START_MAX_RETRIES);
            return;
        }
        if (!ACTIVE_GROWTH_TICKERS.add(npcUuid)) {
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

        if (adultAtMs != 0L && nowMs >= adultAtMs) {
            return STAGE_ADULT;
        }
        if (adolescentAtMs != 0L
                && adultAtMs > adolescentAtMs
                && nowMs >= adolescentAtMs) {
            return STAGE_ADOLESCENT;
        }
        if (bornAtMs != 0L) {
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
        if (fullyGrownAtMs <= bornAtMs) {
            return adultFinal;
        }
        if (nowMs <= bornAtMs) {
            return babyStart;
        }
        if (adolescentAtMs > bornAtMs && nowMs < adolescentAtMs) {
            double progress = (double) (nowMs - bornAtMs) / (double) (adolescentAtMs - bornAtMs);
            return lerp(babyStart, adolescentSwitch, progress);
        }
        if (adultAtMs > adolescentAtMs && nowMs < adultAtMs) {
            double progress = (double) (nowMs - adolescentAtMs) / (double) (adultAtMs - adolescentAtMs);
            return lerp(adolescentStart, adultSwitch, progress);
        }
        long adultStartAtMs = adultAtMs > 0L ? adultAtMs : Math.max(adolescentAtMs, bornAtMs);
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
            component.setAdultScale(resolveAdultScale(npcRef, store));
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

        changed |= migrateLegacyTimelineBasis(component, npcRef, store);

        if (component.getFullyGrownAtMs() < component.getAdultAtMs()) {
            component.setFullyGrownAtMs(component.getAdultAtMs());
            changed = true;
        }
        if (component.isGrowthScalingEnabled()
                && component.getBornAtMs() != 0L
                && component.getFullyGrownAtMs() != 0L
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
        return isLegacyTimestamp(component.getBornAtMs(), nowGameMs)
                || isLegacyTimestamp(component.getAdolescentAtMs(), nowGameMs)
                || isLegacyTimestamp(component.getAdultAtMs(), nowGameMs)
                || isLegacyTimestamp(component.getFullyGrownAtMs(), nowGameMs);
    }

    private static boolean isLegacyTimestamp(long value, long nowGameMs) {
        if (value <= 0L) {
            return false;
        }
        if (nowGameMs < 0L) {
            return true;
        }
        return value > 946684800000L;
    }

    private static long migrateLegacyAbsoluteTimestamp(long legacyTimestampMs,
                                                       long nowRealMs,
                                                       long nowGameMs,
                                                       TwBreedingConfig.TimerBasis timerBasis,
                                                       Store<EntityStore> store) {
        if (legacyTimestampMs <= 0L || !isLegacyTimestamp(legacyTimestampMs, nowGameMs)) {
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
                ? config.getTiming().getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
    }

    private static boolean applyLifecycleRoleForStage(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable NPCEntity providedNpc,
                                                      @Nullable Store<EntityStore> store,
                                                      @Nullable String stage) {
        if (npcRef == null || !npcRef.isValid() || store == null || stage == null || stage.isBlank()) {
            return false;
        }
        NPCEntity npc = providedNpc != null ? providedNpc : store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        String currentRoleId = resolveRoleId(npc);
        if (currentRoleId == null || currentRoleId.isBlank()) {
            return false;
        }
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(currentRoleId);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = config.getOffspringLifecycle();
        if (lifecycle == null || !lifecycle.isEnabled()) {
            return false;
        }
        TwBreedingConfig.RoleFamily family = config.resolveLifecycleFamilyForRole(currentRoleId);
        if (family == null) {
            return false;
        }
        String targetRoleId = resolveTargetRoleIdForStage(stage, family);
        if (targetRoleId == null || targetRoleId.isBlank() || targetRoleId.equalsIgnoreCase(currentRoleId)) {
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
                                                      @Nullable TwBreedingConfig.RoleFamily family) {
        if (family == null || stage == null || stage.isBlank()) {
            return null;
        }
        if (STAGE_BABY.equals(stage)) {
            if (family.getBabyRoleId() != null && !family.getBabyRoleId().isBlank()) {
                return family.getBabyRoleId();
            }
            return family.getAdultRoleId();
        }
        if (STAGE_ADOLESCENT.equals(stage)) {
            if (family.getAdolescentRoleId() != null && !family.getAdolescentRoleId().isBlank()) {
                return family.getAdolescentRoleId();
            }
            return family.getAdultRoleId();
        }
        return family.getAdultRoleId();
    }

    private static LifecycleComputation computeLifecycle(long nowMs,
                                                         double adultScale,
                                                         @Nullable TwBreedingConfig breedingConfig,
                                                         @Nullable TwBreedingConfig.RoleFamily preResolvedFamily,
                                                         @Nullable String spawnedRoleId,
                                                         @Nullable Store<EntityStore> store) {
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = breedingConfig != null
                ? breedingConfig.getOffspringLifecycle()
                : null;
        TwBreedingConfig.RoleFamily family = preResolvedFamily;
        if (family == null && breedingConfig != null && spawnedRoleId != null && !spawnedRoleId.isBlank()) {
            family = breedingConfig.resolveLifecycleFamilyForRole(spawnedRoleId);
        }

        boolean lifecycleEnabled = lifecycle != null && lifecycle.isEnabled();
        boolean useFamilyScales = lifecycleEnabled && family != null;
        boolean hasAdolescentStage = useFamilyScales
                && family.getAdolescentRoleId() != null
                && !family.getAdolescentRoleId().isBlank();

        double babyStartScale;
        double adolescentStartScale;
        double adolescentSwitchScale;
        double adultStartScale;
        double adultSwitchScale;
        double adultFinalScale = clampScale(adultScale);

        if (useFamilyScales) {
            babyStartScale = clampScale(adultFinalScale * lifecycle.resolveBabyStartScale(family));
            adolescentStartScale = clampScale(adultFinalScale * lifecycle.resolveAdolescentStartScale(family));
            adolescentSwitchScale = clampScale(adultFinalScale * lifecycle.resolveAdolescentSwitchScale(family));
            adultStartScale = clampScale(adultFinalScale * lifecycle.resolveAdultStartScale(family));
            adultSwitchScale = clampScale(adultFinalScale * lifecycle.resolveAdultSwitchScale(family));
        } else {
            babyStartScale = resolveBabyScale(adultFinalScale);
            adolescentStartScale = resolveAdolescentScale(adultFinalScale);
            adolescentSwitchScale = adolescentStartScale;
            adultStartScale = adolescentStartScale;
            adultSwitchScale = adultFinalScale;
        }

        long totalGrowthMs = resolveGrowthDurationMs(breedingConfig, lifecycle, family, store);
        double babyDelta = Math.abs(adolescentSwitchScale - babyStartScale);
        double adolescentDelta = hasAdolescentStage ? Math.abs(adultSwitchScale - adolescentStartScale) : 0.0;
        double adultDelta = Math.abs(adultFinalScale - adultStartScale);
        double totalDelta = babyDelta + adolescentDelta + adultDelta;
        if (totalDelta <= 0.000001) {
            totalDelta = 1.0;
            babyDelta = 1.0;
            adolescentDelta = 0.0;
            adultDelta = 0.0;
        }

        long babyDurationMs = Math.max(1L, Math.round(totalGrowthMs * (babyDelta / totalDelta)));
        long adolescentDurationMs = hasAdolescentStage
                ? Math.max(1L, Math.round(totalGrowthMs * (adolescentDelta / totalDelta)))
                : 0L;
        long adultDurationMs = Math.max(1L, totalGrowthMs - babyDurationMs - adolescentDurationMs);
        long adolescentAtMs = nowMs + babyDurationMs;
        long adultAtMs = adolescentAtMs + adolescentDurationMs;
        long fullyGrownAtMs = adultAtMs + adultDurationMs;

        return new LifecycleComputation(
                babyStartScale,
                adolescentStartScale,
                adolescentSwitchScale,
                adultStartScale,
                adultSwitchScale,
                adultFinalScale,
                adolescentAtMs,
                adultAtMs,
                fullyGrownAtMs
        );
    }

    private static long resolveGrowthDurationMs(@Nullable TwBreedingConfig breedingConfig,
                                                @Nullable TwBreedingConfig.OffspringLifecycleSettings lifecycle,
                                                @Nullable TwBreedingConfig.RoleFamily family,
                                                @Nullable Store<EntityStore> store) {
        long configuredSeconds = DEFAULT_GROWTH_DURATION_SECONDS;
        if (lifecycle != null && lifecycle.isEnabled()) {
            int resolved = lifecycle.resolveTimeToFullGrownSeconds(family);
            if (resolved > 0) {
                configuredSeconds = resolved;
            }
        }
        TwBreedingConfig.TimerBasis timerBasis = breedingConfig != null
                ? breedingConfig.getTiming().getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        long converted = BreedingTimeService.toGameDurationMs(configuredSeconds, timerBasis, store);
        if (converted > 0L) {
            return converted;
        }
        return TimeUnit.SECONDS.toMillis(Math.max(1L, configuredSeconds));
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

    private static void scheduleGrowthStartRetry(Ref<EntityStore> npcRef,
                                                 Store<EntityStore> store,
                                                 int remainingRetries) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || remainingRetries <= 0) {
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> onGrowthStartRetry(world, npcRef, remainingRetries)),
                CompletableFuture.delayedExecutor(GROWTH_START_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private static void onGrowthStartRetry(@Nullable World world,
                                           @Nullable Ref<EntityStore> npcRef,
                                           int remainingRetries) {
        if (world == null || npcRef == null || !npcRef.isValid() || remainingRetries <= 0) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent stage = type != null ? store.getComponent(npcRef, type) : null;
        if (stage == null || !stage.isGrowthScalingEnabled()) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid != null) {
            if (ACTIVE_GROWTH_TICKERS.add(npcUuid)) {
                scheduleGrowthTick(npcRef, npc, store);
            }
            return;
        }
        if (remainingRetries > 1) {
            scheduleGrowthStartRetry(npcRef, store, remainingRetries - 1);
        }
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
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
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

    private static double resolveAdultScale(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return clampScale(CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0));
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
