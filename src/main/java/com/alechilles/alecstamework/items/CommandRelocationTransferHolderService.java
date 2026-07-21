package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Prepares a detached NPC holder for destination insertion and safe source rollback. */
final class CommandRelocationTransferHolderService {
    private final ComponentType<EntityStore, TransformComponent> transformType;

    CommandRelocationTransferHolderService() {
        this(TransformComponent.getComponentType());
    }

    CommandRelocationTransferHolderService(
            @Nullable ComponentType<EntityStore, TransformComponent> transformType
    ) {
        this.transformType = transformType;
    }

    @Nullable
    SourceTransform prepareForDestination(@Nonnull Holder<EntityStore> holder,
                                          @Nonnull Vector3d destination) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(destination, "destination");
        if (transformType == null) {
            return null;
        }
        TransformComponent source = holder.getComponent(transformType);
        if (source == null || source.getPosition() == null || source.getRotation() == null) {
            return null;
        }
        SourceTransform snapshot = new SourceTransform(
                new Vector3d(source.getPosition()), new Rotation3f(source.getRotation())
        );
        try {
            holder.replaceComponent(
                    transformType,
                    new TransformComponent(destination, new Rotation3f(source.getRotation()))
            );
            return snapshot;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    boolean restoreSource(@Nonnull Holder<EntityStore> holder,
                          @Nullable SourceTransform sourceTransform) {
        if (transformType == null || sourceTransform == null) {
            return false;
        }
        try {
            holder.replaceComponent(
                    transformType,
                    new TransformComponent(sourceTransform.position(), sourceTransform.rotation())
            );
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Immutable source transform retained only while a detached holder is in flight. */
    record SourceTransform(Vector3d position, Rotation3f rotation) {
        SourceTransform {
            position = new Vector3d(Objects.requireNonNull(position, "position"));
            rotation = new Rotation3f(Objects.requireNonNull(rotation, "rotation"));
        }
    }
}
