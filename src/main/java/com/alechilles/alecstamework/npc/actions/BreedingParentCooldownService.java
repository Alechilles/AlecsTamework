package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies both parent cooldowns as one focused successful-pair completion concern. */
final class BreedingParentCooldownService {
    private final BreedingCooldownService cooldownService;

    BreedingParentCooldownService(@Nonnull BreedingCooldownService cooldownService) {
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
    }

    void apply(@Nonnull Ref<EntityStore> parentARef,
               @Nonnull TameworkBreedingComponent parentABreeding,
               @Nonnull NPCEntity parentA,
               @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
               @Nonnull Ref<EntityStore> parentBRef,
               @Nonnull TameworkBreedingComponent parentBBreeding,
               @Nonnull NPCEntity parentB,
               @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
               @Nullable TwBreedingConfig config,
               @Nonnull Store<EntityStore> store,
               @Nullable CommandBuffer<EntityStore> commandBuffer,
               @Nonnull CooldownObserver observer) {
        BreedingCooldownService.Resolution cooldownA = cooldownService.resolve(
                config, resolveRoleId(parentA), parentARef, store
        );
        BreedingCooldownService.Resolution cooldownB = cooldownService.resolve(
                config, resolveRoleId(parentB), parentBRef, store
        );
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        cooldownService.apply(
                parentARef, parentABreeding, parentA, parentB.getUuid(),
                cooldownA, now, store, commandBuffer
        );
        cooldownService.apply(
                parentBRef, parentBBreeding, parentB, parentA.getUuid(),
                cooldownB, now, store, commandBuffer
        );
        observer.applied(parentA, parentAOwner, cooldownA);
        observer.applied(parentB, parentBOwner, cooldownB);
    }

    /** Resolves live parent components inside the world callback before applying replay cooldowns. */
    void applyDeferred(@Nonnull World world,
                       @Nonnull UUID parentAUuid,
                       @Nonnull UUID parentBUuid,
                       @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
                       @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
                       @Nullable String breedingConfigId,
                       @Nonnull Function<String, TwBreedingConfig> configResolver,
                       @Nonnull CooldownObserver observer,
                       @Nonnull Consumer<String> warning) {
        try {
            world.execute(() -> {
                try {
                    Store<EntityStore> store = world.getEntityStore() == null
                            ? null : world.getEntityStore().getStore();
                    Ref<EntityStore> parentARef = world.getEntityRef(parentAUuid);
                    Ref<EntityStore> parentBRef = world.getEntityRef(parentBUuid);
                    if (store == null || parentARef == null || !parentARef.isValid()
                            || parentBRef == null || !parentBRef.isValid()) {
                        return;
                    }
                    NPCEntity parentA = store.getComponent(parentARef, NPCEntity.getComponentType());
                    NPCEntity parentB = store.getComponent(parentBRef, NPCEntity.getComponentType());
                    ComponentType<EntityStore, TameworkBreedingComponent> breedingType =
                            TameworkBreedingComponent.getComponentType();
                    if (breedingType == null) {
                        return;
                    }
                    TameworkBreedingComponent breedingA = store.getComponent(parentARef, breedingType);
                    TameworkBreedingComponent breedingB = store.getComponent(parentBRef, breedingType);
                    if (parentA == null || parentB == null || breedingA == null || breedingB == null) {
                        return;
                    }
                    apply(
                            parentARef, breedingA, parentA, parentAOwner,
                            parentBRef, breedingB, parentB, parentBOwner,
                            configResolver.apply(breedingConfigId), store, null, observer
                    );
                } catch (RuntimeException | LinkageError failure) {
                    warnSafely(warning, "Breeding replay cooldown completion failed in world callback.");
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            warnSafely(warning, "Breeding replay cooldown completion could not reach the world thread.");
        }
    }

    private static void warnSafely(Consumer<String> warning, String message) {
        try {
            warning.accept(message);
        } catch (RuntimeException | LinkageError ignored) {
            // Parent completion must not depend on diagnostics.
        }
    }

    @Nullable
    private static String resolveRoleId(@Nonnull NPCEntity npc) {
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        return roleIndex >= 0 && NPCPlugin.get() != null
                ? NPCPlugin.get().getName(roleIndex)
                : null;
    }

    @FunctionalInterface
    interface CooldownObserver {
        void applied(@Nonnull NPCEntity parent,
                     @Nonnull BreedingOffspringProgressionService.OwnerSnapshot owner,
                     @Nonnull BreedingCooldownService.Resolution cooldown);
    }
}
