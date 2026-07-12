package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Decision;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.MarkerEvidence;
import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Observation;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Copies live NPC rows into immutable managed-coop capture candidates.
 *
 * <p>The scanner never returns a {@link Ref}, component, NPC, store, or command buffer. Managed
 * aliases are evaluated against the composite stale-entity policy before they can enter capture
 * selection, while unrelated NPCs remain eligible for ordinary first capture.</p>
 */
public final class ManagedCoopRuntimeCandidateScanner {
    public enum ScanStatus {
        COMPLETE,
        COMPONENTS_UNAVAILABLE,
        FAILED
    }

    public record ScanResult(@Nonnull ScanStatus status,
                             @Nonnull List<ManagedCoopCaptureCandidate> candidates,
                             int suppressed,
                             int rejected,
                             @Nullable String detail) {
        public ScanResult {
            Objects.requireNonNull(status, "status");
            candidates = List.copyOf(candidates);
        }
    }

    record RawCandidate(@Nonnull UUID npcUuid,
                        @Nonnull String roleId,
                        double x,
                        double y,
                        double z,
                        @Nullable UUID ownerUuid,
                        @Nullable String displayName,
                        @Nullable String[] toolIds,
                        boolean tamed,
                        boolean vanillaResident,
                        boolean despawning,
                        @Nullable MarkerEvidence marker) {
    }

    private final ManagedCoopStaleEntityPolicy stalePolicy;
    private final CandidateSource source;

    public ManagedCoopRuntimeCandidateScanner(@Nonnull ManagedCoopStaleEntityPolicy stalePolicy) {
        this(stalePolicy, new HytaleCandidateSource());
    }

