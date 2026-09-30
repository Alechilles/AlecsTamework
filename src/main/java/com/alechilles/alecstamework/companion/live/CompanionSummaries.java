package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds the UI summary of a loaded companion (spec 6.6). Runs on the body's world thread. */
public final class CompanionSummaries {
    /** Values that need engine lookups beyond Tamework components. */
    public interface Sources {
        @Nullable String roleId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
        @Nullable String nameKey(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
        @Nullable String iconId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nullable String roleId);
        /** {current, max}, or null when the body has no health stat. */
        @Nullable float[] health(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
        /** Life-stage presentation, or null when the companion has no life stage. */
        @Nullable LifeStageView lifeStage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nullable String roleId);
        @Nonnull String harvestAlarmName();
    }

    public record LifeStageView(@Nullable String stage, double progress, @Nullable String nextStage, long remainingMs) {
    }

    /** Raw inputs; {@link #build} clamps them. */
    public record Inputs(@Nullable String customName, @Nullable String nameKey, @Nullable String roleId,
                         @Nullable String iconId, float healthCurrent, float healthMax,
                         @Nullable String happinessConfigId, double happiness,
                         @Nullable String needsConfigId, double hunger, double thirst,
                         boolean breedingEnabled, long breedingCooldownUntilMs, long breedingCooldownStartedAtMs,
                         long breedingCooldownDurationMs, long harvestAlarmUntilMs,
                         @Nullable String levelingConfigId, int level, double currentXp, double totalXp,
                         int talentPointsSpent, @Nonnull Map<String, Double> traits,
                         @Nullable String lifeStage, double lifeStageProgress, @Nullable String nextLifeStage,
                         long lifeStageRemainingMs) {
    }

    private final Sources sources;

    public CompanionSummaries(@Nonnull Sources sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** Clamps non-finite and negative numbers so no NaN reaches the saved file. */
    @Nonnull
    public static CompanionSummary build(@Nonnull Inputs in, long observedAtMs) {
        float max = finiteNonNegative(in.healthMax());
        float current = Math.min(finiteNonNegative(in.healthCurrent()), max);
        Map<String, Double> traits = new LinkedHashMap<>();
        in.traits().forEach((id, value) -> {
            if (id != null && value != null && Double.isFinite(value)) {
                traits.put(id, value);
            }
        });
        return new CompanionSummary(in.customName(), in.nameKey(), in.roleId(), in.iconId(), current, max,
                in.happinessConfigId(), finite(in.happiness()), in.needsConfigId(),
                finiteNonNegative(in.hunger()), finiteNonNegative(in.thirst()),
                in.breedingEnabled(), in.breedingCooldownUntilMs(), in.breedingCooldownStartedAtMs(),
                Math.max(0L, in.breedingCooldownDurationMs()), in.harvestAlarmUntilMs(),
                in.levelingConfigId(), Math.max(0, in.level()), finiteNonNegative(in.currentXp()),
                finiteNonNegative(in.totalXp()), Math.max(0, in.talentPointsSpent()), traits,
                in.lifeStage(), clamp01(in.lifeStageProgress()), in.nextLifeStage(),
                Math.max(0L, in.lifeStageRemainingMs()), observedAtMs);
    }

    /**
     * Reads the live body and builds its summary. Returns null when the ref is no longer valid,
     * so a caller keeps the previously stored summary instead of overwriting it with a blank one.
     */
    @Nullable
    public CompanionSummary capture(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, long observedAtMs) {
        if (!ref.isValid()) {
            return null;
        }
        String roleId = sources.roleId(ref, store);
        TameworkNpcNameComponent name = get(store, ref, TameworkNpcNameComponent.getComponentType());
        TameworkHappinessComponent happiness = get(store, ref, TameworkHappinessComponent.getComponentType());
        TameworkNeedsComponent needs = get(store, ref, TameworkNeedsComponent.getComponentType());
        TameworkBreedingComponent breeding = get(store, ref, TameworkBreedingComponent.getComponentType());
        TameworkAlarmComponent alarms = get(store, ref, TameworkAlarmComponent.getComponentType());
        TameworkLevelingComponent leveling = get(store, ref, TameworkLevelingComponent.getComponentType());
        TameworkTalentsComponent talents = get(store, ref, TameworkTalentsComponent.getComponentType());
        TameworkTraitsComponent traits = get(store, ref, TameworkTraitsComponent.getComponentType());
        float[] health = sources.health(ref, store);
        LifeStageView stage = sources.lifeStage(ref, store, roleId);
        TameworkAlarmComponent.AlarmEntry harvest = alarms == null ? null : alarms.getAlarm(sources.harvestAlarmName());
        Map<String, Double> traitValues = new LinkedHashMap<>();
        if (traits != null && traits.getTraitValues() != null) {
            for (TameworkTraitsComponent.TraitValue value : traits.getTraitValues()) {
                if (value != null && value.getId() != null) {
                    traitValues.put(value.getId(), value.getValue());
                }
            }
        }
        String customName = name == null || name.getName() == null || name.getName().isBlank() ? null : name.getName().trim();
        return build(new Inputs(customName, sources.nameKey(ref, store), roleId, sources.iconId(ref, store, roleId),
                health == null ? 0f : health[0], health == null ? 0f : health[1],
                happiness == null ? null : happiness.getConfigId(), happiness == null ? 0.0 : happiness.getValue(),
                needs == null ? null : needs.getConfigId(), needs == null ? 0.0 : needs.getHunger(),
                needs == null ? 0.0 : needs.getThirst(),
                breeding != null && breeding.isEnabled(), breeding == null ? 0L : breeding.getCooldownUntilMs(),
                breeding == null ? 0L : breeding.getCooldownStartedAtMs(),
                breeding == null ? 0L : breeding.getCooldownDurationMs(),
                harvest == null ? 0L : harvest.getUntilMs(),
                leveling == null ? null : leveling.getConfigId(), leveling == null ? 0 : leveling.getLevel(),
                leveling == null ? 0.0 : leveling.getCurrentXp(), leveling == null ? 0.0 : leveling.getTotalXp(),
                talents == null ? 0 : talents.getSpentPoints(), traitValues,
                stage == null ? null : stage.stage(), stage == null ? 0.0 : stage.progress(),
                stage == null ? null : stage.nextStage(), stage == null ? 0L : stage.remainingMs()),
                observedAtMs);
    }

    @Nullable
    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> T get(
            Store<EntityStore> store, Ref<EntityStore> ref,
            @Nullable com.hypixel.hytale.component.ComponentType<EntityStore, T> type) {
        return type == null ? null : store.getComponent(ref, type);
    }

    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) && value > 0f ? value : 0f;
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
