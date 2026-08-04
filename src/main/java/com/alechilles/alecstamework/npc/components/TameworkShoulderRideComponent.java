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
                    .<Boolean>append(new KeyedCodec<>("WasInteractable", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasInteractableValue,
                            TameworkShoulderRideComponent::getWasInteractableValue)
                    .add()
                    .<Boolean>append(new KeyedCodec<>("WasIntangible", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasIntangibleValue,
                            TameworkShoulderRideComponent::getWasIntangibleValue)
                    .add()
                    .<Boolean>append(new KeyedCodec<>("WasInvulnerable", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasInvulnerableValue,
                            TameworkShoulderRideComponent::getWasInvulnerableValue)
                    .add()
                    .<Boolean>append(new KeyedCodec<>("WasFrozen", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setWasFrozenValue,
                            TameworkShoulderRideComponent::getWasFrozenValue)
                    .add()
                    .<Boolean>append(new KeyedCodec<>("StateCaptured", Codec.BOOLEAN),
                            TameworkShoulderRideComponent::setStateCapturedValue,
                            TameworkShoulderRideComponent::getStateCapturedValue)
                    .add().build();

    private UUID ownerUuid;
    private Boolean wasInteractable;
    private Boolean wasIntangible;
    private Boolean wasInvulnerable;
    private Boolean wasFrozen;
    private Boolean stateCaptured;

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
        this(ownerUuid, wasInteractable, wasIntangible, wasInvulnerable,
                wasFrozen, true);
    }

    private TameworkShoulderRideComponent(UUID ownerUuid,
                                          Boolean wasInteractable,
                                          Boolean wasIntangible,
                                          Boolean wasInvulnerable,
                                          Boolean wasFrozen,
                                          Boolean stateCaptured) {
        this.ownerUuid = ownerUuid;
        this.wasInteractable = wasInteractable;
        this.wasIntangible = wasIntangible;
        this.wasInvulnerable = wasInvulnerable;
        this.wasFrozen = wasFrozen;
        this.stateCaptured = stateCaptured;
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
        return Boolean.TRUE.equals(wasInteractable);
    }

    private Boolean getWasInteractableValue() {
        return wasInteractable;
    }

    private void setWasInteractableValue(Boolean wasInteractable) {
        this.wasInteractable = wasInteractable;
    }

    public boolean wasIntangible() {
        return Boolean.TRUE.equals(wasIntangible);
    }

    private Boolean getWasIntangibleValue() {
        return wasIntangible;
    }

    private void setWasIntangibleValue(Boolean wasIntangible) {
        this.wasIntangible = wasIntangible;
    }

    public boolean wasInvulnerable() {
        return Boolean.TRUE.equals(wasInvulnerable);
    }

    private Boolean getWasInvulnerableValue() {
        return wasInvulnerable;
    }

    private void setWasInvulnerableValue(Boolean wasInvulnerable) {
        this.wasInvulnerable = wasInvulnerable;
    }

    public boolean wasFrozen() {
        return Boolean.TRUE.equals(wasFrozen);
    }

    private Boolean getWasFrozenValue() {
        return wasFrozen;
    }

    private void setWasFrozenValue(Boolean wasFrozen) {
        this.wasFrozen = wasFrozen;
    }

    public boolean hasCapturedState() {
        return Boolean.TRUE.equals(stateCaptured)
                || wasInteractable != null
                || wasIntangible != null
                || wasInvulnerable != null
                || wasFrozen != null;
    }

    private Boolean getStateCapturedValue() {
        return stateCaptured;
    }

    private void setStateCapturedValue(Boolean stateCaptured) {
        this.stateCaptured = stateCaptured;
    }

    @Override
    public TameworkShoulderRideComponent clone() {
        return new TameworkShoulderRideComponent(ownerUuid, wasInteractable,
                wasIntangible, wasInvulnerable, wasFrozen, stateCaptured);
    }
}
