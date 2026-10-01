package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.reference.PersistentRefCount;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.joml.Vector3d;

/**
 * Prepares a deserialized companion snapshot for an AddReason.LOAD respawn at a new place
 * (spec 6.5, confirmed by spikes A and B on 2026-09-30). Never use AddReason.SPAWN: it re-runs
 * fresh-spawn logic (random name, reset inventories, spawn effect).
 */
public final class CompanionRespawn {
    /**
     * Component types used by {@link #prepare}. {@code refCount}, {@code velocity},
     * {@code headRotation} and {@code markedEntities} may be null and are then skipped.
     */
    public record Types(
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuid,
            @Nonnull ComponentType<EntityStore, NPCEntity> npc,
            @Nonnull ComponentType<EntityStore, TransformComponent> transform,
            @Nullable ComponentType<EntityStore, PersistentRefCount> refCount,
            @Nullable ComponentType<EntityStore, Velocity> velocity,
            @Nullable ComponentType<EntityStore, HeadRotation> headRotation,
            @Nullable ComponentType<EntityStore, MarkedEntitySupport> markedEntities,
            @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stamp,
            @Nonnull List<ComponentType<EntityStore, ?>> strip
    ) {
        public Types {
            strip = List.copyOf(strip);
        }

        /** Production types; call after all plugins and Tamework components are registered. */
        @Nonnull
        public static Types production(@Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType) {
            List<ComponentType<EntityStore, ?>> strip = new ArrayList<>();
            addIfPresent(strip, com.hypixel.hytale.server.npc.components.SpawnMarkerReference::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.npc.components.SpawnBeaconReference::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.spawning.blockstates.SpawnMarkerBlockReference::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.core.modules.entity.component.WorldGenId::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.flock.FlockMembership::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.flock.PersistentFlockData::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent::getComponentType);
            addIfPresent(strip, com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval::getComponentType);
            // NPCMountComponent is kept: on add without a rider, NPCMountSystems.OnAdd restores the
            // original role, so a body snapshotted mid-ride does not stay in Empty_Role.
            addIfPresent(strip, com.hypixel.hytale.server.core.modules.entity.component.FromPrefab::getComponentType);
            // Tamework components that link this body to another entity or hold a stale signal
            // (Task 4 step 1.2). Their cleanup systems would act on the partner (for example,
            // detach a rider from a different mount) or treat the body as an old projection.
            addIfPresent(strip, TameworkRideMountComponent::getComponentType);
            addIfPresent(strip, TameworkMountedGlideComponent::getComponentType);
            addIfPresent(strip, TameworkHookComponent::getComponentType);
            addIfPresent(strip, TameworkProjectionIdentityComponent::getComponentType);
            // Kept on purpose: TameworkShoulderRide, TameworkMountedNameplate and
            // TameworkAvatarFlightSource restore this body's own state once it is not mounted.
            // The respawn caller must re-point AvatarFlightSource's origin at the destination:
            // its recovery moves the body there when it unparks it.
            return new Types(UUIDComponent.getComponentType(), NPCEntity.getComponentType(),
                    TransformComponent.getComponentType(), PersistentRefCount.getComponentType(),
                    Velocity.getComponentType(), HeadRotation.getComponentType(),
                    MarkedEntitySupport.getComponentType(), stampType, strip);
        }

        /**
         * Plugin-owned types (farming, mounts, flock) are null, or their getter throws, when
         * that plugin is not loaded (seen in spike B for CoopBlock). Skip them.
         */
        private static void addIfPresent(List<ComponentType<EntityStore, ?>> out,
                                         Supplier<? extends ComponentType<EntityStore, ?>> type) {
            try {
                ComponentType<EntityStore, ?> resolved = type.get();
                if (resolved != null) {
                    out.add(resolved);
                }
            } catch (RuntimeException | LinkageError missingPlugin) {
                // Plugin not loaded; nothing of that type can be on the holder.
            }
        }
    }

    private final Types types;

    public CompanionRespawn(@Nonnull Types types) {
        this.types = Objects.requireNonNull(types, "types");
    }

    /**
     * Removes path state from the serialized NPC component. PathManager has no clear method, so
     * it is dropped before deserializing. Returns a new document; the input is not modified.
     *
     * <p>A body snapshotted while ridden through Tamework ride keeps the ride's motion controller
     * in the NPC's persisted {@code ActiveMC}, which {@code RoleActivateSystem} re-activates on
     * add. {@link #prepare} strips {@code TameworkRideMount}, so its cleanup never restores the
     * controller; this puts the controller the ride recorded back first. The ride state itself
     * is not persisted: the role starts in its start state.
     */
    @Nonnull
    public static BsonDocument stripDocument(@Nonnull BsonDocument entity) {
        BsonDocument copy = entity.clone();
        if (copy.isDocument("Components") && copy.getDocument("Components").isDocument("NPC")) {
            BsonDocument components = copy.getDocument("Components");
            BsonDocument npc = components.getDocument("NPC");
            npc.remove("PathManager");
            if (components.isDocument("TameworkRideMount")
                    && components.getDocument("TameworkRideMount").isString("PreviousMotionController")) {
                String previous = components.getDocument("TameworkRideMount")
                        .getString("PreviousMotionController").getValue().trim();
                if (!previous.isEmpty()) {
                    npc.put("ActiveMC", new BsonString(previous));
                }
            }
        }
        return copy;
    }

    /** Prepares {@code holder} in place and returns it. */
    @Nonnull
    public Holder<EntityStore> prepare(@Nonnull Holder<EntityStore> holder, @Nonnull Vector3d position,
                                       @Nonnull Rotation3f rotation, @Nonnull UUID newNpcUuid,
                                       @Nonnull UUID profileId, long generation) {
        // Always PUT a new UUID: removing it lets LegacyUUIDSystem restore the old one.
        holder.putComponent(types.uuid(), new UUIDComponent(newNpcUuid));
        NPCEntity npc = holder.getComponent(types.npc());
        if (npc != null) {
            npc.setLegacyUUID(newNpcUuid);
            npc.setSpawnConfiguration(AssetMapWithIndexes.NOT_FOUND);
            npc.setDespawning(false);
            npc.setPlayingDespawnAnim(false);
            npc.setLeashPoint(new Vector3d(position));
            npc.setLeashHeading(rotation.yaw());
            npc.setLeashPitch(rotation.pitch());
        }
        types.strip().forEach(holder::tryRemoveComponent);
        TransformComponent transform = holder.getComponent(types.transform());
        if (transform != null) {
            transform.setPosition(position);
            transform.setRotation(rotation);
        }
        if (types.velocity() != null) {
            Velocity velocity = holder.getComponent(types.velocity());
            if (velocity != null) {
                velocity.setZero();
                velocity.getInstructions().clear();
            }
        }
        if (types.headRotation() != null) {
            HeadRotation head = holder.getComponent(types.headRotation());
            if (head != null) {
                head.setRotation(rotation);
            }
        }
        if (types.markedEntities() != null && holder.getComponent(types.markedEntities()) != null) {
            holder.putComponent(types.markedEntities(), new MarkedEntitySupport());
        }
        if (types.refCount() != null) {
            holder.ensureAndGetComponent(types.refCount()).increment();
        }
        holder.putComponent(types.stamp(), new TameworkCompanionComponent(profileId, generation));
        return holder;
    }
}
