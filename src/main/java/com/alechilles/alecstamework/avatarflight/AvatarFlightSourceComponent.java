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

/** NPC-side reverse link and role snapshot for an active avatar-flight mount session. */
public final class AvatarFlightSourceComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightSourceComponent> CODEC = BuilderCodec.builder(
            AvatarFlightSourceComponent.class,
            AvatarFlightSourceComponent::new
    )
            .append(new KeyedCodec<>("RiderUuid", Codec.STRING),
                    AvatarFlightSourceComponent::setRiderUuid,
                    AvatarFlightSourceComponent::getRiderUuid).add()
            .append(new KeyedCodec<>("RuntimeEpoch", Codec.STRING),
                    AvatarFlightSourceComponent::setRuntimeEpoch,
                    AvatarFlightSourceComponent::getRuntimeEpoch).add()
            .append(new KeyedCodec<>("OriginalRoleId", Codec.STRING),
                    AvatarFlightSourceComponent::setOriginalRoleId,
                    AvatarFlightSourceComponent::getOriginalRoleId).add()
            .append(new KeyedCodec<>("OriginalRoleIndex", Codec.INTEGER),
                    AvatarFlightSourceComponent::setOriginalRoleIndex,
                    AvatarFlightSourceComponent::getOriginalRoleIndex).add()
            .append(new KeyedCodec<>("PreviousState", Codec.STRING),
                    AvatarFlightSourceComponent::setPreviousState,
                    AvatarFlightSourceComponent::getPreviousState).add()
            .append(new KeyedCodec<>("PreviousSubState", Codec.STRING),
                    AvatarFlightSourceComponent::setPreviousSubState,
                    AvatarFlightSourceComponent::getPreviousSubState).add()
            .append(new KeyedCodec<>("PreviousMotionController", Codec.STRING),
                    AvatarFlightSourceComponent::setPreviousMotionController,
                    AvatarFlightSourceComponent::getPreviousMotionController).add()
            .append(new KeyedCodec<>("OriginX", Codec.DOUBLE),
                    AvatarFlightSourceComponent::setOriginX,
                    AvatarFlightSourceComponent::getOriginX).add()
            .append(new KeyedCodec<>("OriginY", Codec.DOUBLE),
                    AvatarFlightSourceComponent::setOriginY,
                    AvatarFlightSourceComponent::getOriginY).add()
            .append(new KeyedCodec<>("OriginZ", Codec.DOUBLE),
                    AvatarFlightSourceComponent::setOriginZ,
                    AvatarFlightSourceComponent::getOriginZ).add()
            .append(new KeyedCodec<>("OriginYaw", Codec.FLOAT),
                    AvatarFlightSourceComponent::setOriginYaw,
                    AvatarFlightSourceComponent::getOriginYaw).add()
            .append(new KeyedCodec<>("OriginPitch", Codec.FLOAT),
                    AvatarFlightSourceComponent::setOriginPitch,
                    AvatarFlightSourceComponent::getOriginPitch).add()
            .append(new KeyedCodec<>("OriginRoll", Codec.FLOAT),
                    AvatarFlightSourceComponent::setOriginRoll,
                    AvatarFlightSourceComponent::getOriginRoll).add()
            .append(new KeyedCodec<>("WasInteractable", Codec.BOOLEAN),
                    AvatarFlightSourceComponent::setWasInteractable,
                    AvatarFlightSourceComponent::wasInteractable).add()
            .append(new KeyedCodec<>("WasVisible", Codec.BOOLEAN),
                    AvatarFlightSourceComponent::setWasVisible,
                    AvatarFlightSourceComponent::wasVisible).add()
            .append(new KeyedCodec<>("WasFrozen", Codec.BOOLEAN),
                    AvatarFlightSourceComponent::setWasFrozen,
                    AvatarFlightSourceComponent::wasFrozen).add()
            .append(new KeyedCodec<>("WasIntangible", Codec.BOOLEAN),
                    AvatarFlightSourceComponent::setWasIntangible,
                    AvatarFlightSourceComponent::wasIntangible).add()
            .append(new KeyedCodec<>("WasInvulnerable", Codec.BOOLEAN),
                    AvatarFlightSourceComponent::setWasInvulnerable,
                    AvatarFlightSourceComponent::wasInvulnerable).add()
            .append(new KeyedCodec<>("Phase", Codec.STRING),
                    AvatarFlightSourceComponent::setPhaseName,
                    AvatarFlightSourceComponent::getPhaseName).add()
            .append(new KeyedCodec<>("SchemaVersion", Codec.INTEGER),
                    AvatarFlightSourceComponent::setSchemaVersion,
                    AvatarFlightSourceComponent::getSchemaVersion).add()
            .build();

    private String riderUuid = "";
    private String runtimeEpoch = "";
    private String originalRoleId = "";
    private int originalRoleIndex = -1;
    private String previousState = "";
    private String previousSubState = "";
    private String previousMotionController = "";
    private double originX;
    private double originY;
    private double originZ;
    private float originYaw;
    private float originPitch;
    private float originRoll;
    private boolean wasInteractable;
    private boolean wasVisible;
    private boolean wasFrozen;
    private boolean wasIntangible;
    private boolean wasInvulnerable;
    private AvatarFlightMountPhase phase = AvatarFlightMountPhase.PREPARING;
    private int schemaVersion = 2;

    public AvatarFlightSourceComponent() {
    }

    public AvatarFlightSourceComponent(@Nullable String riderUuid,
                                       @Nullable String originalRoleId,
                                       int originalRoleIndex) {
        setRiderUuid(riderUuid);
        setRuntimeEpoch(AvatarFlightRuntimeEpoch.current());
        setOriginalRoleId(originalRoleId);
        setOriginalRoleIndex(originalRoleIndex);
    }

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightSourceComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightSourceComponentType();
    }

    public void captureOrigin(double x, double y, double z, float yaw, float pitch, float roll) {
        originX = finite(x);
        originY = finite(y);
        originZ = finite(z);
        originYaw = finite(yaw);
        originPitch = finite(pitch);
        originRoll = finite(roll);
    }

    @Nonnull public String getRiderUuid() { return clean(riderUuid); }
    public void setRiderUuid(@Nullable String value) { riderUuid = clean(value); }
    @Nonnull public String getRuntimeEpoch() { return clean(runtimeEpoch); }
    public void setRuntimeEpoch(@Nullable String value) { runtimeEpoch = clean(value); }
    @Nonnull public String getOriginalRoleId() { return clean(originalRoleId); }
    public void setOriginalRoleId(@Nullable String value) { originalRoleId = clean(value); }
    public int getOriginalRoleIndex() { return originalRoleIndex; }
    public void setOriginalRoleIndex(int value) { originalRoleIndex = value; }
    @Nonnull public String getPreviousState() { return clean(previousState); }
    public void setPreviousState(@Nullable String value) { previousState = clean(value); }
    @Nonnull public String getPreviousSubState() { return clean(previousSubState); }
    public void setPreviousSubState(@Nullable String value) { previousSubState = clean(value); }
    @Nonnull public String getPreviousMotionController() { return clean(previousMotionController); }
    public void setPreviousMotionController(@Nullable String value) { previousMotionController = clean(value); }
    public double getOriginX() { return originX; }
    public void setOriginX(double value) { originX = finite(value); }
    public double getOriginY() { return originY; }
    public void setOriginY(double value) { originY = finite(value); }
    public double getOriginZ() { return originZ; }
    public void setOriginZ(double value) { originZ = finite(value); }
    public float getOriginYaw() { return originYaw; }
    public void setOriginYaw(float value) { originYaw = finite(value); }
    public float getOriginPitch() { return originPitch; }
    public void setOriginPitch(float value) { originPitch = finite(value); }
    public float getOriginRoll() { return originRoll; }
    public void setOriginRoll(float value) { originRoll = finite(value); }
    public boolean wasInteractable() { return wasInteractable; }
    public void setWasInteractable(boolean value) { wasInteractable = value; }
    public boolean wasVisible() { return wasVisible; }
    public void setWasVisible(boolean value) { wasVisible = value; }
    public boolean wasFrozen() { return wasFrozen; }
    public void setWasFrozen(boolean value) { wasFrozen = value; }
    public boolean wasIntangible() { return wasIntangible; }
    public void setWasIntangible(boolean value) { wasIntangible = value; }
    public boolean wasInvulnerable() { return wasInvulnerable; }
    public void setWasInvulnerable(boolean value) { wasInvulnerable = value; }
    @Nonnull public AvatarFlightMountPhase getPhase() { return phase == null ? AvatarFlightMountPhase.PREPARING : phase; }
    public void setPhase(@Nullable AvatarFlightMountPhase value) { phase = value == null ? AvatarFlightMountPhase.PREPARING : value; }
    @Nonnull public String getPhaseName() { return getPhase().name(); }
    public void setPhaseName(@Nullable String value) { phase = AvatarFlightMountPhase.parse(value); }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = Math.max(1, value); }

    @Override
    public AvatarFlightSourceComponent clone() {
        AvatarFlightSourceComponent copy = new AvatarFlightSourceComponent(riderUuid, originalRoleId, originalRoleIndex);
        copy.runtimeEpoch = runtimeEpoch;
        copy.previousState = previousState;
        copy.previousSubState = previousSubState;
        copy.previousMotionController = previousMotionController;
        copy.captureOrigin(originX, originY, originZ, originYaw, originPitch, originRoll);
        copy.wasInteractable = wasInteractable;
        copy.wasVisible = wasVisible;
        copy.wasFrozen = wasFrozen;
        copy.wasIntangible = wasIntangible;
        copy.wasInvulnerable = wasInvulnerable;
        copy.phase = getPhase();
        copy.schemaVersion = schemaVersion;
        return copy;
    }

    private static String clean(@Nullable String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static double finite(double value) { return Double.isFinite(value) ? value : 0.0; }
    private static float finite(float value) { return Float.isFinite(value) ? value : 0.0f; }
}
