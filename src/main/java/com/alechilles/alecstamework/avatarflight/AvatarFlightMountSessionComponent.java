package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Player-side durable linkage and recovery state for an NPC-backed avatar-flight session. */
public final class AvatarFlightMountSessionComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightMountSessionComponent> CODEC = BuilderCodec.builder(
            AvatarFlightMountSessionComponent.class,
            AvatarFlightMountSessionComponent::new
    )
            .append(new KeyedCodec<>("SourceNpcUuid", Codec.STRING),
                    AvatarFlightMountSessionComponent::setSourceNpcUuid,
                    AvatarFlightMountSessionComponent::getSourceNpcUuid).add()
            .append(new KeyedCodec<>("SourceWorld", Codec.STRING),
                    AvatarFlightMountSessionComponent::setSourceWorld,
                    AvatarFlightMountSessionComponent::getSourceWorld).add()
            .append(new KeyedCodec<>("ConfigId", Codec.STRING),
                    AvatarFlightMountSessionComponent::setConfigId,
                    AvatarFlightMountSessionComponent::getConfigId).add()
            .append(new KeyedCodec<>("RuntimeEpoch", Codec.STRING),
                    AvatarFlightMountSessionComponent::setRuntimeEpoch,
                    AvatarFlightMountSessionComponent::getRuntimeEpoch).add()
            .append(new KeyedCodec<>("Phase", Codec.STRING),
                    AvatarFlightMountSessionComponent::setPhaseName,
                    AvatarFlightMountSessionComponent::getPhaseName).add()
            .append(new KeyedCodec<>("MountStartedAtMs", Codec.LONG),
                    AvatarFlightMountSessionComponent::setMountStartedAtMs,
                    AvatarFlightMountSessionComponent::getMountStartedAtMs).add()
            .append(new KeyedCodec<>("OriginX", Codec.DOUBLE),
                    AvatarFlightMountSessionComponent::setOriginX,
                    AvatarFlightMountSessionComponent::getOriginX).add()
            .append(new KeyedCodec<>("OriginY", Codec.DOUBLE),
                    AvatarFlightMountSessionComponent::setOriginY,
                    AvatarFlightMountSessionComponent::getOriginY).add()
            .append(new KeyedCodec<>("OriginZ", Codec.DOUBLE),
                    AvatarFlightMountSessionComponent::setOriginZ,
                    AvatarFlightMountSessionComponent::getOriginZ).add()
            .append(new KeyedCodec<>("OriginYaw", Codec.FLOAT),
                    AvatarFlightMountSessionComponent::setOriginYaw,
                    AvatarFlightMountSessionComponent::getOriginYaw).add()
            .append(new KeyedCodec<>("LastSafeGroundValid", Codec.BOOLEAN),
                    AvatarFlightMountSessionComponent::setLastSafeGroundValid,
                    AvatarFlightMountSessionComponent::isLastSafeGroundValid).add()
            .append(new KeyedCodec<>("LastSafeGroundX", Codec.DOUBLE),
                    AvatarFlightMountSessionComponent::setLastSafeGroundX,
                    AvatarFlightMountSessionComponent::getLastSafeGroundX).add()
            .append(new KeyedCodec<>("LastSafeGroundY", Codec.DOUBLE),
                    AvatarFlightMountSessionComponent::setLastSafeGroundY,
                    AvatarFlightMountSessionComponent::getLastSafeGroundY).add()
            .append(new KeyedCodec<>("LastSafeGroundZ", Codec.DOUBLE),
                    AvatarFlightMountSessionComponent::setLastSafeGroundZ,
                    AvatarFlightMountSessionComponent::getLastSafeGroundZ).add()
            .append(new KeyedCodec<>("LastSafeGroundYaw", Codec.FLOAT),
                    AvatarFlightMountSessionComponent::setLastSafeGroundYaw,
                    AvatarFlightMountSessionComponent::getLastSafeGroundYaw).add()
            .append(new KeyedCodec<>("DismountHoldStartedAtMs", Codec.LONG),
                    AvatarFlightMountSessionComponent::setDismountHoldStartedAtMs,
                    AvatarFlightMountSessionComponent::getDismountHoldStartedAtMs).add()
            .build();

    private String sourceNpcUuid = "";
    private String sourceWorld = "";
    private String configId = "";
    private String runtimeEpoch = "";
    private AvatarFlightMountPhase phase = AvatarFlightMountPhase.PREPARING;
    private long mountStartedAtMs;
    private double originX;
    private double originY;
    private double originZ;
    private float originYaw;
    private boolean lastSafeGroundValid;
    private double lastSafeGroundX;
    private double lastSafeGroundY;
    private double lastSafeGroundZ;
    private float lastSafeGroundYaw;
    private long dismountHoldStartedAtMs;

    public AvatarFlightMountSessionComponent() {
    }

    public AvatarFlightMountSessionComponent(@Nullable String sourceNpcUuid,
                                             @Nullable String sourceWorld,
                                             @Nullable String configId,
                                             long mountStartedAtMs) {
        setSourceNpcUuid(sourceNpcUuid);
        setSourceWorld(sourceWorld);
        setConfigId(configId);
        setRuntimeEpoch(AvatarFlightRuntimeEpoch.current());
        this.mountStartedAtMs = mountStartedAtMs;
    }

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightMountSessionComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightMountSessionComponentType();
    }

    public void captureOrigin(double x, double y, double z, float yaw) {
        originX = finite(x);
        originY = finite(y);
        originZ = finite(z);
        originYaw = finite(yaw);
    }

    public void captureLastSafeGround(double x, double y, double z, float yaw) {
        lastSafeGroundValid = true;
        lastSafeGroundX = finite(x);
        lastSafeGroundY = finite(y);
        lastSafeGroundZ = finite(z);
        lastSafeGroundYaw = finite(yaw);
    }

    @Nonnull public String getSourceNpcUuid() { return clean(sourceNpcUuid); }
    public void setSourceNpcUuid(@Nullable String value) { sourceNpcUuid = clean(value); }
    @Nonnull public String getSourceWorld() { return clean(sourceWorld); }
    public void setSourceWorld(@Nullable String value) { sourceWorld = clean(value); }
    @Nonnull public String getConfigId() { return clean(configId); }
    public void setConfigId(@Nullable String value) { configId = clean(value); }
    @Nonnull public String getRuntimeEpoch() { return clean(runtimeEpoch); }
    public void setRuntimeEpoch(@Nullable String value) { runtimeEpoch = clean(value); }
    @Nonnull public AvatarFlightMountPhase getPhase() { return phase == null ? AvatarFlightMountPhase.PREPARING : phase; }
    public void setPhase(@Nullable AvatarFlightMountPhase value) { phase = value == null ? AvatarFlightMountPhase.PREPARING : value; }
    @Nonnull public String getPhaseName() { return getPhase().name(); }
    public void setPhaseName(@Nullable String value) { phase = AvatarFlightMountPhase.parse(value); }
    public long getMountStartedAtMs() { return mountStartedAtMs; }
    public void setMountStartedAtMs(long value) { mountStartedAtMs = value; }
    public double getOriginX() { return originX; }
    public void setOriginX(double value) { originX = finite(value); }
    public double getOriginY() { return originY; }
    public void setOriginY(double value) { originY = finite(value); }
    public double getOriginZ() { return originZ; }
    public void setOriginZ(double value) { originZ = finite(value); }
    public float getOriginYaw() { return originYaw; }
    public void setOriginYaw(float value) { originYaw = finite(value); }
    public boolean isLastSafeGroundValid() { return lastSafeGroundValid; }
    public void setLastSafeGroundValid(boolean value) { lastSafeGroundValid = value; }
    public double getLastSafeGroundX() { return lastSafeGroundX; }
    public void setLastSafeGroundX(double value) { lastSafeGroundX = finite(value); }
    public double getLastSafeGroundY() { return lastSafeGroundY; }
    public void setLastSafeGroundY(double value) { lastSafeGroundY = finite(value); }
    public double getLastSafeGroundZ() { return lastSafeGroundZ; }
    public void setLastSafeGroundZ(double value) { lastSafeGroundZ = finite(value); }
    public float getLastSafeGroundYaw() { return lastSafeGroundYaw; }
    public void setLastSafeGroundYaw(float value) { lastSafeGroundYaw = finite(value); }
    public long getDismountHoldStartedAtMs() { return dismountHoldStartedAtMs; }
    public void setDismountHoldStartedAtMs(long value) { dismountHoldStartedAtMs = value; }

    @Override
    public AvatarFlightMountSessionComponent clone() {
        AvatarFlightMountSessionComponent copy = new AvatarFlightMountSessionComponent(
                sourceNpcUuid, sourceWorld, configId, mountStartedAtMs);
        copy.runtimeEpoch = runtimeEpoch;
        copy.phase = getPhase();
        copy.captureOrigin(originX, originY, originZ, originYaw);
        copy.lastSafeGroundValid = lastSafeGroundValid;
        copy.lastSafeGroundX = lastSafeGroundX;
        copy.lastSafeGroundY = lastSafeGroundY;
        copy.lastSafeGroundZ = lastSafeGroundZ;
        copy.lastSafeGroundYaw = lastSafeGroundYaw;
        copy.dismountHoldStartedAtMs = dismountHoldStartedAtMs;
        return copy;
    }

    private static String clean(@Nullable String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static double finite(double value) { return Double.isFinite(value) ? value : 0.0; }
    private static float finite(float value) { return Float.isFinite(value) ? value : 0.0f; }
}
