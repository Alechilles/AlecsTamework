package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companioncard.CompanionCardFBehaviour;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A player's own choice of what F on a companion does. A player without one follows the server default. Read and
 * write it on the player's world thread; engine player saving owns durability. Use
 * {@link com.alechilles.alecstamework.companioncard.CompanionCardPreferences} rather than this class directly.
 */
public final class TameworkCompanionCardPreferenceComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkCompanionCardPreferenceComponent> CODEC = BuilderCodec
            .builder(TameworkCompanionCardPreferenceComponent.class, TameworkCompanionCardPreferenceComponent::new)
            .append(new KeyedCodec<>("FBehaviour", Codec.STRING), (c, v) -> c.fBehaviour = v, c -> c.fBehaviour).add()
            .build();
    private static ComponentType<EntityStore, TameworkCompanionCardPreferenceComponent> type;
    private String fBehaviour;

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkCompanionCardPreferenceComponent.class,
                "TameworkCompanionCardPreference", CODEC);
    }

    @Nullable
    public static ComponentType<EntityStore, TameworkCompanionCardPreferenceComponent> getComponentType() {
        return type;
    }

    /**
     * The player's saved choice, or {@code null} when they follow the server default, the saved value is unknown,
     * or the component is not registered. Read only.
     */
    @Nullable
    public static CompanionCardFBehaviour readSaved(@Nullable Ref<EntityStore> playerRef,
                                                    @Nullable ComponentAccessor<EntityStore> store) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return null;
        }
        TameworkCompanionCardPreferenceComponent saved = store.getComponent(playerRef, type);
        return saved == null ? null : CompanionCardFBehaviour.fromConfigValueOrNull(saved.fBehaviour);
    }

    /** Drops the player's own choice so they follow the server default again. Same calling rules as write. */
    public static void clear(@Nullable Ref<EntityStore> playerRef, @Nullable Store<EntityStore> store) {
        if (type != null && playerRef != null && playerRef.isValid() && store != null) {
            store.tryRemoveComponent(playerRef, type);
        }
    }

    /** Saves a choice on the player. Call from a page or command callback, never an ECS system iteration. */
    public static boolean write(@Nullable Ref<EntityStore> playerRef,
                                @Nullable Store<EntityStore> store,
                                @Nonnull CompanionCardFBehaviour behaviour) {
        if (type == null || playerRef == null || !playerRef.isValid() || store == null) {
            return false;
        }
        TameworkCompanionCardPreferenceComponent next = new TameworkCompanionCardPreferenceComponent();
        next.fBehaviour = behaviour.toConfigValue();
        store.putComponent(playerRef, type, next);
        return true;
    }

    @Override
    @Nonnull
    public TameworkCompanionCardPreferenceComponent clone() {
        TameworkCompanionCardPreferenceComponent result = new TameworkCompanionCardPreferenceComponent();
        result.fBehaviour = fBehaviour;
        return result;
    }
}
