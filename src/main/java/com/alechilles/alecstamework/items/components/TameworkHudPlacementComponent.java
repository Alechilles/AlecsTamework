package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.settings.CompanionHudDefaultsStore;
import com.alechilles.alecstamework.ui.CompanionHudPlacement;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Player-saved position of the companion target HUD. It changes only that player's screen; a player without one
 * follows the server default. Read and write it on the player's world thread; engine player saving owns durability.
 */
public final class TameworkHudPlacementComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkHudPlacementComponent> CODEC = BuilderCodec
            .builder(TameworkHudPlacementComponent.class, TameworkHudPlacementComponent::new)
            .append(new KeyedCodec<>("Point", Codec.STRING), (c, v) -> c.point = v, c -> c.point).add()
            .append(new KeyedCodec<>("X", Codec.INTEGER), (c, v) -> c.x = v == null ? 0 : v, c -> c.x).add()
            .append(new KeyedCodec<>("Y", Codec.INTEGER), (c, v) -> c.y = v == null ? 0 : v, c -> c.y).add()
            .build();
    private static ComponentType<EntityStore, TameworkHudPlacementComponent> type;
    private String point = CompanionHudPlacement.DEFAULT.point().name();
    private int x = CompanionHudPlacement.DEFAULT.x();
    private int y = CompanionHudPlacement.DEFAULT.y();

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkHudPlacementComponent.class,
                "TameworkHudPlacement", CODEC);
    }

    @Nullable
    public static ComponentType<EntityStore, TameworkHudPlacementComponent> getComponentType() {
        return type;
    }

    /** The player's saved placement, or the server default when none is saved or the component is not registered. */
    @Nonnull
    public static CompanionHudPlacement read(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        CompanionHudPlacement saved = readSaved(playerRef, store);
        return saved == null ? CompanionHudDefaultsStore.current() : saved;
    }

    /** The player's own saved placement, or {@code null} when they follow the server default. */
    @Nullable
    public static CompanionHudPlacement readSaved(@Nullable Ref<EntityStore> playerRef,
                                                  @Nullable Store<EntityStore> store) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        TameworkHudPlacementComponent saved = store.getComponent(playerRef, type);
        return saved == null ? null : saved.placement();
    }

    /** Drops the player's own placement so they follow the server default again. Same calling rules as write. */
    public static void clear(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        if (type != null && playerRef != null && playerRef.isValid() && store != null) {
            store.tryRemoveComponent(playerRef, type);
        }
    }

    /** Saves a placement on the player. Call from a page or interaction callback, never an ECS system iteration. */
    public static boolean write(@Nullable Ref<EntityStore> playerRef,
                                @Nullable Store<EntityStore> store,
                                @Nonnull CompanionHudPlacement placement) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return false;
        }
        TameworkHudPlacementComponent next = new TameworkHudPlacementComponent();
        next.point = placement.point().name();
        next.x = placement.x();
        next.y = placement.y();
        store.putComponent(playerRef, type, next);
        return true;
    }

    /** The saved placement, or {@code null} when the saved point name is not a known snap point. */
    @Nullable
    public CompanionHudPlacement placement() {
        CompanionHudPlacement.Point parsed = CompanionHudPlacement.Point.parse(point);
        return parsed == null ? null : new CompanionHudPlacement(parsed, x, y);
    }

    @Override
    @Nonnull
    public TameworkHudPlacementComponent clone() {
        TameworkHudPlacementComponent result = new TameworkHudPlacementComponent();
        result.point = point;
        result.x = x;
        result.y = y;
        return result;
    }
}
