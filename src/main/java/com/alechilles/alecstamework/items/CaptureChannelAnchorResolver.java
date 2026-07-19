package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileAnchor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Resolves the live NPC-body and view-relative held-item anchors used by capture VFX. */
public final class CaptureChannelAnchorResolver {
    private static final double HELD_ITEM_RIGHT_OFFSET = 0.32D;
    private static final double HELD_ITEM_DOWN_OFFSET = 0.42D;
    private static final double HELD_ITEM_FORWARD_OFFSET = 0.28D;

    private CaptureChannelAnchorResolver() {
    }

    @Nullable
    public static Vector3d resolve(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull HomingVisualProjectileAnchor anchor,
                                   @Nonnull ComponentAccessor<EntityStore> store) {
        return switch (anchor) {
            case ROOT -> resolveRoot(ref, store);
            case BODY -> resolveBody(ref, store);
            case HELD_ITEM -> resolveHeldItem(ref, store);
        };
    }

    @Nullable
    public static Vector3d resolveRoot(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull ComponentAccessor<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        return new Vector3d(transform.getPosition());
    }

    @Nullable
    public static Vector3d resolveHeldItem(@Nonnull Ref<EntityStore> ref,
                                           @Nonnull ComponentAccessor<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        var look = TargetUtil.getLook(ref, store);
        return look == null || look.getPosition() == null || look.getRotation() == null
                ? null
                : new Vector3d(look.getPosition()).add(heldItemOffset(
                        look.getRotation().yaw(),
                        look.getRotation().pitch()
                ));
    }

    @Nullable
    public static Vector3d resolveBody(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull ComponentAccessor<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        double eyeHeight = 0.0D;
        ModelComponent model = store.getComponent(ref, ModelComponent.getComponentType());
        if (model != null && model.getModel() != null) {
            eyeHeight = model.getModel().getEyeHeight(ref, store);
        }
        Vector3d position = transform.getPosition();
        return new Vector3d(position.x, position.y + bodyAnchorHeight(eyeHeight), position.z);
    }

    @Nonnull
    static Vector3d heldItemOffset(float yaw, float pitch) {
        Vector3d offset = new Vector3d();
        ProjectileComponent.computeStartOffset(
                true,
                HELD_ITEM_DOWN_OFFSET,
                HELD_ITEM_RIGHT_OFFSET,
                HELD_ITEM_FORWARD_OFFSET,
                yaw,
                pitch,
                offset
        );
        return offset;
    }

    static double bodyAnchorHeight(double eyeHeight) {
        if (!Double.isFinite(eyeHeight) || eyeHeight <= 0.0D) {
            return 0.15D;
        }
        return Math.max(0.15D, Math.min(2.5D, eyeHeight * 0.45D));
    }

    public static boolean isWithinRange(@Nullable Vector3d first,
                                        @Nullable Vector3d second,
                                        double maxDistance) {
        if (first == null || second == null) {
            return false;
        }
        double distance = first.distance(second);
        return Double.isFinite(distance) && (maxDistance <= 0.0D || distance <= maxDistance);
    }
}
