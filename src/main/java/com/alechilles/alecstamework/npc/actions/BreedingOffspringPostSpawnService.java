package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.damage.RecentSpawnProtectionService;
import com.alechilles.alecstamework.items.CommandCompanionSpawnPhysicsResetService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies optional offspring state while guaranteeing live population finalization. */
final class BreedingOffspringPostSpawnService {
    private final BreedingOffspringProgressionService progressionService;
    private final BreedingCooldownService cooldownService;
    private final BreedingPairingEffectsService effectsService;
    private final BreedingLiveChildCompletion completion;

    BreedingOffspringPostSpawnService(
            @Nonnull BreedingOffspringProgressionService progressionService,
            @Nonnull BreedingCooldownService cooldownService,
            @Nonnull BreedingPairingEffectsService effectsService
    ) {
        this.progressionService = progressionService;
        this.cooldownService = cooldownService;
        this.effectsService = effectsService;
        this.completion = new BreedingLiveChildCompletion();
    }

    void finish(
            @Nonnull Request request,
            @Nonnull Supplier<CompletableFuture<CompanionPopulationCommitResult>> commitAction,
            @Nonnull Consumer<String> durabilityCallback,
            @Nonnull Runnable reservationRelease
    ) {
        String[] physicsReset = new String[] { "not-run" };
        BreedingCooldownService.Resolution[] childCooldown =
                new BreedingCooldownService.Resolution[1];
        completion.finish(
                sideEffects(request, physicsReset, childCooldown),
                commitAction,
                durabilityCallback,
                reservationRelease
        );
    }

    @Nonnull
    private List<BreedingLiveChildCompletion.SideEffect> sideEffects(
            Request request,
            String[] physicsReset,
            BreedingCooldownService.Resolution[] childCooldown
    ) {
        return List.of(
                effect("breeding-spawn-physics-reset-failed",
                        () -> physicsReset[0] = resetPhysics(request)),
                effect("breeding-spawn-protection-failed", () -> protectSpawn(request)),
                effect("breeding-progression-failed",
                        () -> applyProgression(request, childCooldown)),
                effect("breeding-effects-failed",
                        () -> effectsService.spawnHearts(request.childRef(), request.store())),
                effect("breeding-cooldown-log-failed",
                        () -> logCooldown(request, childCooldown[0])),
                effect("breeding-success-log-failed",
                        () -> request.successLogger().accept(physicsReset[0]))
        );
    }

    private static BreedingLiveChildCompletion.SideEffect effect(
            String reason,
            Runnable action
    ) {
        return new BreedingLiveChildCompletion.SideEffect(reason, action);
    }

    private static String resetPhysics(Request request) {
        return CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                request.childRef(), request.childNpc(), request.store()
        );
    }

    private static void protectSpawn(Request request) {
        RecentSpawnProtectionService.getInstance().record(
                request.childUuid(),
                "breeding_offspring",
                request.roleId(),
                System.currentTimeMillis()
        );
    }

    private void applyProgression(
            Request request,
            BreedingCooldownService.Resolution[] childCooldown
    ) {
        BreedingCooldownService.Resolution resolved = cooldownService.resolve(
                request.config(), request.roleId(), request.childRef(), request.store()
        );
        childCooldown[0] = resolved;
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
                resolved.durationMs(),
                request.adultRoleId(),
                request.gender(),
                request.lifecycleFamily(),
                request.lifecycleResolution(),
                request.store()
        );
    }

    private static void logCooldown(
            Request request,
            @Nullable BreedingCooldownService.Resolution resolution
    ) {
        if (resolution != null) {
            request.cooldownLogger().accept(resolution);
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
}
