package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves direct and projectile player attribution without treating environmental sources as players. */
final class DamageAttackerAttributionResolver {
    @Nullable
    UUID resolve(@Nullable Damage.Source source,
                 @Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        return resolve(source, reference -> resolvePlayerUuid(reference, store));
    }

    @Nullable
    UUID resolve(@Nullable Damage.Source source,
                 @Nonnull Function<Ref<EntityStore>, UUID> playerUuidResolver) {
        if (source instanceof Damage.ProjectileSource projectileSource) {
            UUID sourcePlayerUuid = playerUuidResolver.apply(projectileSource.getRef());
            return sourcePlayerUuid != null
                    ? sourcePlayerUuid
                    : playerUuidResolver.apply(projectileSource.getProjectile());
        }
        if (source instanceof Damage.EntitySource entitySource) {
            return playerUuidResolver.apply(entitySource.getRef());
        }
        return null;
    }

    @Nullable
    private UUID resolvePlayerUuid(@Nullable Ref<EntityStore> sourceRef,
                                   @Nonnull Store<EntityStore> store) {
        if (sourceRef == null || !sourceRef.isValid()) {
            return null;
        }
        Player player = store.getComponent(sourceRef, Player.getComponentType());
        return player != null ? player.getUuid() : null;
    }
}
