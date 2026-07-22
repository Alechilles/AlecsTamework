package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerMutationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies capture-side entity cleanup after a successful spawner capture.
 */
public final class SpawnerCaptureFinalizerService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private final PersistenceCheckpointHook checkpoints;

    public SpawnerCaptureFinalizerService() {
        this(PersistenceCheckpointHook.NO_OP);
    }

    SpawnerCaptureFinalizerService(@Nonnull PersistenceCheckpointHook checkpoints) {
        this.checkpoints = checkpoints;
    }

    public void despawnNpc(Player player, Ref<EntityStore> targetRef, Entity targetEntity) {
        if (player == null) {
            return;
        }
        if (targetEntity instanceof NPCEntity npcEntity) {
            hit(PersistenceCheckpoint.BEFORE_LIVE_ENTITY_REMOVAL);
            npcEntity.setToDespawn();
            hit(PersistenceCheckpoint.AFTER_LIVE_ENTITY_REMOVAL);
            return;
        }
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            hit(PersistenceCheckpoint.BEFORE_LIVE_ENTITY_REMOVAL);
            npc.setToDespawn();
            hit(PersistenceCheckpoint.AFTER_LIVE_ENTITY_REMOVAL);
        }
    }

    private void hit(PersistenceCheckpoint checkpoint) {
        try {
            checkpoints.hit(checkpoint, null);
        } catch (Exception failure) {
            throw new PersistenceCaptureCheckpointException(failure);
        }
    }

    public boolean finalizeCapture(Player player,
                                   ItemFeatureConfig config,
                                   Ref<EntityStore> targetRef,
                                   @Nullable CaptureCallbacks callbacks) {
        return finalizeCapture(player, config, targetRef, null, callbacks);
    }

    public boolean finalizeCapture(Player player,
                                   ItemFeatureConfig config,
                                   Ref<EntityStore> targetRef,
                                   @Nullable String durableContextJson,
                                   @Nullable CaptureCallbacks callbacks) {
        CaptureCallbacks safeCallbacks = callbacks == null ? CaptureCallbacks.NOOP : callbacks;
        PreparedCapture prepared = prepare(player, config, targetRef, safeCallbacks);
        return prepared != null && schedule(prepared, durableContextJson, safeCallbacks);
    }

    /** Prepares population capacity while leaving the NPC and source item untouched. */
    public boolean prepareCapture(Player player,
                                  ItemFeatureConfig config,
                                  Ref<EntityStore> targetRef,
                                  @Nullable String durableContextJson,
                                  @Nonnull String idempotencyKey,
                                  @Nonnull CapturePreparationCallbacks callbacks) {
        CapturePreparationCallbacks safeCallbacks = Objects.requireNonNull(callbacks, "callbacks");
        PreparedCapture prepared = prepare(player, config, targetRef, new CaptureCallbacks() {
            @Override
            public void onDenied(String reason) {
                safeCallbacks.onDenied(reason);
            }
        });
        if (prepared == null) {
            return false;
        }
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        String scopedContext = capturePersistenceContext(durableContextJson);
        return prepared.scheduler().prepareWithDurableContext(
                prepared.targetRef(), prepared.store(), null, null,
                prepared.expectedLiveOwnerId(), prepared.retainedOwnerId(),
                prepared.retainedOwnerName(), targetLifecycle(prepared.config()),
                prepared.operation(), false, idempotencyKey, scopedContext,
                new OwnerMutationScheduler.PreparationCallbacks() {
                    @Override
                    public void onPrepared(OwnerMutationScheduler.PreparedMutation mutation) {
                        safeCallbacks.onPrepared(new PreparedCaptureMutation(prepared, mutation));
                    }

                    @Override
                    public void onDenied(String reason) {
                        safeCallbacks.onDenied(reason);
                    }
                }
        );
    }

    @Nullable
    private PreparedCapture prepare(Player player,
                                    ItemFeatureConfig config,
                                    Ref<EntityStore> targetRef,
                                    CaptureCallbacks callbacks) {
        if (player == null || config == null || targetRef == null || !targetRef.isValid()) {
            callbacks.onDenied("capture-owner-target-unavailable");
            return null;
        }
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (store == null) {
            callbacks.onDenied("capture-owner-store-unavailable");
            return null;
        }
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        UUID npcUuid = npc == null ? null : npc.getUuid();
        if (npc == null || npcUuid == null) {
            callbacks.onDenied("capture-owner-npc-unavailable");
            return null;
        }
        OwnerMutationScheduler scheduler = resolveMutationScheduler();
        if (scheduler == null) {
            callbacks.onDenied("owner-mutation-scheduler-unavailable");
            return null;
        }
        TameworkOwnerComponent currentOwner = readOwner(targetRef, store);
        UUID expectedLiveOwnerId = currentOwner == null ? null : currentOwner.getOwnerId();
        UUID retainedOwnerId;
        String retainedOwnerName;
        OwnerPopulationOperation operation;
        if (config.isCaptureTamesTarget()) {
            retainedOwnerId = player.getUuid();
            retainedOwnerName = OwnerNameUtil.resolve(player);
            operation = OwnerPopulationOperation.NEW_OWNERSHIP;
        } else {
            retainedOwnerId = config.isCaptureClearsOwner() || currentOwner == null
                    ? null
                    : currentOwner.getOwnerId();
            retainedOwnerName = config.isCaptureClearsOwner() || currentOwner == null
                    ? null
                    : currentOwner.getOwnerName();
            operation = config.isCaptureClearsOwner()
                    ? OwnerPopulationOperation.OWNER_CLEAR
                    : OwnerPopulationOperation.LIFECYCLE_CHANGE;
        }
        return new PreparedCapture(
                player, config, targetRef, store, npcUuid, scheduler,
                expectedLiveOwnerId, retainedOwnerId, retainedOwnerName, operation
        );
    }

    private boolean schedule(PreparedCapture prepared,
                             @Nullable String durableContextJson,
                             CaptureCallbacks callbacks) {
        OwnerMutationScheduler scheduler = prepared.scheduler();
        OwnerMutationScheduler.MutationCallbacks mutationCallbacks = callbacks(prepared, callbacks);
        String idempotencyKey = captureIdempotencyKey(prepared);
        String scopedContext = capturePersistenceContext(durableContextJson);
        return scheduler.scheduleWithDurableContext(
                prepared.targetRef(), prepared.store(), null, null,
                prepared.expectedLiveOwnerId(), prepared.retainedOwnerId(),
                prepared.retainedOwnerName(), targetLifecycle(prepared.config()),
                prepared.operation(), false, idempotencyKey, scopedContext,
                mutationCallbacks
        );
    }

    @Nonnull
    private static String captureIdempotencyKey(@Nonnull PreparedCapture prepared) {
        return "spawner-capture:" + prepared.npcUuid()
                + ":clear-owner=" + prepared.config().isCaptureClearsOwner()
                + ":tames-target=" + prepared.config().isCaptureTamesTarget();
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    @Nonnull
    private String capturePersistenceContext(@Nullable String durableContextJson) {
        JsonObject context = new JsonObject();
        if (durableContextJson != null && !durableContextJson.isBlank()) {
            JsonElement parsed = JsonParser.parseString(durableContextJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Capture durability context must be an object.");
            }
            context = parsed.getAsJsonObject().deepCopy();
        }
        if (context.has("persistenceDomain")) {
            throw new IllegalArgumentException("Capture context cannot replace persistenceDomain.");
        }
        context.addProperty("persistenceDomain", "CAPTURE_INTAKE");
        return context.toString();
    }

    @Nonnull
    private OwnerMutationScheduler.MutationCallbacks callbacks(
            PreparedCapture prepared, CaptureCallbacks callbacks) {
        Player player = prepared.player();
        OwnerMutationScheduler.MutationCallbacks mutationCallbacks =
                new OwnerMutationScheduler.MutationCallbacks() {
                    @Override
                    public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                        callbacks.onDenied(reason);
                    }

                    @Override
                    public boolean beforeApply(@Nonnull String profileId) {
                        return callbacks.beforeApply(profileId);
                    }

                    @Override
                    public void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
                        callbacks.onApplyCompensated(profileId, reason);
                    }

                    @Override
                    public void onApplied(@Nonnull OwnerPopulationDecision decision,
                                          @Nonnull String profileId,
                                          @Nonnull OwnerMutationContext context) {
                        NPCEntity liveNpc = context.store().getComponent(
                                context.npcRef(), NPCEntity.getComponentType()
                        );
                        try {
                            callbacks.onApplied(profileId, context);
                        } finally {
                            if (!keepsLiveNpc(prepared.config())
                                    && prepared.config().isCaptureClearsOwner()
                                    && liveNpc != null
                                    && liveNpc.getRole() != null
                                    && liveNpc.getRole().getMarkedEntitySupport() != null) {
                                liveNpc.getRole().getMarkedEntitySupport()
                                        .setMarkedEntity(MASTER_TARGET_SLOT, null);
                            }
                            if (!keepsLiveNpc(prepared.config())) {
                                despawnNpc(player, context.npcRef(), liveNpc);
                            }
                        }
                    }

                    @Override
                    public void onPopulationCommitted(
                            @Nonnull CompanionPopulationCommitResult result) {
                        callbacks.onPopulationCommitted(result);
                    }

                    @Override
                    public void onDurabilityDegraded(@Nonnull String reason) {
                        callbacks.onDurabilityDegraded(reason);
                    }
                };
        return mutationCallbacks;
    }

    private static boolean keepsLiveNpc(@Nonnull ItemFeatureConfig config) {
        return config.getCaptureMechanics().successDisposition()
                == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK;
    }

    @Nonnull
    private static CompanionLifecycleState targetLifecycle(@Nonnull ItemFeatureConfig config) {
        return keepsLiveNpc(config)
                ? CompanionLifecycleState.ACTIVE : CompanionLifecycleState.CAPTURED;
    }

    @Nullable
    private static OwnerMutationScheduler resolveMutationScheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }

    @Nullable
    private static TameworkOwnerComponent readOwner(@Nonnull Ref<EntityStore> targetRef,
                                                    @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        return type == null ? null : store.getComponent(targetRef, type);
    }

    private record PreparedCapture(
            @Nonnull Player player,
            @Nonnull ItemFeatureConfig config,
            @Nonnull Ref<EntityStore> targetRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID npcUuid,
            @Nonnull OwnerMutationScheduler scheduler,
            @Nullable UUID expectedLiveOwnerId,
            @Nullable UUID retainedOwnerId,
            @Nullable String retainedOwnerName,
            @Nonnull OwnerPopulationOperation operation) {
    }

    public interface CaptureCallbacks {
        CaptureCallbacks NOOP = new CaptureCallbacks() {
        };

        default boolean beforeApply(@Nonnull String profileId) {
            return true;
        }

        default void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
        }

        default void onApplied(@Nonnull String profileId) {
        }

        default void onApplied(@Nonnull String profileId, @Nonnull OwnerMutationContext context) {
            onApplied(profileId);
        }

        default void onPopulationCommitted(@Nonnull CompanionPopulationCommitResult result) {
        }

        default void onDenied(@Nonnull String reason) {
        }

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }

    public interface CapturePreparationCallbacks {
        void onPrepared(@Nonnull PreparedCaptureMutation mutation);

        default void onDenied(@Nonnull String reason) {
        }
    }

    public final class PreparedCaptureMutation {
        private final PreparedCapture capture;
        private final OwnerMutationScheduler.PreparedMutation mutation;

        private PreparedCaptureMutation(PreparedCapture capture,
                                        OwnerMutationScheduler.PreparedMutation mutation) {
            this.capture = capture;
            this.mutation = mutation;
        }

        @Nonnull
        public UUID populationOperationId() {
            return mutation.populationOperationId();
        }

        @Nonnull
        public String profileId() {
            return mutation.profileId();
        }

        public boolean apply(@Nullable CaptureCallbacks callbacks) {
            CaptureCallbacks safeCallbacks = callbacks == null ? CaptureCallbacks.NOOP : callbacks;
            return mutation.apply(SpawnerCaptureFinalizerService.this.callbacks(capture, safeCallbacks));
        }

        public boolean cancel(@Nonnull String reason) {
            return mutation.cancel(reason);
        }
    }

    private static final class PersistenceCaptureCheckpointException extends RuntimeException {
        private PersistenceCaptureCheckpointException(Exception cause) {
            super(cause);
        }
    }
}

