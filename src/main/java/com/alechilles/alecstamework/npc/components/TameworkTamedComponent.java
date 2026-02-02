package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.simple.BooleanCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class TameworkTamedComponent implements Component<EntityStore> {
    public static final String TAMED_TAG = "Tamed";

    public static final BuilderCodec<TameworkTamedComponent> CODEC = BuilderCodec.builder(
            TameworkTamedComponent.class,
            TameworkTamedComponent::new
    ).append(
            new KeyedCodec<>(TAMED_TAG, new BooleanCodec()),
            TameworkTamedComponent::setTamed,
            TameworkTamedComponent::isTamed
    ).add().build();

    private boolean tamed;

    public TameworkTamedComponent() {
        this(false);
    }

    public TameworkTamedComponent(boolean tamed) {
        this.tamed = tamed;
    }

    public static ComponentType<EntityStore, TameworkTamedComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getTamedComponentType() : null;
    }

    public boolean isTamed() {
        return tamed;
    }

    public void setTamed(boolean tamed) {
        this.tamed = tamed;
    }

    @Override
    public TameworkTamedComponent clone() {
        return new TameworkTamedComponent(tamed);
    }
}
