package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CaptureRequirementContext;
import com.alechilles.alecstamework.api.CaptureRequirementPhase;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.items.capturepolicy.runtime.CaptureAttemptCoordinator;
import com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptRecord;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns prepare-time capture identities, durable roll resolution, and apply fencing.
 * The spawner handler supplies only gameplay inputs and the terminal apply continuation.
 */
final class SpawnerCaptureAttemptRuntimeCoordinator {
    private static final long ATTEMPT_TTL_MS = 120_000L;

    private final ItemFeatureRegistry registry;
    private final SpawnerPlayerInventoryService inventory;
    private final SpawnerCaptureFinalizerService finalizer;
    private final SpawnerCapturePolicyService capturePolicy;
    private final SpawnerLinkedNpcSyncService linkedNpcSync;
    private final SpawnerRolePolicyService rolePolicy;
    private final SpawnerEffectService effects;
    @Nullable
    private final CaptureAttemptCoordinator attempts;
    private final LongSupplier requirementGeneration;
    private final Consumer<String> debugLog;
    private final ConcurrentHashMap<UUID, CaptureAttemptHandle> channelAttempts =
            new ConcurrentHashMap<>();

    SpawnerCaptureAttemptRuntimeCoordinator(
            ItemFeatureRegistry registry,
            SpawnerPlayerInventoryService inventory,
            SpawnerCaptureFinalizerService finalizer,
            SpawnerCapturePolicyService capturePolicy,
            SpawnerLinkedNpcSyncService linkedNpcSync,
            SpawnerRolePolicyService rolePolicy,
            SpawnerEffectService effects,
            @Nullable CaptureAttemptCoordinator attempts,
            @Nullable LongSupplier requirementGeneration,
            Consumer<String> debugLog) {
        this.registry = registry;
        this.inventory = inventory;
        this.finalizer = finalizer;
        this.capturePolicy = capturePolicy;
        this.linkedNpcSync = linkedNpcSync;
        this.rolePolicy = rolePolicy;
        this.effects = effects;
        this.attempts = attempts;
        this.requirementGeneration = requirementGeneration == null ? () -> 0L : requirementGeneration;
        this.debugLog = debugLog;
    }

    @Nullable
    CaptureAttemptHandle prepare(
            @Nullable Player player,
            @Nullable ItemStack source,
            @Nullable Integer hotbarSlot) {
        Integer exactSlot = resolveSourceHotbarSlot(player, hotbarSlot);
        ItemStack current = exactSource(player, source, exactSlot);
        return current == null ? null : CaptureAttemptHandle.forDispatch(exactSlot, current);
    }

    @Nullable
    CaptureAttemptHandle prepare(
            @Nullable Player player,
            @Nullable ItemStack source,
            @Nullable Integer hotbarSlot,
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey) {
        Integer exactSlot = resolveSourceHotbarSlot(player, hotbarSlot);
        ItemStack current = exactSource(player, source, exactSlot);
        return current == null ? null : CaptureAttemptHandle.forCaller(
                callerNamespace, idempotencyKey, exactSlot, current);
    }

    void rememberChannel(@Nonnull UUID playerUuid, @Nonnull CaptureAttemptHandle attempt) {
        channelAttempts.put(playerUuid, attempt);
    }

    @Nullable
    CaptureAttemptHandle takeChannel(@Nullable UUID playerUuid) {
        return playerUuid == null ? null : channelAttempts.remove(playerUuid);
    }

    void clearChannel(@Nullable UUID playerUuid) {
        if (playerUuid != null) channelAttempts.remove(playerUuid);
    }

    boolean sourceMatches(@Nullable Player player, @Nullable CaptureAttemptHandle attempt) {
        if (player == null || attempt == null) return false;
        ItemStack current = inventory.getHotbarItem(player, attempt.hotbarSlot());
        return current != null && attempt.sourceFingerprint().equals(
                SpawnerSourceFingerprint.of(current));
    }

    boolean durableRuntimeInstalled() {
        return attempts != null;
    }

