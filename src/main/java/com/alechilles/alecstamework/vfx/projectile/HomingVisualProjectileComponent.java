package com.alechilles.alecstamework.vfx.projectile;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Data-only steering and lifecycle state for a non-combat visual projectile. */
public final class HomingVisualProjectileComponent implements Component<EntityStore> {
    public static final BuilderCodec<HomingVisualProjectileComponent> CODEC = BuilderCodec.builder(
            HomingVisualProjectileComponent.class,
            HomingVisualProjectileComponent::new
    )
            .append(new KeyedCodec<>("DestinationUuid", Codec.STRING),
                    HomingVisualProjectileComponent::setDestinationUuid,
                    HomingVisualProjectileComponent::getDestinationUuid).add()
            .append(new KeyedCodec<>("DestinationAnchor", Codec.STRING),
                    HomingVisualProjectileComponent::setDestinationAnchorName,
                    HomingVisualProjectileComponent::getDestinationAnchorName).add()
            .append(new KeyedCodec<>("OwnerUuid", Codec.STRING),
                    HomingVisualProjectileComponent::setOwnerUuid,
                    HomingVisualProjectileComponent::getOwnerUuid).add()
            .append(new KeyedCodec<>("SourceUuid", Codec.STRING),
                    HomingVisualProjectileComponent::setSourceUuid,
                    HomingVisualProjectileComponent::getSourceUuid).add()
            .append(new KeyedCodec<>("WorldName", Codec.STRING),
                    HomingVisualProjectileComponent::setWorldName,
                    HomingVisualProjectileComponent::getWorldName).add()
            .append(new KeyedCodec<>("SessionGeneration", Codec.LONG),
                    HomingVisualProjectileComponent::setSessionGeneration,
                    HomingVisualProjectileComponent::getSessionGeneration).add()
            .append(new KeyedCodec<>("Speed", Codec.DOUBLE),
                    HomingVisualProjectileComponent::setSpeed,
                    HomingVisualProjectileComponent::getSpeed).add()
            .append(new KeyedCodec<>("TurnRateDegreesPerSecond", Codec.DOUBLE),
                    HomingVisualProjectileComponent::setTurnRateDegreesPerSecond,
                    HomingVisualProjectileComponent::getTurnRateDegreesPerSecond).add()
            .append(new KeyedCodec<>("ArrivalRadius", Codec.DOUBLE),
                    HomingVisualProjectileComponent::setArrivalRadius,
                    HomingVisualProjectileComponent::getArrivalRadius).add()
            .append(new KeyedCodec<>("RemainingLifetimeSeconds", Codec.DOUBLE),
                    HomingVisualProjectileComponent::setRemainingLifetimeSeconds,
                    HomingVisualProjectileComponent::getRemainingLifetimeSeconds).add()
            .append(new KeyedCodec<>("LastDirectionX", Codec.DOUBLE),
                    (component, value) -> component.lastDirectionX = finite(value),
                    component -> component.lastDirectionX).add()
            .append(new KeyedCodec<>("LastDirectionY", Codec.DOUBLE),
                    (component, value) -> component.lastDirectionY = finite(value),
                    component -> component.lastDirectionY).add()
            .append(new KeyedCodec<>("LastDirectionZ", Codec.DOUBLE),
                    (component, value) -> component.lastDirectionZ = finite(value),
                    component -> component.lastDirectionZ).add()
            .build();

    private String destinationUuid = "";
    private HomingVisualProjectileAnchor destinationAnchor = HomingVisualProjectileAnchor.BODY;
    private String ownerUuid = "";
    private String sourceUuid = "";
    private String worldName = "";
    private long sessionGeneration;
    private double speed = 8.0D;
    private double turnRateDegreesPerSecond;
    private double arrivalRadius = 0.18D;
    private double remainingLifetimeSeconds = 2.0D;
    private double lastDirectionX;
    private double lastDirectionY;
    private double lastDirectionZ;

    public HomingVisualProjectileComponent() {
    }