    ManagedCoopRuntimeCandidateScanner(@Nonnull ManagedCoopStaleEntityPolicy stalePolicy,
                                       @Nonnull CandidateSource source) {
        this.stalePolicy = Objects.requireNonNull(stalePolicy, "stalePolicy");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Must run on the entity store's owning thread. */
    @Nonnull
    public ScanResult scan(@Nonnull Store<EntityStore> store) {
        Objects.requireNonNull(store, "store");
        try {
            store.assertThread();
            SourceRead read = source.read(store);
            if (read == null) {
                return failed("managed_coop_candidate_source_result_missing");
            }
            if (read.status() != ScanStatus.COMPLETE) {
                return new ScanResult(read.status(), List.of(), 0, 0, read.detail());
            }
            return filter(read.candidates());
        } catch (RuntimeException exception) {
            return failed(failureDetail("managed_coop_candidate_scan", exception));
        }
    }

    @Nonnull
    ScanResult filter(@Nonnull List<RawCandidate> rawCandidates) {
        ArrayList<ManagedCoopCaptureCandidate> accepted = new ArrayList<>();
        int suppressed = 0;
        int rejected = 0;
        for (RawCandidate raw : rawCandidates) {
            if (raw == null || raw.vanillaResident() || raw.despawning()) {
                rejected++;
                continue;
            }
            final Decision decision;
            try {
                decision = stalePolicy.decide(new Observation(raw.npcUuid(), raw.marker()));
            } catch (RuntimeException exception) {
                suppressed++;
                continue;
            }
            if (decision == null || !captureEligible(decision.action())) {
                suppressed++;
                continue;
            }
            String profileId = decision.profileId() != null
                    ? decision.profileId()
                    : raw.marker() != null ? raw.marker().profileId() : null;
            try {
                accepted.add(new ManagedCoopCaptureCandidate(
                        raw.npcUuid(), raw.roleId(), raw.x(), raw.y(), raw.z(),
                        raw.ownerUuid(), raw.displayName(), raw.toolIds(), profileId, raw.tamed()));
            } catch (IllegalArgumentException exception) {
                rejected++;
            }
        }
        return new ScanResult(ScanStatus.COMPLETE, accepted, suppressed, rejected, null);
    }

    private boolean captureEligible(Action action) {
        return action == Action.IGNORE || action == Action.ALLOW;
    }

    @Nonnull
    private ScanResult failed(String detail) {
        return new ScanResult(ScanStatus.FAILED, List.of(), 0, 0, detail);
    }

    interface CandidateSource {
        @Nonnull
        SourceRead read(@Nonnull Store<EntityStore> store);
    }

    record SourceRead(@Nonnull ScanStatus status,
                      @Nonnull List<RawCandidate> candidates,
                      @Nullable String detail) {
        SourceRead {
            candidates = List.copyOf(candidates);
        }
    }

    private static final class HytaleCandidateSource implements CandidateSource {
        @Nonnull
        @Override
        public SourceRead read(Store<EntityStore> store) {
            ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
            ComponentType<EntityStore, TransformComponent> transformType =
                    TransformComponent.getComponentType();
            if (npcType == null || transformType == null) {
                return new SourceRead(
                        ScanStatus.COMPONENTS_UNAVAILABLE, List.of(),
                        "managed_coop_candidate_required_component_type_unavailable");
            }
            Types types = new Types(npcType, transformType);
            ArrayList<RawCandidate> candidates = new ArrayList<>();
            store.forEachChunk(
                    Query.and(npcType, transformType),
                    (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) ->
                            collect(chunk, types, candidates));
            return new SourceRead(ScanStatus.COMPLETE, candidates, null);
        }

        private void collect(ArchetypeChunk<EntityStore> chunk,
                             Types types,
                             List<RawCandidate> target) {
            for (int index = 0; index < chunk.size(); index++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                NPCEntity npc = chunk.getComponent(index, types.npc());
                TransformComponent transform = chunk.getComponent(index, types.transform());
                if (ref == null || !ref.isValid() || npc == null || transform == null) {
                    continue;
                }
                UUID uuid = exactUuid(chunk, index, npc, types.uuid());
                Vector3d position = transform.getPosition();
                if (uuid == null || position == null || npc.getRoleName() == null) {
                    continue;
                }
                TameworkCommandLinksComponent links = component(chunk, index, types.links());
                TameworkOwnerComponent owner = component(chunk, index, types.owner());
                TameworkNpcNameComponent name = component(chunk, index, types.name());
                TameworkTamedComponent tamed = component(chunk, index, types.tamed());
                TameworkProjectionIdentityComponent marker = component(chunk, index, types.marker());
                UUID ownerUuid = links != null && links.getOwnerId() != null
                        ? links.getOwnerId() : owner != null ? owner.getOwnerId() : null;
                target.add(new RawCandidate(
                        uuid,
                        npc.getRoleName(),
                        position.x, position.y, position.z,
                        ownerUuid,
                        name != null ? name.getName() : null,
                        links != null ? links.getToolIds() : null,
                        tamed != null && tamed.isTamed(),
                        component(chunk, index, types.vanillaResident()) != null,
                        npc.isDespawning(),
                        markerEvidence(marker)));
            }
        }

        @Nullable
        private UUID exactUuid(ArchetypeChunk<EntityStore> chunk,
                               int index,
                               NPCEntity npc,
                               @Nullable ComponentType<EntityStore, UUIDComponent> uuidType) {
            UUIDComponent component = component(chunk, index, uuidType);
            UUID componentUuid = component != null ? component.getUuid() : null;
            UUID npcUuid = npc.getUuid();
            return npcUuid != null && componentUuid != null && !npcUuid.equals(componentUuid)
                    ? null : npcUuid != null ? npcUuid : componentUuid;
        }

        @Nullable
        private static MarkerEvidence markerEvidence(
                @Nullable TameworkProjectionIdentityComponent marker) {
            return marker == null ? null : new MarkerEvidence(
                    marker.getProfileId(), marker.getOperationId(), marker.getProjectionKind(),
                    marker.getSlotKey(), marker.getSourceNpcUuid(), marker.getGeneration());
        }

        @Nullable
        private static <T extends com.hypixel.hytale.component.Component<EntityStore>> T component(
                ArchetypeChunk<EntityStore> chunk,
                int index,
                @Nullable ComponentType<EntityStore, T> type) {
            return type == null ? null : chunk.getComponent(index, type);
        }

        private record Types(
                ComponentType<EntityStore, NPCEntity> npc,
                ComponentType<EntityStore, TransformComponent> transform,
                @Nullable ComponentType<EntityStore, UUIDComponent> uuid,
                @Nullable ComponentType<EntityStore, TameworkCommandLinksComponent> links,
                @Nullable ComponentType<EntityStore, TameworkOwnerComponent> owner,
                @Nullable ComponentType<EntityStore, TameworkNpcNameComponent> name,
                @Nullable ComponentType<EntityStore, TameworkTamedComponent> tamed,
                @Nullable ComponentType<EntityStore, TameworkProjectionIdentityComponent> marker,
                @Nullable ComponentType<EntityStore, CoopResidentComponent> vanillaResident) {
            private Types(ComponentType<EntityStore, NPCEntity> npc,
                          ComponentType<EntityStore, TransformComponent> transform) {
                this(
                        npc,
                        transform,
                        UUIDComponent.getComponentType(),
                        TameworkCommandLinksComponent.getComponentType(),
                        TameworkOwnerComponent.getComponentType(),
                        TameworkNpcNameComponent.getComponentType(),
                        TameworkTamedComponent.getComponentType(),
                        TameworkProjectionIdentityComponent.getComponentType(),
                        CoopResidentComponent.getComponentType());
            }
        }
    }

    private static String failureDetail(String stage, RuntimeException exception) {
        String message = exception.getMessage();
        return stage + (message == null || message.isBlank()
                ? ":" + exception.getClass().getSimpleName()
                : ":" + message);
    }
}