    boolean prepareAndResolve(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable String captureBurstParticleSystem,
            @Nonnull CaptureAttemptHandle attempt,
            @Nullable String durableDetachContextJson,
            @Nonnull BooleanSupplier successSourceSpendPreflight,
            @Nonnull ResolvedCaptureContinuation continuation) {
        if (attempts == null) return false;
        return finalizer.prepareCapture(
                player, config, targetRef, durableDetachContextJson,
                "spawner-capture-attempt:" + attempt.attemptId(),
                new SpawnerCaptureFinalizerService.CapturePreparationCallbacks() {
                    @Override
                    public void onPrepared(
                            SpawnerCaptureFinalizerService.PreparedCaptureMutation mutation) {
                        if (!scheduleResolution(
                                player, targetRef, itemStack, config,
                                captureBurstParticleSystem, attempt, mutation,
                                successSourceSpendPreflight, continuation)) {
                            mutation.cancel("capture-attempt-preparation-failed");
                        }
                    }

                    @Override
                    public void onDenied(String reason) {
                        debugLog.accept("capture denied reason=" + reason
                                + " player=" + player.getUuid());
                    }
                });
    }

    boolean revalidateBeforeApply(
            @Nonnull UUID attemptId,
            @Nonnull CaptureAttemptRecord resolvedAttempt,
            @Nonnull Player player,
            @Nonnull UUID targetUuid,
            @Nonnull String profileId,
            @Nonnull String roleId,
            @Nonnull World world,
            @Nonnull String sourceItemId,
            double healthFraction,
            long expectedRequirementGeneration,
            @Nonnull SpawnerCaptureFinalizerService.PreparedCaptureMutation mutation) {
        if (attempts == null) return false;
        CaptureRequirementContext context = new CaptureRequirementContext(
                attemptId,
                CaptureRequirementPhase.FINAL_REVALIDATION,
                player.getUuid(),
                targetUuid,
                profileId,
                roleId,
                world.getName(),
                sourceItemId,
                healthFraction,
                CaptureRequirementContext.UNKNOWN_PROFILE_REVISION);
        var decision = attempts.revalidateBeforeApply(
                resolvedAttempt, context, expectedRequirementGeneration);
        if (decision.allowed()) return true;
        mutation.cancel(decision.reason());
        attempts.quarantineApply(attemptId, decision.reason());
        return false;
    }

    void commit(@Nullable UUID attemptId) {
        if (attempts != null && attemptId != null) attempts.commit(attemptId);
    }

    void quarantine(@Nullable UUID attemptId, @Nonnull String reason) {
        if (attempts != null && attemptId != null) attempts.quarantineApply(attemptId, reason);
    }

