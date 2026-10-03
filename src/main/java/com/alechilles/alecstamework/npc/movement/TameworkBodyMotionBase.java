package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.BodyMotionBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.movement.Steering;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adapts the engine ExecutionSupport callbacks to the Role-based methods Tamework body motions implement.
 */
public abstract class TameworkBodyMotionBase extends BodyMotionBase {
    protected TameworkBodyMotionBase(@Nonnull BuilderBodyMotionBase builder) {
        super(builder);
    }

    /** Role-based callback that subclasses override. */
    public void preComputeSteering(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Role role,
                                   @Nullable InfoProvider sensorInfo,
                                   @Nonnull Store<EntityStore> store) {
    }

    @Override
    public final void preComputeSteering(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull ExecutionSupport support,
                                         @Nullable InfoProvider sensorInfo,
                                         @Nonnull Store<EntityStore> store) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            preComputeSteering(ref, support.getRole(), sensorInfo, store);
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }

    /** Role-based callback that subclasses override. */
    public void activate(@Nonnull Ref<EntityStore> ref,
                         @Nonnull Role role,
                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
    }

    @Override
    public final void activate(@Nonnull Ref<EntityStore> ref,
                               @Nonnull ExecutionSupport support,
                               @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            activate(ref, support.getRole(), componentAccessor);
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }

    /** Role-based callback that subclasses override. */
    public void deactivate(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
    }

    @Override
    public final void deactivate(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull ExecutionSupport support,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            deactivate(ref, support.getRole(), componentAccessor);
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }

    /** Role-based steering callback that subclasses implement. */
    public abstract boolean computeSteering(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull Role role,
                                            @Nullable InfoProvider sensorInfo,
                                            double dt,
                                            @Nonnull Steering desiredSteering,
                                            @Nonnull ComponentAccessor<EntityStore> componentAccessor);

    @Override
    public final boolean computeSteering(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull ExecutionSupport support,
                                         @Nullable InfoProvider sensorInfo,
                                         double dt,
                                         @Nonnull Steering desiredSteering,
                                         @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            return computeSteering(ref, support.getRole(), sensorInfo, dt, desiredSteering, componentAccessor);
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }
}
