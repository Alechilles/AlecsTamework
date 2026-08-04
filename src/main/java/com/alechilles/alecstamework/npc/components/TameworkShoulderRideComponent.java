package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/** Marks an NPC passenger created by Tamework's shoulder-ride command. */
public final class TameworkShoulderRideComponent
        implements Component<EntityStore> {
    public static final BuilderCodec<TameworkShoulderRideComponent> CODEC =
            BuilderCodec.builder(TameworkShoulderRideComponent.class,
                    TameworkShoulderRideComponent::new)
                    .append(new KeyedCodec<>("OwnerUuid", new UUIDBinaryCodec()),
                            TameworkShoulderRideComponent::setOwnerUuid,
                            TameworkShoulderRideComponent::getOwnerUuid)
                    .add()
                    .append(new KeyedCodec<>("WasInteractable", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasInteractable,
                            TameworkShoulderRideComponent::wasInteractable)
                    .add()
                    .append(new KeyedCodec<>("WasIntangible", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasIntangible,
                            TameworkShoulderRideComponent::wasIntangible)
                    .add()
                    .append(new KeyedCodec<>("WasInvulnerable", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasInvulnerable,
                            TameworkShoulderRideComponent::wasInvulnerable)
                    .add()
                    .append(new KeyedCodec<>("WasFrozen", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasFrozen,
                            TameworkShoulderRideComponent::wasFrozen)
                    .add().build();

    private UUID ownerUuid;
    private boolean wasInteractable;
    private boolean wasIntangible;
    private boolean wasInvulnerable;
    private boolean wasFrozen;

    public TameworkShoulderRideComponent() {
    }

    public TameworkShoulderRideComponent(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public TameworkShoulderRideComponent(UUID ownerUuid,
                                         boolean wasInteractable,
                                         boolean wasIntangible,
                                         boolean wasInvulnerable,
                                         boolean wasFrozen) {
        this.ownerUuid = ownerUuid;
        this.wasInteractable = wasInteractable;
        this.wasIntangible = wasIntangible;
        this.wasInvulnerable = wasInvulnerable;
        this.wasFrozen = wasFrozen;
    }

    public static ComponentType<EntityStore, TameworkShoulderRideComponent>
    getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getShoulderRideComponentType();
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean wasInteractable() {
        return wasInteractable;
    }

    public void setWasInteractable(boolean wasInteractable) {
        this.wasInteractable = wasInteractable;
    }

    public boolean wasIntangible() {
        return wasIntangible;
    }

    public void setWasIntangible(boolean wasIntangible) {
        this.wasIntangible = wasIntangible;
    }

    public boolean wasInvulnerable() {
        return wasInvulnerable;
    }

    public void setWasInvulnerable(boolean wasInvulnerable) {
        this.wasInvulnerable = wasInvulnerable;
    }

    public boolean wasFrozen() {
        return wasFrozen;
    }

    public void setWasFrozen(boolean wasFrozen) {
        this.wasFrozen = wasFrozen;
    }

    @Override
    public TameworkShoulderRideComponent clone() {
        return new TameworkShoulderRideComponent(ownerUuid, wasInteractable,
                wasIntangible, wasInvulnerable, wasFrozen);
    }
}