    private boolean scheduleResolution(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable String captureBurstParticleSystem,
            CaptureAttemptHandle attempt,
            SpawnerCaptureFinalizerService.PreparedCaptureMutation mutation,
            BooleanSupplier successSourceSpendPreflight,
            ResolvedCaptureContinuation continuation) {
        UUID attemptId = attempt.attemptId();
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        NPCEntity npc = store == null ? null
                : store.getComponent(targetRef, NPCEntity.getComponentType());
        UUID targetUuid = linkedNpcSync.resolveEntityUuid(player, targetRef);
        String roleId = npc == null ? null : rolePolicy.resolveRoleIdFromNpc(npc);
        SpawnerCapturePolicyService.CaptureHealth health =
                capturePolicy.resolveCaptureHealth(targetRef, store);
        if (world == null || store == null || targetUuid == null
                || roleId == null || roleId.isBlank() || health == null) {
            return false;
        }
        if (!sourceMatches(player, attempt)) return false;

        long generation = Math.max(0L, requirementGeneration.getAsLong());
        CaptureRequirementContext requirementContext = new CaptureRequirementContext(
                attemptId,
                CaptureRequirementPhase.FINAL_REVALIDATION,
                player.getUuid(),
                targetUuid,
                mutation.profileId(),
                roleId,
                world.getName(),
                itemStack.getItemId(),
                health.currentHealth() / health.maximumHealth(),
                CaptureRequirementContext.UNKNOWN_PROFILE_REVISION);
        CaptureAttemptCoordinator.AttemptRequest request =
                new CaptureAttemptCoordinator.AttemptRequest(
                        attemptId,
                        mutation.populationOperationId(),
                        attempt.callerNamespace(),
                        attempt.idempotencyKey(),
                        player.getUuid(),
                        targetUuid,
                        mutation.profileId(),
                        CaptureRequirementContext.UNKNOWN_PROFILE_REVISION,
                        itemStack.getItemId(),
                        roleId,
                        attempt.sourceContextJson(world.getName()),
                        itemStack.getItemId(),
                        registry.revision(),
                        config.getCaptureMechanics(),
                        health.currentHealth(),
                        health.maximumHealth(),
                        requirementContext,
                        generation,
                        mutation.populationOperationId().toString(),
                        expiresAt(),
                        attempt.sourceFingerprint(),
                        SpawnerSourceFingerprint.afterConsumingOne(itemStack));

        AtomicReference<CaptureAttemptRecord> resolvedAttempt = new AtomicReference<>();
        AtomicReference<CaptureAttemptHandle> resolvedHandle = new AtomicReference<>(attempt);
        attempts.resolve(request).thenCompose(result -> {
            if (result.status() == CaptureAttemptCoordinator.ResultStatus.FAILED_ROLL) {
                if (result.attempt() != null && result.attempt().sourceSpend().state()
                        == CaptureAttemptRecord.SourceSpendState.PENDING) {
                    return consumeAndConfirm(world, player.getUuid(), itemStack, attempt, result.attempt())
                            .thenApply(consumed -> {
                                if (consumed) {
                                    mutation.cancel("capture-probability-failure");
                                    world.execute(() -> effects.playEffects(
                                            world, targetRef,
                                            config.getCaptureMechanics().failureParticleSystem(),
                                            config.getCaptureMechanics().failureSoundEvent()));
                                }
                                return false;
                            });
                }
                mutation.cancel("capture-probability-failure");
                world.execute(() -> effects.playEffects(
                        world, targetRef,
                        config.getCaptureMechanics().failureParticleSystem(),
                        config.getCaptureMechanics().failureSoundEvent()));
                return CompletableFuture.completedFuture(false);
            }
            if (result.status() != CaptureAttemptCoordinator.ResultStatus.SUCCESS) {
                mutation.cancel(result.reason());
                debugLog.accept("capture denied reason=" + result.reason()
                        + " player=" + player.getUuid() + " targetUuid=" + targetUuid);
                return CompletableFuture.completedFuture(false);
            }
            resolvedAttempt.set(result.attempt());
            CaptureAttemptHandle effective = attempt.withAttemptId(result.attemptId());
            resolvedHandle.set(effective);
            if (result.attempt().sourceSpend().state()
                    == CaptureAttemptRecord.SourceSpendState.PENDING) {
                return consumeAndConfirm(
                        world, player.getUuid(), itemStack, effective, result.attempt(),
                        successSourceSpendPreflight)
                        .thenCompose(consumed -> consumed
                                ? attempts.beginApply(effective.attemptId())
                                : CompletableFuture.completedFuture(false));
            }
            return attempts.beginApply(effective.attemptId());
        }).whenComplete((applyReady, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(applyReady)) {
                mutation.cancel("capture-attempt-apply-fence-failed");
                CaptureAttemptRecord successful = resolvedAttempt.get();
                if (successful != null) {
                    attempts.requireSourceRefund(
                            resolvedHandle.get().attemptId(),
                            "capture-attempt-apply-fence-failed");
                }
                return;
            }
            world.execute(() -> {
                try {
                    boolean scheduled = continuation.apply(
                            resolvedHandle.get(), mutation, resolvedAttempt.get(), generation);
                    if (!scheduled) {
                        mutation.cancel("capture-terminal-revalidation-failed");
                        quarantine(resolvedHandle.get().attemptId(),
                                "capture-terminal-revalidation-failed");
                        attempts.requireSourceRefund(
                                resolvedHandle.get().attemptId(),
                                "capture-terminal-revalidation-failed");
                    }
                } catch (RuntimeException | LinkageError failure) {
                    mutation.cancel("capture-terminal-apply-threw");
                    quarantine(resolvedHandle.get().attemptId(), "capture-terminal-apply-threw");
                    attempts.requireSourceRefund(
                            resolvedHandle.get().attemptId(), "capture-terminal-apply-threw");
                    debugLog.accept("capture denied reason=capture-terminal-apply-threw"
                            + " player=" + player.getUuid()
                            + " targetUuid=" + targetUuid
                            + " failure=" + failure.getClass().getSimpleName());
                }
            });
        });
        return true;
    }

    @Nonnull
    private CompletableFuture<Boolean> consumeAndConfirm(
            @Nonnull World world,
            @Nonnull UUID playerUuid,
            @Nonnull ItemStack source,
            @Nonnull CaptureAttemptHandle handle,
            @Nonnull CaptureAttemptRecord resolved) {
        return consumeAndConfirm(world, playerUuid, source, handle, resolved, () -> true);
    }

    @Nonnull
    private CompletableFuture<Boolean> consumeAndConfirm(
            @Nonnull World world,
            @Nonnull UUID playerUuid,
            @Nonnull ItemStack source,
            @Nonnull CaptureAttemptHandle handle,
            @Nonnull CaptureAttemptRecord resolved,
            @Nonnull BooleanSupplier worldPreflight) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        world.execute(() -> {
            final SpawnerSourceItemTransaction receiptTransaction;
            final ItemStack receipt;
            try {
                if (!worldPreflight.getAsBoolean()) {
                    completion.complete(false);
                    return;
                }
                UUID attemptId = UUID.fromString(resolved.identity().attemptId());
                receipt = SpawnerCaptureSourceReceipt.mark(source, attemptId);
                receiptTransaction = new SpawnerSourceItemTransaction(
                        inventory, world, playerUuid, handle.hotbarSlot(), source, null,
                        "Resolved capture attempt");
                if (!receiptTransaction.prepare(receipt)) {
                    completion.complete(false);
                    return;
                }
            } catch (RuntimeException | LinkageError failure) {
                debugLog.accept("capture denied reason=capture-source-spend-threw"
                        + " player=" + playerUuid
                        + " failure=" + failure.getClass().getSimpleName());
                completion.complete(false);
                return;
            }
            UUID attemptId = UUID.fromString(resolved.identity().attemptId());
            attempts.confirmSourceReceipted(attemptId).whenComplete((receipted, receiptFailure) -> {
                if (receiptFailure != null || receipted == null
                        || receipted.sourceSpend().receiptedAtMs() <= 0L) {
                    world.execute(() -> {
                        receiptTransaction.compensate();
                        completion.complete(false);
                    });
                    return;
                }
                world.execute(() -> {
                    ItemStack replacement = SpawnerCaptureSourceReceipt.after(receipt);
                    SpawnerSourceItemTransaction decrement = new SpawnerSourceItemTransaction(
                            inventory, world, playerUuid, handle.hotbarSlot(), receipt, null,
                            "Receipted capture source decrement");
                    if (!decrement.prepare(replacement)) {
                        receiptTransaction.commit();
                        completion.complete(false);
                        return;
                    }
                    attempts.confirmSourceConsumed(attemptId).whenComplete((committed, failure) -> {
                        if (failure == null && committed != null
                                && committed.sourceSpend().state()
                                == CaptureAttemptRecord.SourceSpendState.CONSUMED) {
                            receiptTransaction.commit();
                            decrement.commit();
                            completion.complete(true);
                        } else {
                            // The durable receipt proves whether recovery must finish the decrement
                            // or merely confirm an already-applied after fingerprint.
                            receiptTransaction.commit();
                            decrement.commit();
                            completion.complete(false);
                        }
                    });
                });
            });
        });
        return completion;
    }

    @Nullable
    private ItemStack exactSource(
            @Nullable Player player,
            @Nullable ItemStack source,
            @Nullable Integer exactSlot) {
        if (player == null || source == null || source.isEmpty() || exactSlot == null) return null;
        ItemStack current = inventory.getHotbarItem(player, exactSlot);
        return current != null && java.util.Objects.equals(current, source) ? current : null;
    }

    @Nullable
    private static Integer resolveSourceHotbarSlot(
            @Nullable Player player, @Nullable Integer explicitSlot) {
        if (explicitSlot != null && explicitSlot >= 0) return explicitSlot;
        if (player == null) return null;
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        return activeSlot < 0 ? null : (int) activeSlot;
    }

    private static long expiresAt() {
        try {
            return Math.addExact(System.currentTimeMillis(), ATTEMPT_TTL_MS);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    @FunctionalInterface
    interface ResolvedCaptureContinuation {
        boolean apply(
                @Nonnull CaptureAttemptHandle handle,
                @Nonnull SpawnerCaptureFinalizerService.PreparedCaptureMutation mutation,
                @Nonnull CaptureAttemptRecord attempt,
                long requirementGeneration);
    }
}
