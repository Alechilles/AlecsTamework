package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class TameworkLingeringHazardProjectileComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkLingeringHazardProjectileComponent> CODEC = BuilderCodec.builder(
            TameworkLingeringHazardProjectileComponent.class,
            TameworkLingeringHazardProjectileComponent::new
    )
            .<Double>append(
                    new KeyedCodec<>("Radius", Codec.DOUBLE),
                    (component, value) -> component.radius = value == null ? 0.0 : value,
                    component -> component.radius
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DurationSeconds", Codec.DOUBLE),
                    (component, value) -> component.durationSeconds = value == null ? 0.0 : value,
                    component -> component.durationSeconds
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("TickIntervalSeconds", Codec.DOUBLE),
                    (component, value) -> component.tickIntervalSeconds = value == null ? 0.0 : value,
                    component -> component.tickIntervalSeconds
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("DamagePerTick", Codec.DOUBLE),
                    (component, value) -> component.damagePerTick = value == null ? 0.0 : value,
                    component -> component.damagePerTick
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("ExcludeSource", Codec.BOOLEAN),
                    (component, value) -> component.excludeSource = value == null || value,
                    component -> component.excludeSource
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("SourceTypeId", Codec.STRING),
                    (component, value) -> component.sourceTypeId = value,
                    component -> component.sourceTypeId
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("EffectId", Codec.STRING),
                    (component, value) -> component.effectId = value,
                    component -> component.effectId
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("SourceEntityUuid", Codec.STRING),
                    (component, value) -> component.sourceEntityUuid = value,
                    component -> component.sourceEntityUuid
            )
            .add()
            .build();

    private double radius;
    private double durationSeconds;
    private double tickIntervalSeconds;
    private double damagePerTick;
    private boolean excludeSource = true;
    private String sourceTypeId = "tamework.lingering_hazard";
    private String effectId;
    private String sourceEntityUuid;

    public TameworkLingeringHazardProjectileComponent() {
    }

    public TameworkLingeringHazardProjectileComponent(double radius,
                                                      double durationSeconds,
                                                      double tickIntervalSeconds,
                                                      double damagePerTick,
                                                      boolean excludeSource,
                                                      String sourceTypeId,
                                                      String effectId,
                                                      String sourceEntityUuid) {
        this.radius = radius;
        this.durationSeconds = durationSeconds;
        this.tickIntervalSeconds = tickIntervalSeconds;
        this.damagePerTick = damagePerTick;
        this.excludeSource = excludeSource;
        this.sourceTypeId = sourceTypeId;
        this.effectId = effectId;
        this.sourceEntityUuid = sourceEntityUuid;
    }

    public static ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getLingeringHazardProjectileComponentType() : null;
    }

    public boolean isEnabled() {
        return getRadius() > 0.0 && getDurationSeconds() > 0.0 && getTickIntervalSeconds() > 0.0 && getDamagePerTick() > 0.0;
    }

    public double getRadius() {
        return sanitizePositive(radius, 0.0);
    }

    public double getDurationSeconds() {
        return sanitizePositive(durationSeconds, 0.0);
    }

    public double getTickIntervalSeconds() {
        return sanitizePositive(tickIntervalSeconds, 0.0);
    }

    public double getDamagePerTick() {
        return sanitizePositive(damagePerTick, 0.0);
    }

    public boolean isExcludeSource() {
        return excludeSource;
    }

    public String getSourceTypeId() {
        return sourceTypeId == null || sourceTypeId.isBlank() ? "tamework.lingering_hazard" : sourceTypeId;
    }

    public String getEffectId() {
        return effectId;
    }

    public String getSourceEntityUuid() {
        return sourceEntityUuid;
    }

    @Override
    public TameworkLingeringHazardProjectileComponent clone() {
        return new TameworkLingeringHazardProjectileComponent(
                radius,
                durationSeconds,
                tickIntervalSeconds,
                damagePerTick,
                excludeSource,
                sourceTypeId,
                effectId,
                sourceEntityUuid
        );
    }

    private static double sanitizePositive(double value, double fallback) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return fallback;
        }
        return value;
    }
}
