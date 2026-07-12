package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirement;
import com.alechilles.alecstamework.items.ManagedCoopCrossWorldAliasRetirementCoordinator.RetirementRequest;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Decision;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.MarkerEvidence;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Suppresses stale managed-coop NPC aliases as soon as they become visible in an entity store.
 *
 * <p>All component reads use the add callback's command buffer. The policy and diagnostic sink
 * receive immutable values only; no live reference or component crosses a deferred boundary. An
 * NPC is marked for despawn only for an explicit, trusted {@link Action#SUPPRESS} decision.</p>
 */
public final class ManagedCoopStaleEntitySuppressionSystem extends RefSystem<EntityStore> {
    private final DecisionEvaluator evaluator;
    @Nullable
    private final ComponentType<EntityStore, NPCEntity> npcType;
    @Nullable
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    @Nullable
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType;
    private final DecisionSink decisionSink;
    private final ManagedCoopCrossWorldAliasRetirement crossWorldRetirement;

    public ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull ManagedCoopStaleEntityPolicy policy,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType) {
        this(
                policy, npcType, uuidType, projectionType,
                DecisionSink.noop(), ManagedCoopCrossWorldAliasRetirement.noop());
    }

    public ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull ManagedCoopStaleEntityPolicy policy,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nonnull DecisionSink decisionSink) {
        this(
                policy, npcType, uuidType, projectionType, decisionSink,
                ManagedCoopCrossWorldAliasRetirement.noop());
    }

    public ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull ManagedCoopStaleEntityPolicy policy,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nonnull DecisionSink decisionSink,
            @Nonnull ManagedCoopCrossWorldAliasRetirement crossWorldRetirement) {
        this(
                Objects.requireNonNull(policy, "policy")::decide,
                npcType,
                uuidType,
                projectionType,
                decisionSink,
                crossWorldRetirement
        );
    }

    ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull DecisionEvaluator evaluator,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nonnull DecisionSink decisionSink) {
        this(
                evaluator, npcType, uuidType, projectionType, decisionSink,
                ManagedCoopCrossWorldAliasRetirement.noop());
    }

    ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull DecisionEvaluator evaluator,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nonnull DecisionSink decisionSink,
            @Nonnull ManagedCoopCrossWorldAliasRetirement crossWorldRetirement) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.npcType = npcType;
        this.uuidType = uuidType;
        this.projectionType = projectionType;
        this.decisionSink = Objects.requireNonNull(decisionSink, "decisionSink");
        this.crossWorldRetirement = Objects.requireNonNull(
                crossWorldRetirement, "crossWorldRetirement");
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        evaluateVisibleNpc(reference, store, commandBuffer);
    }

    /**
     * Re-evaluates already-loaded NPCs after the chunk scanner publishes current authority proof.
     * Must run synchronously on the entity store's owning thread.
     */
    public void reevaluate(@Nonnull Store<EntityStore> store) {
        Objects.requireNonNull(store, "store");
        if (npcType == null || uuidType == null) {
            return;
        }
        store.assertThread();
        store.forEachChunk(
                Query.and(npcType, uuidType),
                (ArchetypeChunk<EntityStore> chunk,
                 CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        Ref<EntityStore> reference = chunk.getReferenceTo(index);
                        if (reference != null && reference.isValid()) {
                            evaluateVisibleNpc(reference, store, commandBuffer);
                        }
                    }
                });
    }

    private void evaluateVisibleNpc(Ref<EntityStore> reference,
                                    Store<EntityStore> store,
                                    CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || uuidType == null) {
            return;
        }
        NPCEntity npc = commandBuffer.getComponent(reference, npcType);
        UUIDComponent uuidComponent = commandBuffer.getComponent(reference, uuidType);
        if (npc == null || uuidComponent == null) {
            return;
        }
        UUID entityUuid = uuidComponent.getUuid();
        if (entityUuid == null) {
            return;
        }
        TameworkProjectionIdentityComponent marker = projectionType != null
                ? commandBuffer.getComponent(reference, projectionType)
                : null;
        Observation observation = new Observation(
                entityUuid,
                markerEvidence(marker)
        );
        final Decision decision;
        try {
            decision = evaluator.decide(observation);
        } catch (RuntimeException exception) {
            return;
        }
        boolean retainedProjectionAllowed = decision != null
                && retainedProjectionAllowed(
                        store, commandBuffer, observation, decision);
        boolean suppressed = decision != null
                && suppressionAuthorized(decision, retainedProjectionAllowed)
                && applyDecision(npc, observation, decision);
        if (decision != null && !suppressed && !npc.isDespawning()
                && decision.action() == Action.SUPPRESS
                && decision.reason()
                == ManagedCoopStaleEntityPolicy.Reason.HISTORICAL_RESIDENT_ALIAS) {
            requestCrossWorld(observation.npcUuid(), decision.requiredLiveProjectionUuid(),
                    decision.profileId(), decision.operationId());
        }
        if (decision != null && decision.action() == Action.ALLOW
                && decision.staleAliasUuid() != null) {
            boolean retired = retireEarlierAlias(store, commandBuffer, decision);
            if (!retired) {
                String activeOperationId = decision.reason()
                        == ManagedCoopStaleEntityPolicy.Reason.ACTIVE_RELEASE_PROJECTION
                        ? decision.operationId() : null;
                requestCrossWorld(
                        decision.staleAliasUuid(), observation.npcUuid(),
                        decision.profileId(), activeOperationId);
            }
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (uuidType == null) {
            return;
        }
        UUIDComponent identity = commandBuffer.getComponent(reference, uuidType);
        if (identity != null && identity.getUuid() != null) {
            crossWorldRetirement.invalidateNpc(identity.getUuid());
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null || uuidType == null) {
            return Query.any();
        }
        return Query.and(npcType, uuidType);
    }

    boolean applyDecision(@Nonnull NPCEntity npc,
                          @Nonnull Observation observation,
                          @Nonnull Decision decision) {
        Objects.requireNonNull(npc, "npc");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(decision, "decision");
        if (decision.action() != Action.SUPPRESS || npc.isDespawning()) {
            return false;
        }
        npc.setToDespawn();
        try {
            decisionSink.onSuppressed(new SuppressionEvent(
                    observation.npcUuid(),
                    decision.reason(),
                    decision.profileId(),
                    decision.operationId()
            ));
        } catch (RuntimeException ignored) {
            // Suppression already won; diagnostics must not destabilize the add callback.
        }
        return true;
    }

    static boolean suppressionAuthorized(@Nonnull Decision decision,
                                         boolean retainedProjectionAllowed) {
        return decision.action() == Action.SUPPRESS
                && (decision.requiredLiveProjectionUuid() == null || retainedProjectionAllowed);
    }

    private boolean retainedProjectionAllowed(Store<EntityStore> store,
                                               CommandBuffer<EntityStore> commandBuffer,
                                               Observation staleObservation,
                                               Decision guardedSuppression) {
        UUID retainedUuid = guardedSuppression.requiredLiveProjectionUuid();
        if (retainedUuid == null) {
            return true;
        }
        Ref<EntityStore> retainedReference = liveRef(store, retainedUuid);
        if (retainedReference == null || uuidType == null) {
            return false;
        }
        NPCEntity retainedNpc = commandBuffer.getComponent(retainedReference, npcType);
        UUIDComponent retainedIdentity = commandBuffer.getComponent(retainedReference, uuidType);
        if (retainedNpc == null || retainedNpc.isDespawning() || retainedIdentity == null
                || !retainedUuid.equals(retainedIdentity.getUuid())) {
            return false;
        }
        TameworkProjectionIdentityComponent marker = projectionType != null
                ? commandBuffer.getComponent(retainedReference, projectionType)
                : null;
        Observation retainedObservation = Observation.of(retainedUuid, markerEvidence(marker));
        final Decision retainedDecision;
        try {
            retainedDecision = evaluator.decide(retainedObservation);
        } catch (RuntimeException exception) {
            return false;
        }
        return exactRetainedProjectionProof(
                guardedSuppression, staleObservation, retainedObservation, retainedDecision);
    }

    static boolean exactRetainedProjectionProof(@Nonnull Decision guardedSuppression,
                                                @Nonnull Observation staleObservation,
                                                @Nonnull Observation retainedObservation,
                                                @Nullable Decision retainedDecision) {
        return ManagedCoopStaleEntityPolicy.exactRetainedProjectionProof(
                guardedSuppression, staleObservation, retainedObservation, retainedDecision);
    }

    private boolean retireEarlierAlias(Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer,
                                       Decision retainedDecision) {
        UUID staleUuid = retainedDecision.staleAliasUuid();
        NPCEntity stale = liveNpc(store, commandBuffer, staleUuid);
        if (stale == null) {
            return false;
        }
        if (stale.isDespawning()) {
            return true;
        }
        Decision suppression = new Decision(
                Action.SUPPRESS,
                ManagedCoopStaleEntityPolicy.Reason.HISTORICAL_RESIDENT_ALIAS,
                retainedDecision.profileId(),
                retainedDecision.operationId());
        return applyDecision(stale, Observation.of(staleUuid, null), suppression);
    }

    private void requestCrossWorld(@Nullable UUID staleUuid,
                                   @Nullable UUID retainedUuid,
                                   @Nullable String profileId,
                                   @Nullable String activeOperationId) {
        if (staleUuid == null || retainedUuid == null || profileId == null) {
            return;
        }
        try {
            crossWorldRetirement.request(new RetirementRequest(
                    staleUuid, retainedUuid, profileId, activeOperationId));
        } catch (RuntimeException ignored) {
            // Cross-world cleanup is optional and every rejected request remains non-destructive.
        }
    }

    @Nullable
    private NPCEntity liveNpc(Store<EntityStore> store,
                              CommandBuffer<EntityStore> commandBuffer,
                              @Nullable UUID npcUuid) {
        Ref<EntityStore> reference = liveRef(store, npcUuid);
        return reference != null ? commandBuffer.getComponent(reference, npcType) : null;
    }

    @Nullable
    private Ref<EntityStore> liveRef(Store<EntityStore> store, @Nullable UUID npcUuid) {
        if (npcUuid == null || npcType == null || store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        Ref<EntityStore> reference = world != null ? world.getEntityRef(npcUuid) : null;
        return reference != null && reference.isValid() ? reference : null;
    }

    @Nullable
    private MarkerEvidence markerEvidence(@Nullable TameworkProjectionIdentityComponent marker) {
        return marker == null ? null : new MarkerEvidence(
                marker.getProfileId(),
                marker.getOperationId(),
                marker.getProjectionKind(),
                marker.getSlotKey(),
                marker.getSourceNpcUuid(),
                marker.getGeneration()
        );
    }

    /** Immutable actionable diagnostic emitted only when this system starts a despawn. */
    public record SuppressionEvent(@Nonnull UUID npcUuid,
                                   @Nonnull ManagedCoopStaleEntityPolicy.Reason reason,
                                   @Nullable String profileId,
                                   @Nullable String operationId) {
        public SuppressionEvent {
            Objects.requireNonNull(npcUuid, "npcUuid");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Injected logging/telemetry boundary; implementations may throttle by event identity. */
    @FunctionalInterface
    public interface DecisionSink {
        void onSuppressed(@Nonnull SuppressionEvent event);

        @Nonnull
        static DecisionSink noop() {
            return ignored -> {
            };
        }
    }

    @FunctionalInterface
    interface DecisionEvaluator {
        @Nonnull
        Decision decide(@Nonnull Observation observation);
    }
}
