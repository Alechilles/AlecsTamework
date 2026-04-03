package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class TameworkProjectileImpactEffectComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkProjectileImpactEffectComponent> CODEC = BuilderCodec.builder(
            TameworkProjectileImpactEffectComponent.class,
            TameworkProjectileImpactEffectComponent::new
    )
            .<Double>append(
                    new KeyedCodec<>("Radius", Codec.DOUBLE),
                    (component, value) -> component.radius = value == null ? 0.0 : value,
                    component -> component.radius
            )
            .add()
            .<String>append(
                    new KeyedCodec<>("EffectId", Codec.STRING),
                    (component, value) -> component.effectId = value,
                    component -> component.effectId
            )
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("ExcludeSource", Codec.BOOLEAN),
                    (component, value) -> component.excludeSource = value == null || value,
                    component -> component.excludeSource
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
    private String effectId;
    private boolean excludeSource = true;
    private String sourceEntityUuid;

    public TameworkProjectileImpactEffectComponent() {
    }

    public TameworkProjectileImpactEffectComponent(double radius,
                                                  String effectId,
                                                  boolean excludeSource,
                                                  String sourceEntityUuid) {
        this.radius = radius;
        this.effectId = effectId;
        this.excludeSource = excludeSource;
        this.sourceEntityUuid = sourceEntityUuid;
    }

    public static ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getProjectileImpactEffectComponentType() : null;
    }

    public double getRadius() {
        return sanitizePositive(radius, 0.0);
    }

    public String getEffectId() {
        return effectId;
    }

    public boolean isExcludeSource() {
        return excludeSource;
    }

    public String getSourceEntityUuid() {
        return sourceEntityUuid;
    }

    public boolean isEnabled() {
        return getRadius() > 0.0 && effectId != null && !effectId.isBlank();
    }

    @Override
    public TameworkProjectileImpactEffectComponent clone() {
        return new TameworkProjectileImpactEffectComponent(radius, effectId, excludeSource, sourceEntityUuid);
    }

    private static double sanitizePositive(double value, double fallback) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return fallback;
        }
        return value;
    }
}
