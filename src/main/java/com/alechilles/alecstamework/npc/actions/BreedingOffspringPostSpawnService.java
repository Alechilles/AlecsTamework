package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.damage.RecentSpawnProtectionService;
import com.alechilles.alecstamework.items.CommandCompanionSpawnPhysicsResetService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies best-effort offspring initialization after a live spawn succeeds.
 *
 * <p>A spawned NPC is the authoritative birth outcome. Optional initialization
 * failures are isolated so they cannot create a duplicate retry litter.
 */
final class BreedingOffspringPostSpawnService {
    private final BreedingOffspringProgressionService progressionService;
    private final BreedingCooldownService cooldownService;
    private final BreedingPairingEffectsService effectsService;

    BreedingOffspringPostSpawnService(
            @Nonnull BreedingOffspringProgressionService progressionService,
            @Nonnull BreedingCooldownService cooldownService,
            @Nonnull BreedingPairingEffectsService effectsService
    ) {
        this.progressionService = progressionService;
        this.cooldownService = cooldownService;
        this.effectsService = effectsService;
    }

    void finish(@Nonnull Request request) {
        String physics = runWithFallback(
                () -> CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                        request.childRef(), request.childNpc(), request.store()
                ),
                "not-run"
        );
        runQuietly(() -> RecentSpawnProtectionService.getInstance().record(
                request.childUuid(),
                "breeding_offspring",
                request.roleId(),
                System.currentTimeMillis()
        ));
        BreedingCooldownService.Resolution cooldown = applyProgression(request);
        runQuietly(() -> effectsService.spawnHearts(request.childRef(), request.store()));
        if (cooldown != null) {
            runQuietly(() -> request.cooldownLogger().accept(cooldown));
        }
        String finalPhysics = physics;
        runQuietly(() -> request.successLogger().accept(finalPhysics));
    }

    @Nullable
    private BreedingCooldownService.Resolution applyProgression(@Nonnull Request request) {
        try {
            BreedingCooldownService.Resolution cooldown = cooldownService.resolve(
                    request.config(), request.roleId(), request.childRef(), request.store()
            );
            progressionService.applyOffspringState(
                    request.childRef(),
                    request.childNpc(),
                    request.parentARef(),
                    request.parentBRef(),
                    request.roleId(),
                    request.parentAOwner(),
                    request.parentBOwner(),
                    request.parentATamed(),
                    request.parentBTamed(),
                    request.configId(),
                    cooldown.durationMs(),
                    request.adultRoleId(),
                    request.gender(),
                    request.lifecycleFamily(),
                    request.lifecycleResolution(),
                    request.store()
            );
            return cooldown;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void runQuietly(@Nonnull Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The child is already live; optional follow-up cannot revoke the birth.
        }
    }

    @Nonnull
    private static String runWithFallback(
            @Nonnull StringAction action,
            @Nonnull String fallback
    ) {
        try {
            String result = action.run();
            return result == null ? fallback : result;
        } catch (RuntimeException | LinkageError ignored) {
            return fallback;
        }
    }

    record Request(
            @Nonnull Ref<EntityStore> childRef,
            @Nonnull NPCEntity childNpc,
            @Nonnull Ref<EntityStore> parentARef,
            @Nonnull Ref<EntityStore> parentBRef,
            @Nonnull Store<EntityStore> store,
            @Nullable TwBreedingConfig config,
            @Nonnull String roleId,
            @Nonnull UUID childUuid,
            @Nullable String configId,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot parentBOwner,
            boolean parentATamed,
            boolean parentBTamed,
            @Nullable String adultRoleId,
            @Nullable TwBreedingConfig.Gender gender,
            @Nullable TwBreedingConfig.RoleFamily lifecycleFamily,
            @Nonnull CompanionLifeStageService.LifecycleFamilyResolution lifecycleResolution,
            @Nonnull Consumer<BreedingCooldownService.Resolution> cooldownLogger,
            @Nonnull Consumer<String> successLogger
    ) {
    }

    @FunctionalInterface
    private interface StringAction {
        @Nullable
        String run();
    }
}
