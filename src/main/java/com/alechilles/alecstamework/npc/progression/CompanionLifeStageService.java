package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Manages companion life-stage state and optional juvenile growth scaling.
 */
public final class CompanionLifeStageService {
    public static final String STAGE_BABY = "Baby";
    public static final String STAGE_ADOLESCENT = "Adolescent";
    public static final String STAGE_ADULT = "Adult";

    private static final long DEFAULT_BABY_DURATION_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long DEFAULT_ADOLESCENT_DURATION_MS = TimeUnit.MINUTES.toMillis(4);
    private static final long GROWTH_TICK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(3);
    private static final double DEFAULT_BABY_SCALE_FACTOR = 0.33;
    private static final double DEFAULT_ADOLESCENT_SCALE_FACTOR = 0.66;
    private static final double MIN_SCALE = 0.10;

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
            double currentScale = CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0);
            TameworkLifeStageComponent created = new TameworkLifeStageComponent(
                    STAGE_ADULT,
                    0L,
                    0L,
                    0L,
                    resolveBabyScale(currentScale),
                    resolveAdolescentScale(currentScale),
                    currentScale,
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
                                                    boolean hasBabyVariant) {
        if (childRef == null || !childRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long adolescentAtMs = now + DEFAULT_BABY_DURATION_MS;
        long adultAtMs = adolescentAtMs + DEFAULT_ADOLESCENT_DURATION_MS;
        double adultScale = resolveAdultScale(childRef, store);
        double babyScale = resolveBabyScale(adultScale);
        double adolescentScale = resolveAdolescentScale(adultScale);
        boolean growthScalingEnabled = !hasBabyVariant;
        TameworkLifeStageComponent stage = new TameworkLifeStageComponent(
                STAGE_BABY,
                now,
                adolescentAtMs,
                adultAtMs,
                babyScale,
                adolescentScale,
                adultScale,
                growthScalingEnabled
        );
        store.putComponent(childRef, type, stage);
        if (growthScalingEnabled) {
            CompanionModelScaleService.applyScale(childRef, childNpc, store, babyScale);
            scheduleGrowthTick(childRef, childNpc, store);
        }
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
        long now = System.currentTimeMillis();
        String resolvedStage = resolveStageId(stage, now);
        if (!resolvedStage.equals(stage.getStage())) {
            stage.setStage(resolvedStage);
            changed = true;
        }
        if (stage.isGrowthScalingEnabled()) {
            double targetScale = resolveScale(stage, now);
            CompanionModelScaleService.applyScale(npcRef, npc, store, targetScale);
            if (STAGE_ADULT.equals(resolvedStage)) {
                stage.setGrowthScalingEnabled(false);
                changed = true;
            }
        }
        if (changed) {
            store.putComponent(npcRef, type, stage);
        }
    }

    public static boolean isAdult(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String roleIdFallback) {
        if (npcRef != null && npcRef.isValid() && store != null) {
            ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
            if (type != null) {
                TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
                if (stage != null) {
                    refreshLifeStage(npcRef, store.getComponent(npcRef, NPCEntity.getComponentType()), store);
                    return STAGE_ADULT.equals(stage.getStage());
                }
            }
        }
        return isAdultRoleFallback(roleIdFallback);
    }

    public static String resolveCurrentStage(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             @Nullable String roleIdFallback) {
        if (npcRef != null && npcRef.isValid() && store != null) {
            ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
            if (type != null) {
                TameworkLifeStageComponent stage = store.getComponent(npcRef, type);
                if (stage != null) {
                    refreshLifeStage(npcRef, store.getComponent(npcRef, NPCEntity.getComponentType()), store);
                    return stage.getStage();
                }
            }
        }
        return isAdultRoleFallback(roleIdFallback) ? STAGE_ADULT : STAGE_BABY;
    }

    static String resolveStageId(@Nullable TameworkLifeStageComponent component, long nowMs) {
        if (component == null) {
            return STAGE_ADULT;
        }
        long adultAtMs = component.getAdultAtMs();
        long adolescentAtMs = component.getAdolescentAtMs();
        long bornAtMs = component.getBornAtMs();
        if (adultAtMs > 0L && nowMs >= adultAtMs) {
            return STAGE_ADULT;
        }
        if (adolescentAtMs > 0L && nowMs >= adolescentAtMs) {
            return STAGE_ADOLESCENT;
        }
        if (bornAtMs > 0L) {
            return STAGE_BABY;
        }
        String normalized = normalizeStage(component.getStage());
        return normalized != null ? normalized : STAGE_ADULT;
    }

    static double resolveScale(@Nullable TameworkLifeStageComponent component, long nowMs) {
        if (component == null) {
            return 1.0;
        }
        double babyScale = clampScale(component.getBabyScale());
        double adolescentScale = clampScale(component.getAdolescentScale());
        double adultScale = clampScale(component.getAdultScale());
        if (!component.isGrowthScalingEnabled()) {
            return adultScale;
        }
        long bornAtMs = component.getBornAtMs();
        long adolescentAtMs = component.getAdolescentAtMs();
        long adultAtMs = component.getAdultAtMs();
        if (adultAtMs <= bornAtMs || adolescentAtMs <= bornAtMs) {
            return adultScale;
        }
        if (nowMs <= bornAtMs) {
            return babyScale;
        }
        if (nowMs < adolescentAtMs) {
            double progress = (double) (nowMs - bornAtMs) / (double) (adolescentAtMs - bornAtMs);
            return lerp(babyScale, adolescentScale, progress);
        }
        if (nowMs < adultAtMs) {
            double progress = (double) (nowMs - adolescentAtMs) / (double) (adultAtMs - adolescentAtMs);
            return lerp(adolescentScale, adultScale, progress);
        }
        return adultScale;
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
        double adultScale = component.getAdultScale();
        if (!Double.isFinite(adultScale) || adultScale <= 0.0) {
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
        if (component.isGrowthScalingEnabled()
                && component.getBornAtMs() > 0L
                && component.getAdultAtMs() > 0L
                && component.getAdultAtMs() <= component.getBornAtMs()) {
            component.setGrowthScalingEnabled(false);
            component.setStage(STAGE_ADULT);
            changed = true;
        }
        return changed;
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

    private static void onGrowthTick(World world, UUID npcUuid) {
        if (world == null || npcUuid == null) {
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
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        refreshLifeStage(npcRef, npc, store);
        if (isGrowthInProgress(npcRef, store)) {
            scheduleGrowthTick(npcRef, npc, store);
        }
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

    private static double resolveAdultScale(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        double current = CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0);
        double sizeMultiplier = TraitModifierService.resolveMultiplier(npcRef, store, "SizeMultiplier", 1.0);
        if (!Double.isFinite(sizeMultiplier) || sizeMultiplier <= 0.0) {
            sizeMultiplier = 1.0;
        }
        return clampScale(current * sizeMultiplier);
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

    private static double lerp(double start, double end, double progress) {
        double clamped = progress;
        if (clamped < 0.0) {
            clamped = 0.0;
        } else if (clamped > 1.0) {
            clamped = 1.0;
        }
        return start + (end - start) * clamped;
    }
}
