package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Decision;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.MarkerEvidence;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
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
 * NPC is marked for despawn only for an explicit fail-closed {@link Action#SUPPRESS} decision.</p>
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

    public ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull ManagedCoopStaleEntityPolicy policy,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType) {
        this(policy, npcType, uuidType, projectionType, DecisionSink.noop());
    }

    public ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull ManagedCoopStaleEntityPolicy policy,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nonnull DecisionSink decisionSink) {
        this(
                Objects.requireNonNull(policy, "policy")::decide,
                npcType,
                uuidType,
                projectionType,
                decisionSink
        );
    }

    ManagedCoopStaleEntitySuppressionSystem(
            @Nonnull DecisionEvaluator evaluator,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, UUIDComponent> uuidType,
            @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionType,
            @Nonnull DecisionSink decisionSink) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.npcType = npcType;
        this.uuidType = uuidType;
        this.projectionType = projectionType;
        this.decisionSink = Objects.requireNonNull(decisionSink, "decisionSink");
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
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
        applyDecision(npc, observation, evaluator.decide(observation));
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Suppression is decided only when an NPC becomes visible.
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
