package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
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
 * Player-saved position of the companion card, in the same snap point and offset terms as the companion HUD. A
 * player without one sees the card beside the crosshair ({@link CompanionHudPlacement#CARD_DEFAULT}). Read and write
 * it on the player's world thread; engine player saving owns durability.
 */
public final class TameworkCompanionCardPlacementComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkCompanionCardPlacementComponent> CODEC = BuilderCodec
            .builder(TameworkCompanionCardPlacementComponent.class, TameworkCompanionCardPlacementComponent::new)
            .append(new KeyedCodec<>("Point", Codec.STRING), (c, v) -> c.point = v, c -> c.point).add()
            .append(new KeyedCodec<>("X", Codec.INTEGER), (c, v) -> c.x = v == null ? 0 : v, c -> c.x).add()
            .append(new KeyedCodec<>("Y", Codec.INTEGER), (c, v) -> c.y = v == null ? 0 : v, c -> c.y).add()
            .build();
    private static ComponentType<EntityStore, TameworkCompanionCardPlacementComponent> type;
    private String point = CompanionHudPlacement.CARD_DEFAULT.point().name();
    private int x = CompanionHudPlacement.CARD_DEFAULT.x();
    private int y = CompanionHudPlacement.CARD_DEFAULT.y();

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkCompanionCardPlacementComponent.class,
                "TameworkCompanionCardPlacement", CODEC);
    }

    @Nullable
    public static ComponentType<EntityStore, TameworkCompanionCardPlacementComponent> getComponentType() {
        return type;
    }

    /** The player's saved card position, or the default beside the crosshair. */
    @Nonnull
    public static CompanionHudPlacement read(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return CompanionHudPlacement.CARD_DEFAULT;
        }
        TameworkCompanionCardPlacementComponent saved = store.getComponent(playerRef, type);
        CompanionHudPlacement.Point parsed = saved == null ? null : CompanionHudPlacement.Point.parse(saved.point);
        return parsed == null ? CompanionHudPlacement.CARD_DEFAULT : new CompanionHudPlacement(parsed, saved.x, saved.y);
    }

    /**
     * Saves a card position, or drops it when it is the default. Call from a page or interaction callback, never an
     * ECS system iteration.
     */
    public static void write(@Nullable Ref<EntityStore> playerRef,
                             @Nullable Store<EntityStore> store,
                             @Nonnull CompanionHudPlacement placement) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        if (placement.equals(CompanionHudPlacement.CARD_DEFAULT)) {
            store.tryRemoveComponent(playerRef, type);
            return;
        }
        TameworkCompanionCardPlacementComponent next = new TameworkCompanionCardPlacementComponent();
        next.point = placement.point().name();
        next.x = placement.x();
        next.y = placement.y();
        store.putComponent(playerRef, type, next);
    }

    @Override
    @Nonnull
    public TameworkCompanionCardPlacementComponent clone() {
        TameworkCompanionCardPlacementComponent result = new TameworkCompanionCardPlacementComponent();
        result.point = point;
        result.x = x;
        result.y = y;
        return result;
    }
}
