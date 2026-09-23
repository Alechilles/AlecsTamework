package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Player-saved named companion views shared by their ordinary command flutes. */
public final class TameworkCompanionViewsComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkCompanionViewsComponent> CODEC = BuilderCodec
            .builder(TameworkCompanionViewsComponent.class, TameworkCompanionViewsComponent::new)
            .append(new KeyedCodec<>("Views", Codec.STRING),
                    (component, value) -> component.views = value,
                    component -> component.views).add().build();
    private static ComponentType<EntityStore, TameworkCompanionViewsComponent> type;
    private String views = "";

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(
                TameworkCompanionViewsComponent.class, "TameworkCompanionViews", CODEC);
    }

    @Nullable
    public static ComponentType<EntityStore, TameworkCompanionViewsComponent> getComponentType() {
        return type;
    }

    public String views() { return views; }

    public void views(String value) { views = value == null ? "" : value; }

    @Override @Nonnull
    public TameworkCompanionViewsComponent clone() {
        TameworkCompanionViewsComponent copy = new TameworkCompanionViewsComponent();
        copy.views = views;
        return copy;
    }
}
