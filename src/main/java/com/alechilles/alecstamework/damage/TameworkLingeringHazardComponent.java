package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class TameworkLingeringHazardComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkLingeringHazardComponent> CODEC = BuilderCodec.builder(
            TameworkLingeringHazardComponent.class,
            TameworkLingeringHazardComponent::new
    )
            .<Double>append(
                    new KeyedCodec<>("Radius", Codec.DOUBLE),
                    (component, value) -> component.radius = value == null ? 0.0 : value,
                    component -> component.radius
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("RemainingDurationSeconds", Codec.DOUBLE),
                    (component, value) -> component.remainingDurationSeconds = value == null ? 0.0 : value,
                    component -> component.remainingDurationSeconds
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("TickIntervalSeconds", Codec.DOUBLE),
                    (component, value) -> component.tickIntervalSeconds = value == null ? 0.0 : value,
                    component -> component.tickIntervalSeconds
            )
            .add()
            .<Double>append(
                    new KeyedCodec<>("TickCountdownSeconds", Codec.DOUBLE),
                    (component, value) -> component.tickCountdownSeconds = value == null ? 0.0 : value,
                    component -> component.tickCountdownSeconds
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
                    new KeyedCodec<>("SourceEntityUuid", Codec.STRING),
                    (component, value) -> component.sourceEntityUuid = value,
                    component -> component.sourceEntityUuid
            )
            .add()
            .build();

    private double radius;
    private double remainingDurationSeconds;
    private double tickIntervalSeconds;
    private double tickCountdownSeconds;
    private double damagePerTick;
    private boolean excludeSource = true;
    private String sourceTypeId = "tamework.lingering_hazard";
    private String sourceEntityUuid;

    public TameworkLingeringHazardComponent() {
    }

    public TameworkLingeringHazardComponent(double radius,
                                            double remainingDurationSeconds,
                                            double tickIntervalSeconds,
                                            double tickCountdownSeconds,
                                            double damagePerTick,
                                            boolean excludeSource,
                                            String sourceTypeId,
                                            String sourceEntityUuid) {
        this.radius = radius;
        this.remainingDurationSeconds = remainingDurationSeconds;
        this.tickIntervalSeconds = tickIntervalSeconds;
        this.tickCountdownSeconds = tickCountdownSeconds;
        this.damagePerTick = damagePerTick;
        this.excludeSource = excludeSource;
        this.sourceTypeId = sourceTypeId;
        this.sourceEntityUuid = sourceEntityUuid;
    }

    public static ComponentType<EntityStore, TameworkLingeringHazardComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getLingeringHazardComponentType() : null;
    }

    public double getRadius() {
        return sanitizePositive(radius, 0.0);
    }

    public double getRemainingDurationSeconds() {
        return sanitizePositive(remainingDurationSeconds, 0.0);
    }

    public void setRemainingDurationSeconds(double remainingDurationSeconds) {
        this.remainingDurationSeconds = remainingDurationSeconds;
    }

    public double getTickIntervalSeconds() {
        return sanitizePositive(tickIntervalSeconds, 0.25);
    }

    public double getTickCountdownSeconds() {
        return tickCountdownSeconds;
    }

    public void setTickCountdownSeconds(double tickCountdownSeconds) {
        this.tickCountdownSeconds = tickCountdownSeconds;
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

    public String getSourceEntityUuid() {
        return sourceEntityUuid;
    }

    public boolean isActive() {
        return getRadius() > 0.0 && getRemainingDurationSeconds() > 0.0 && getDamagePerTick() > 0.0;
    }

    @Override
    public TameworkLingeringHazardComponent clone() {
        return new TameworkLingeringHazardComponent(
                radius,
                remainingDurationSeconds,
                tickIntervalSeconds,
                tickCountdownSeconds,
                damagePerTick,
                excludeSource,
                sourceTypeId,
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
