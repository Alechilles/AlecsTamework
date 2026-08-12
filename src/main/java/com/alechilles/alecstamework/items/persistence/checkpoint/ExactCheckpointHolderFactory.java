package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.FromPrefab;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/** Decodes an exact holder and changes only identity-safe transient state. */
final class ExactCheckpointHolderFactory {
    private final HolderDecoder decoder;
    private final ComponentTypes types;

    ExactCheckpointHolderFactory() {
        this(null);
    }

    ExactCheckpointHolderFactory(
            @Nullable ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirementType
    ) {
        this(
                EntityStore.REGISTRY::deserialize,
                ComponentTypes.runtime(retirementType)
        );
    }

    ExactCheckpointHolderFactory(
            @Nonnull HolderDecoder decoder,
            @Nonnull ComponentTypes types
    ) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.types = Objects.requireNonNull(types, "types");
    }

    /** Returns a destination-ready holder or null when exact state disagrees. */
    @Nullable
    Holder<EntityStore> prepare(
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan
    ) {
        if (plan == null || !types.complete()) {
            return null;
        }
        Holder<EntityStore> holder = decoder.decode(
                plan.checkpoint().holder()
        );
        if (!matches(holder, plan)) {
            return null;
        }
        for (ComponentType<EntityStore, ?> transientType
                : types.transientTypes()) {
            remove(holder, transientType);
        }
        UUIDComponent identity = new UUIDComponent(
                plan.checkpoint().alias().value()
        );
        holder.replaceComponent(types.uuid(), identity);
        NPCEntity npc = holder.getComponent(types.npc());
        npc.setLegacyUUID(plan.checkpoint().alias().value());
        npc.setDespawning(false);
        npc.setPlayingDespawnAnim(false);
        TransformComponent source = holder.getComponent(types.transform());
        holder.replaceComponent(
                types.transform(),
                new TransformComponent(
                        new Vector3d(
                                plan.destination().x(),
                                plan.destination().y(),
                                plan.destination().z()
                        ),
                        new Rotation3f(source.getRotation())
                )
        );
        return holder;
    }

    private boolean matches(
            @Nullable Holder<EntityStore> holder,
            ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan
    ) {
        if (holder == null) {
            return false;
        }
        UUIDComponent identity = holder.getComponent(types.uuid());
        NPCEntity npc = holder.getComponent(types.npc());
        TransformComponent transform = holder.getComponent(types.transform());
        TameworkOwnerComponent owner = holder.getComponent(types.owner());
        TameworkTamedComponent tamed = holder.getComponent(types.tamed());
        String roleId = plan.profile().identity().roleId();
        return identity != null
                && plan.checkpoint().alias().value().equals(
                        identity.getUuid()
                )
                && npc != null
                && plan.checkpoint().alias().value().equals(npc.getUuid())
                && roleId != null
                && roleId.equals(npc.getRoleName())
                && transform != null
                && transform.getPosition() != null
                && transform.getRotation() != null
                && owner != null
                && plan.checkpoint().ownerId().value().equals(
                        owner.getOwnerId()
                )
                && tamed != null
                && tamed.isTamed();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void remove(
            Holder<EntityStore> holder,
            @Nullable ComponentType<EntityStore, ?> type
    ) {
        if (type != null) {
            holder.tryRemoveComponent((ComponentType) type);
        }
    }

    @FunctionalInterface
    interface HolderDecoder {
        @Nullable
        Holder<EntityStore> decode(@Nonnull BsonDocument document);
    }

    record ComponentTypes(
            ComponentType<EntityStore, UUIDComponent> uuid,
            ComponentType<EntityStore, NPCEntity> npc,
            ComponentType<EntityStore, TransformComponent> transform,
            ComponentType<EntityStore, TameworkOwnerComponent> owner,
            ComponentType<EntityStore, TameworkTamedComponent> tamed,
            @Nonnull List<ComponentType<EntityStore, ?>> transientTypes
    ) {
        ComponentTypes {
            ArrayList<ComponentType<EntityStore, ?>> present =
                    new ArrayList<>();
            for (ComponentType<EntityStore, ?> type
                    : Objects.requireNonNull(
                            transientTypes, "transientTypes"
                    )) {
                if (type != null) {
                    present.add(type);
                }
            }
            transientTypes = List.copyOf(present);
        }

        private boolean complete() {
            return uuid != null && npc != null && transform != null
                    && owner != null && tamed != null;
        }

        private static ComponentTypes runtime(
                @Nullable ComponentType<
                        EntityStore,
                        TameworkPersistenceRetirementComponent
                        > retirementType
        ) {
            ArrayList<ComponentType<EntityStore, ?>> transientTypes =
                    new ArrayList<>();
            transientTypes.add(type(FromPrefab::getComponentType));
            transientTypes.add(type(SpawnMarkerReference::getComponentType));
            transientTypes.add(type(SpawnBeaconReference::getComponentType));
            transientTypes.add(type(
                    TameworkProjectionIdentityComponent::getComponentType
            ));
            transientTypes.add(retirementType);
            return new ComponentTypes(
                    type(UUIDComponent::getComponentType),
                    type(NPCEntity::getComponentType),
                    type(TransformComponent::getComponentType),
                    type(TameworkOwnerComponent::getComponentType),
                    type(TameworkTamedComponent::getComponentType),
                    transientTypes
            );
        }

        @Nullable
        private static <T extends Component<EntityStore>>
        ComponentType<EntityStore, T> type(ComponentTypeSupplier<T> supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }
    }

    @FunctionalInterface
    private interface ComponentTypeSupplier<
            T extends Component<EntityStore>> {
        ComponentType<EntityStore, T> get();
    }
}
