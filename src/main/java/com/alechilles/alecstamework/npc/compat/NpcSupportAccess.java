package com.alechilles.alecstamework.npc.compat;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves NPC support objects from their ECS components.
 *
 * <p>NPC callbacks bind their supplied execution support for the callback duration so
 * repeated reads do not repeat ECS lookups.
 */
public final class NpcSupportAccess {
    private static final ThreadLocal<Binding> ACTIVE_EXECUTION_SUPPORT = new ThreadLocal<>();

    private NpcSupportAccess() {
    }

    /** Binds callback support and returns the previous nested value. */
    @Nullable
    public static ExecutionSupport push(@Nullable ExecutionSupport support) {
        Binding previous = ACTIVE_EXECUTION_SUPPORT.get();
        if (support == null) {
            ACTIVE_EXECUTION_SUPPORT.remove();
        } else {
            ACTIVE_EXECUTION_SUPPORT.set(new Binding(support.getRole(), support));
        }
        return previous == null ? null : previous.support();
    }

    /** Restores the value returned by {@link #push(ExecutionSupport)}. */
    public static void restore(@Nullable ExecutionSupport previous) {
        if (previous == null) {
            ACTIVE_EXECUTION_SUPPORT.remove();
            return;
        }
        ACTIVE_EXECUTION_SUPPORT.set(new Binding(previous.getRole(), previous));
    }

    /** Resolves support without a live reference only inside a bound NPC callback. */
    @Nullable
    public static StateSupport state(@Nullable Role role) {
        ExecutionSupport active = matchingActiveSupport(role);
        return active != null ? active.getStateSupport() : null;
    }

    @Nullable
    public static StateSupport state(@Nullable Role role,
                                     @Nullable Ref<EntityStore> ref,
                                     ComponentAccessor<EntityStore> accessor) {
        ExecutionSupport active = matchingActiveSupport(role);
        return active != null ? active.getStateSupport() : getState(ref, accessor);
    }

    @Nullable
    public static MarkedEntitySupport markedEntity(@Nullable Role role,
                                                    @Nullable Ref<EntityStore> ref,
                                                    ComponentAccessor<EntityStore> accessor) {
        ExecutionSupport active = matchingActiveSupport(role);
        return active != null ? active.getMarkedEntitySupport() : getMarkedEntity(ref, accessor);
    }

    @Nullable
    public static WorldSupport world(@Nullable Role role,
                                     @Nullable Ref<EntityStore> ref,
                                     ComponentAccessor<EntityStore> accessor) {
        ExecutionSupport active = matchingActiveSupport(role);
        return active != null ? active.getWorldSupport() : getWorld(ref, accessor);
    }

    @Nullable
    public static EntitySupport entity(@Nullable Role role,
                                       @Nullable Ref<EntityStore> ref,
                                       ComponentAccessor<EntityStore> accessor) {
        ExecutionSupport active = matchingActiveSupport(role);
        return active != null ? active.getEntitySupport() : getEntity(ref, accessor);
    }

    @Nullable
    public static StdScope sensorScope(@Nullable Role role,
                                       @Nullable Ref<EntityStore> ref,
                                       @Nullable ComponentAccessor<EntityStore> accessor) {
        EntitySupport support = entity(role, ref, accessor);
        return support != null ? support.getSensorScope() : null;
    }

    @Nullable
    private static ExecutionSupport matchingActiveSupport(@Nullable Role role) {
        Binding active = ACTIVE_EXECUTION_SUPPORT.get();
        return active != null && active.role() == role ? active.support() : null;
    }

    @Nullable
    static Binding pushBound(@Nullable Role role, @Nullable ExecutionSupport support) {
        Binding previous = ACTIVE_EXECUTION_SUPPORT.get();
        if (support == null) {
            ACTIVE_EXECUTION_SUPPORT.remove();
        } else {
            ACTIVE_EXECUTION_SUPPORT.set(new Binding(role, support));
        }
        return previous;
    }

    static void restoreBound(@Nullable Binding previous) {
        if (previous == null) {
            ACTIVE_EXECUTION_SUPPORT.remove();
            return;
        }
        ACTIVE_EXECUTION_SUPPORT.set(previous);
    }

    @Nullable
    private static StateSupport getState(@Nullable Ref<EntityStore> ref,
                                         ComponentAccessor<EntityStore> accessor) {
        return isUsable(ref, accessor) ? StateSupport.get(ref, accessor) : null;
    }

    @Nullable
    private static MarkedEntitySupport getMarkedEntity(@Nullable Ref<EntityStore> ref,
                                                       ComponentAccessor<EntityStore> accessor) {
        return isUsable(ref, accessor) ? MarkedEntitySupport.get(ref, accessor) : null;
    }

    @Nullable
    private static WorldSupport getWorld(@Nullable Ref<EntityStore> ref,
                                         ComponentAccessor<EntityStore> accessor) {
        return isUsable(ref, accessor) ? WorldSupport.get(ref, accessor) : null;
    }

    @Nullable
    private static EntitySupport getEntity(@Nullable Ref<EntityStore> ref,
                                           ComponentAccessor<EntityStore> accessor) {
        return isUsable(ref, accessor) ? EntitySupport.get(ref, accessor) : null;
    }

    private static boolean isUsable(@Nullable Ref<EntityStore> ref,
                                    @Nullable ComponentAccessor<EntityStore> accessor) {
        return ref != null && ref.isValid() && accessor != null;
    }

    record Binding(@Nullable Role role, @Nonnull ExecutionSupport support) {
    }
}
