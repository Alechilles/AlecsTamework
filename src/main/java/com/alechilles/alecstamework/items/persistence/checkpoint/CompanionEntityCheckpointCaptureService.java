package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/** Copies and serializes complete companion holders on the world thread. */
public final class CompanionEntityCheckpointCaptureService {
    private final CompanionEntityCheckpointSink sink;
    private final LongSupplier clock;

    public CompanionEntityCheckpointCaptureService(
            @Nonnull CompanionEntityCheckpointSink sink,
            @Nonnull LongSupplier clock
    ) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Captures one owned tamed NPC without carrying live ECS objects. */
    @Nullable
    public CompanionEntityCheckpointCapture capture(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            CompanionEntityCheckpoint.CaptureBoundary boundary
    ) {
        if (reference == null || !reference.isValid() || store == null
                || boundary == null) {
            return null;
        }
        try {
            store.assertThread();
            CapturedIdentity identity = identity(reference, store);
            Vector3d position = position(reference, store);
            String worldKey = worldKey(store);
            if (identity == null || position == null || worldKey == null) {
                return null;
            }
            Holder<EntityStore> holder =
                    store.copySerializableEntity(reference);
            BsonDocument serialized = EntityStore.REGISTRY.serialize(holder);
            return new CompanionEntityCheckpointCapture(
                    new NpcAlias(identity.npcUuid()),
                    new OwnerId(identity.ownerUuid()),
                    worldKey,
                    position.x,
                    position.y,
                    position.z,
                    boundary,
                    clock.getAsLong(),
                    serialized
            );
        } catch (RuntimeException | LinkageError ignored) {
            // Canonical gameplay continues if a best-effort checkpoint fails.
            return null;
        }
    }

    /** Publishes only the immutable state returned by world-thread capture. */
    @Nonnull
    public CompletionStage<Void> publish(
            @Nullable CompanionEntityCheckpointCapture capture
    ) {
        return capture == null
                ? CompletableFuture.completedFuture(null)
                : sink.publish(capture);
    }

    private static CapturedIdentity identity(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        TameworkTamedComponent tamed = component(
                reference, store, TameworkTamedComponent.getComponentType()
        );
        TameworkOwnerComponent owner = component(
                reference, store, TameworkOwnerComponent.getComponentType()
        );
        NPCEntity npc = component(
                reference, store, NPCEntity.getComponentType()
        );
        UUIDComponent uuid = component(
                reference, store, UUIDComponent.getComponentType()
        );
        UUID componentUuid = uuid == null ? null : uuid.getUuid();
        UUID legacyUuid = npc == null ? null : npc.getUuid();
        if (tamed == null || !tamed.isTamed() || owner == null
                || owner.getOwnerId() == null || componentUuid == null
                || !componentUuid.equals(legacyUuid)) {
            return null;
        }
        return new CapturedIdentity(componentUuid, owner.getOwnerId());
    }

    private static Vector3d position(
            Ref<EntityStore> reference,
            Store<EntityStore> store
    ) {
        TransformComponent transform = component(
                reference, store, TransformComponent.getComponentType()
        );
        return transform == null || transform.getPosition() == null
                ? null : new Vector3d(transform.getPosition());
    }

    private static String worldKey(Store<EntityStore> store) {
        EntityStore external = store.getExternalData();
        World world = external == null ? null : external.getWorld();
        String name = world == null ? null : world.getName();
        return name == null || name.isBlank() ? null : name.trim();
    }

    private static <T extends com.hypixel.hytale.component.Component<
            EntityStore>> T component(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            ComponentType<EntityStore, T> type
    ) {
        return type == null ? null : store.getComponent(reference, type);
    }

    private record CapturedIdentity(UUID npcUuid, UUID ownerUuid) {
    }
}
