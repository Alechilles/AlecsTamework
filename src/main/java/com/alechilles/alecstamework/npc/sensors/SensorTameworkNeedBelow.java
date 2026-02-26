package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedBelow;
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
 * Sensor that matches when hunger/thirst normalized ratio is at or below a threshold.
 */
public final class SensorTameworkNeedBelow extends TameworkSensorBase {
    private static final double EPSILON = 0.000001;

    private final NeedType needType;
    private final double ratioBelow;

    public SensorTameworkNeedBelow(@Nonnull BuilderSensorTameworkNeedBelow builder,
                                   @Nonnull BuilderSupport support) {
        super(builder);
        this.needType = NeedType.from(builder.getNeed(support));
        this.ratioBelow = clamp01(builder.getRatioBelow(support));
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

        TwNeedsConfig config = resolveNeedsConfig(ref, store, needs);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double min = needType == NeedType.THIRST ? values.getThirstMin() : values.getHungerMin();
        double max = needType == NeedType.THIRST ? values.getThirstMax() : values.getHungerMax();
        double current = needType == NeedType.THIRST ? needs.getThirst() : needs.getHunger();
        double ratio = resolveRatio(current, min, max);
        return ratio <= ratioBelow + EPSILON;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    @Nullable
    private static TwNeedsConfig resolveNeedsConfig(@Nonnull Ref<EntityStore> ref,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nonnull TameworkNeedsComponent needs) {
        String configId = needs.getConfigId();
        if (configId != null && !configId.isBlank()) {
            TwNeedsConfig fromId = TwNeedsConfig.resolveById(configId);
            if (fromId != null) {
                return fromId;
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        return TwNeedsConfig.resolveForRole(roleId);
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
