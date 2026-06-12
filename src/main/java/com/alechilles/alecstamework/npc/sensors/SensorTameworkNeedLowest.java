package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedLowest;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sensor that matches when one normalized need ratio is lower than or tied with another.
 */
public final class SensorTameworkNeedLowest extends TameworkSensorBase {
    private static final double EPSILON = 0.000001;

    private final NeedType needType;
    private final NeedType otherNeedType;
    private final double allowedHigherBy;

    public SensorTameworkNeedLowest(@Nonnull BuilderSensorTameworkNeedLowest builder,
                                    @Nonnull BuilderSupport support) {
        super(builder);
        this.needType = NeedType.from(builder.getNeed(support));
        this.otherNeedType = NeedType.from(builder.getOtherNeed(support));
        this.allowedHigherBy = clamp01(builder.getAllowedHigherBy(support));
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent needs = store.getComponent(ref, needsType);
        if (needs == null) {
            return false;
        }

        NeedsConfigResolver.NeedsSensorConfig config = NeedsConfigResolver.resolveSensorConfig(ref, store, needs);
        if (config == null || !config.enabled()) {
            return false;
        }
        double selectedRatio = resolveRatio(needs, config, needType);
        double otherRatio = resolveRatio(needs, config, otherNeedType);
        return isSelectedNeedLowestForTests(selectedRatio, otherRatio, allowedHigherBy);
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    static boolean isSelectedNeedLowestForTests(double selectedRatio, double otherRatio) {
        return isSelectedNeedLowestForTests(selectedRatio, otherRatio, 0.0);
    }

    static boolean isSelectedNeedLowestForTests(double selectedRatio, double otherRatio, double allowedHigherBy) {
        if (!Double.isFinite(selectedRatio) || !Double.isFinite(otherRatio)) {
            return false;
        }
        double effectiveAllowedHigherBy = Double.isFinite(allowedHigherBy) ? Math.max(0.0, allowedHigherBy) : 0.0;
        return selectedRatio <= otherRatio + effectiveAllowedHigherBy + EPSILON;
    }

    private static double resolveRatio(@Nonnull TameworkNeedsComponent needs,
                                       @Nonnull NeedsConfigResolver.NeedsSensorConfig config,
                                       @Nonnull NeedType type) {
        double min = type == NeedType.THIRST ? config.thirstMin() : config.hungerMin();
        double max = type == NeedType.THIRST ? config.thirstMax() : config.hungerMax();
        double current = type == NeedType.THIRST ? needs.getThirst() : needs.getHunger();
        return resolveRatio(current, min, max);
    }

    private static double resolveRatio(double value, double min, double max) {
        double range = max - min;
        if (!Double.isFinite(range) || range <= 0.0) {
            return 1.0;
        }
        double clamped = clamp(value, min, max);
        return clamp01((clamped - min) / range);
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

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private enum NeedType {
        HUNGER,
        THIRST;

        @Nonnull
        private static NeedType from(@Nullable String raw) {
            if (raw == null) {
                return HUNGER;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "thirst" -> THIRST;
                default -> HUNGER;
            };
        }
    }
}
