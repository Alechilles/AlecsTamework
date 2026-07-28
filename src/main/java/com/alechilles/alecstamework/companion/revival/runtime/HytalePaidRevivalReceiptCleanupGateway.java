package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalReceiptPlan.ReleasePlan;
import com.alechilles.alecstamework.companion.revival.runtime.HytalePaidRevivalReceiptPlan.ReleaseStatus;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.HytaleAsyncWorldOperationGateway;
import com.alechilles.alecstamework.persistence.runtime.player.HytalePlayerDurabilityBarrier;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Removes phase-authorized paid-revival receipts and proves durable actor cleanup.
 */
final class HytalePaidRevivalReceiptCleanupGateway
        implements HytaleAsyncWorldOperationGateway<PaidRevivalRequest> {
    private final ComponentType<
            EntityStore, TameworkInventoryOperationReceiptsComponent>
            receiptType;
    private final CleanupMode mode;

    HytalePaidRevivalReceiptCleanupGateway(
            ComponentType<
                    EntityStore,
                    TameworkInventoryOperationReceiptsComponent> receiptType,
            CleanupMode mode
    ) {
        this.receiptType = Objects.requireNonNull(
                receiptType, "receiptType"
        );
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    @Nonnull
    public CompletionStage<LiveOperationResult> applyOrResolveAsync(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull PaidRevivalRequest request,
            @Nonnull OperationEnvelope operation
    ) {
        if (!valid(world, store, request, operation)) {
            return completed(unknown("request_invalid", null));
        }
        try {
            store.assertThread();
            Actor actor = resolveActor(world, store, request);
            if (actor == null) {
                return completed(retry("actor_unavailable", null));
            }
            HytalePaidRevivalReceiptPlan receipts =
                    new HytalePaidRevivalReceiptPlan(request, operation);
            ReleasePlan release = release(receipts, actor.receipts());
            if (cleanupAction(release) == CleanupAction.CONFLICT) {
                return completed(unknown(
                        "receipt_evidence_conflict", null
                ));
            }
            if (release.status() == ReleaseStatus.MUTATED) {
                store.putComponent(
                        actor.reference(), receiptType, release.receipts()
                );
                ReleasePlan readback = release(
                        receipts,
                        store.getComponent(actor.reference(), receiptType)
                );
                if (readback.status() != ReleaseStatus.ABSENT) {
                    return completed(unknown(
                            "receipt_removal_readback_conflict", null
                    ));
                }
            }
            HytalePlayerDurabilityBarrier durability =
                    new HytalePlayerDurabilityBarrier(
                            world,
                            store,
                            request.targetWorldKey(),
                            request.familyKey().ownerId().value()
                    );
            return persistAndVerify(
                    world,
                    store,
                    request,
                    operation,
                    receipts,
                    durability
            );
        } catch (RuntimeException | LinkageError failure) {
            return completed(unknown(
                    "receipt_cleanup_failed", failure
            ));
        }
    }

    private CompletionStage<LiveOperationResult> persistAndVerify(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            HytalePaidRevivalReceiptPlan receipts,
            HytalePlayerDurabilityBarrier durability
    ) {
        CompletionStage<HytalePlayerDurabilityBarrier.SaveResult> save =
                durability.saveActor();
        if (save == null) {
            return completed(retry("actor_save_missing", null));
        }
        CompletableFuture<LiveOperationResult> completion =
                new CompletableFuture<>();
        save.whenComplete((result, failure) -> {
            if (failure != null || result == null || !result.saved()) {
                completion.complete(retry(
                        "actor_save_failed",
                        failure != null
                                ? failure
                                : result == null
                                ? null
                                : result.failure()
                ));
                return;
            }
            resumeVerification(
                    world,
                    store,
                    request,
                    operation,
                    receipts,
                    durability,
                    completion
            );
        });
        return completion;
    }

    private void resumeVerification(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            HytalePaidRevivalReceiptPlan receipts,
            HytalePlayerDurabilityBarrier durability,
            CompletableFuture<LiveOperationResult> completion
    ) {
        CompletionStage<LiveOperationResult> resumed =
                durability.resumeOnWorldThread(
                        () -> completed(verifyAbsent(
                                world,
                                store,
                                request,
                                operation,
                                receipts
                        )),
                        () -> retry("world_instance_changed", null)
                );
        if (resumed == null) {
            completion.complete(retry("world_resume_missing", null));
            return;
        }
        resumed.whenComplete((verified, failure) ->
                completion.complete(failure != null
                        ? retry("world_resume_failed", failure)
                        : verified == null
                        ? retry("world_resume_missing", null)
                        : verified));
    }

    private LiveOperationResult verifyAbsent(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation,
            HytalePaidRevivalReceiptPlan receipts
    ) {
        if (!valid(world, store, request, operation)) {
            return unknown("request_changed", null);
        }
        try {
            store.assertThread();
            Actor actor = resolveActor(world, store, request);
            if (actor == null) {
                return retry("actor_unavailable", null);
            }
            ReleasePlan proof = release(receipts, actor.receipts());
            return proof.status() == ReleaseStatus.ABSENT
                    ? confirmed()
                    : proof.status() == ReleaseStatus.CONFLICT
                    ? unknown("receipt_evidence_conflict", null)
                    : unknown("receipt_reappeared", null);
        } catch (RuntimeException | LinkageError failure) {
            return retry("receipt_readback_failed", failure);
        }
    }

    @Nullable
    private Actor resolveActor(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request
    ) {
        Ref<EntityStore> reference = world.getEntityRef(
                request.familyKey().ownerId().value()
        );
        ComponentType<EntityStore, Player> playerType =
                Player.getComponentType();
        if (reference == null || !reference.isValid()
                || reference.getStore() != store
                || playerType == null
                || store.getComponent(reference, playerType) == null) {
            return null;
        }
        return new Actor(
                reference,
                store.getComponent(reference, receiptType)
        );
    }

    private boolean valid(
            World world,
            Store<EntityStore> store,
            PaidRevivalRequest request,
            OperationEnvelope operation
    ) {
        return world != null
                && store != null
                && request != null
                && operation != null
                && PaidRevivalDefinition.KIND.equals(operation.kind())
                && mode.allows(operation.phase())
                && PaidRevivalDefinition.INSTANCE.payloadVersion()
                == operation.payloadVersion()
                && request.groupAdmission().before().revision().equals(
                operation.expectedLifecycleRevision()
        )
                && operation.participants().contains(
                OperationScope.profile(
                        request.sourceSnapshot().profileId()
                )
        )
                && operation.participants().contains(
                OperationScope.owner(request.familyKey().ownerId())
        )
                && operation.participants().contains(
                OperationScope.commandFamily(request.familyKey())
        );
    }

    private LiveOperationResult confirmed() {
        return LiveOperationResult.confirmed(
                mode.code() + "_receipts_absent"
        );
    }

    private LiveOperationResult retry(
            String suffix,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.retryable(
                mode.code() + "_" + suffix, cause
        );
    }

    private LiveOperationResult unknown(
            String suffix,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.unknown(
                mode.code() + "_" + suffix, cause
        );
    }

    private ReleasePlan release(
            HytalePaidRevivalReceiptPlan receipts,
            @Nullable TameworkInventoryOperationReceiptsComponent current
    ) {
        return mode == CleanupMode.NO_CHARGE
                ? receipts.releaseNoCharge(current)
                : receipts.releaseCanonical(current);
    }

    static CleanupAction cleanupAction(ReleasePlan release) {
        Objects.requireNonNull(release, "release");
        return release.status() == ReleaseStatus.CONFLICT
                ? CleanupAction.CONFLICT
                : CleanupAction.PERSIST_AND_VERIFY;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private record Actor(
            Ref<EntityStore> reference,
            @Nullable TameworkInventoryOperationReceiptsComponent receipts
    ) {
    }

    enum CleanupMode {
        NO_CHARGE("paid_revival_release"),
        POST_CANONICAL("paid_revival_cleanup");

        private final String code;

        CleanupMode(String code) {
            this.code = code;
        }

        boolean allows(OperationPhase phase) {
            return this == NO_CHARGE
                    ? phase == OperationPhase.COMPENSATING
                    : phase == OperationPhase.DURABLE
                    || phase == OperationPhase.COMPENSATED;
        }

        String code() {
            return code;
        }
    }

    enum CleanupAction {
        PERSIST_AND_VERIFY,
        CONFLICT
    }
}
