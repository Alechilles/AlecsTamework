package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Caches an NPC's visible nameplate text while mounted so it can be restored when dismounted.
 */
public final class TameworkMountedNameplateComponent implements Component<EntityStore> {
    public static final String CACHED_DISPLAY_NAME_TAG = "CachedDisplayName";

    public static final BuilderCodec<TameworkMountedNameplateComponent> CODEC = BuilderCodec.builder(
            TameworkMountedNameplateComponent.class,
            TameworkMountedNameplateComponent::new
    ).append(
            new KeyedCodec<>(CACHED_DISPLAY_NAME_TAG, new StringCodec()),
            TameworkMountedNameplateComponent::setCachedDisplayName,
            TameworkMountedNameplateComponent::getCachedDisplayName
    ).add().build();

    private String cachedDisplayName;

    public TameworkMountedNameplateComponent() {
        this("");
    }

    public TameworkMountedNameplateComponent(String cachedDisplayName) {
        setCachedDisplayName(cachedDisplayName);
    }

    public static ComponentType<EntityStore, TameworkMountedNameplateComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getMountedNameplateComponentType() : null;
    }

    public String getCachedDisplayName() {
        return cachedDisplayName;
    }

    public void setCachedDisplayName(String cachedDisplayName) {
        this.cachedDisplayName = cachedDisplayName != null ? cachedDisplayName : "";
    }

    @Override
    public TameworkMountedNameplateComponent clone() {
        return new TameworkMountedNameplateComponent(cachedDisplayName);
    }
}
