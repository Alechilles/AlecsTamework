package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
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
                    .add().build();

    private UUID ownerUuid;

    public TameworkShoulderRideComponent() {
    }

    public TameworkShoulderRideComponent(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
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

    @Override
    public TameworkShoulderRideComponent clone() {
        return new TameworkShoulderRideComponent(ownerUuid);
    }
}