    public HomingVisualProjectileComponent(@Nonnull String destinationUuid,
                                           @Nonnull HomingVisualProjectileSpec spec,
                                           @Nullable String ownerUuid,
                                           @Nullable String sourceUuid,
                                           @Nullable String worldName,
                                           long sessionGeneration) {
        setDestinationUuid(destinationUuid);
        setDestinationAnchor(spec.destinationAnchor());
        setOwnerUuid(ownerUuid);
        setSourceUuid(sourceUuid);
        setWorldName(worldName);
        setSessionGeneration(sessionGeneration);
        setSpeed(spec.speed());
        setTurnRateDegreesPerSecond(spec.turnRateDegreesPerSecond());
        setArrivalRadius(spec.arrivalRadius());
        setRemainingLifetimeSeconds(spec.lifetimeSeconds());
    }

    @Nullable
    public static ComponentType<EntityStore, HomingVisualProjectileComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getHomingVisualProjectileComponentType();
    }

    @Nonnull public String getDestinationUuid() { return clean(destinationUuid); }
    public void setDestinationUuid(@Nullable String value) { destinationUuid = clean(value); }
    @Nonnull public HomingVisualProjectileAnchor getDestinationAnchor() {
        return destinationAnchor == null ? HomingVisualProjectileAnchor.BODY : destinationAnchor;
    }
    public void setDestinationAnchor(@Nullable HomingVisualProjectileAnchor value) {
        destinationAnchor = value == null ? HomingVisualProjectileAnchor.BODY : value;
    }
    @Nonnull public String getDestinationAnchorName() { return getDestinationAnchor().name(); }
    public void setDestinationAnchorName(@Nullable String value) {
        try {
            setDestinationAnchor(value == null ? null : HomingVisualProjectileAnchor.valueOf(value.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            setDestinationAnchor(HomingVisualProjectileAnchor.BODY);
        }
    }
    @Nonnull public String getOwnerUuid() { return clean(ownerUuid); }
    public void setOwnerUuid(@Nullable String value) { ownerUuid = clean(value); }
    @Nonnull public String getSourceUuid() { return clean(sourceUuid); }
    public void setSourceUuid(@Nullable String value) { sourceUuid = clean(value); }
    @Nonnull public String getWorldName() { return clean(worldName); }
    public void setWorldName(@Nullable String value) { worldName = clean(value); }
    public long getSessionGeneration() { return sessionGeneration; }
    public void setSessionGeneration(long value) { sessionGeneration = Math.max(0L, value); }
    public double getSpeed() { return speed; }
    public void setSpeed(double value) { speed = positive(value, 8.0D); }
    public double getTurnRateDegreesPerSecond() { return turnRateDegreesPerSecond; }
    public void setTurnRateDegreesPerSecond(double value) { turnRateDegreesPerSecond = nonNegative(value); }
    public double getArrivalRadius() { return arrivalRadius; }
    public void setArrivalRadius(double value) { arrivalRadius = positive(value, 0.18D); }
    public double getRemainingLifetimeSeconds() { return remainingLifetimeSeconds; }
    public void setRemainingLifetimeSeconds(double value) {
        remainingLifetimeSeconds = Double.isFinite(value) ? value : 0.0D;
    }
    @Nonnull public Vector3d getLastDirection() {
        return new Vector3d(lastDirectionX, lastDirectionY, lastDirectionZ);
    }
    public void setLastDirection(@Nullable Vector3d value) {
        lastDirectionX = value == null ? 0.0D : finite(value.x);
        lastDirectionY = value == null ? 0.0D : finite(value.y);
        lastDirectionZ = value == null ? 0.0D : finite(value.z);
    }
    public boolean isSessionBound() {
        return sessionGeneration > 0L && !getOwnerUuid().isBlank() && !getWorldName().isBlank();
    }

    @Override
    public HomingVisualProjectileComponent clone() {
        HomingVisualProjectileComponent copy = new HomingVisualProjectileComponent();
        copy.destinationUuid = destinationUuid;
        copy.destinationAnchor = getDestinationAnchor();
        copy.ownerUuid = ownerUuid;
        copy.sourceUuid = sourceUuid;
        copy.worldName = worldName;
        copy.sessionGeneration = sessionGeneration;
        copy.speed = speed;
        copy.turnRateDegreesPerSecond = turnRateDegreesPerSecond;
        copy.arrivalRadius = arrivalRadius;
        copy.remainingLifetimeSeconds = remainingLifetimeSeconds;
        copy.lastDirectionX = lastDirectionX;
        copy.lastDirectionY = lastDirectionY;
        copy.lastDirectionZ = lastDirectionZ;
        return copy;
    }

    @Nonnull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D ? value : 0.0D;
    }

    private static double finite(@Nullable Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0D;
    }
}
