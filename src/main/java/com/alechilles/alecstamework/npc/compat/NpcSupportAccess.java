package com.alechilles.alecstamework.npc.compat;

import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.PositionCache;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves NPC support objects from Update 6 ECS components or Update 5 Role getters.
 *
 * <p>Update 5 method handles are bound once. Update 6 callbacks bind their supplied
 * execution support for the callback duration so repeated reads do not repeat ECS lookups.
 */
public final class NpcSupportAccess {
    private static final ThreadLocal<Binding> ACTIVE_EXECUTION_SUPPORT = new ThreadLocal<>();
    private static final MethodHandle LEGACY_STATE = legacyGetter("getStateSupport", StateSupport.class);
    private static final MethodHandle LEGACY_MARKED_ENTITY =
            legacyGetter("getMarkedEntitySupport", MarkedEntitySupport.class);
    private static final MethodHandle LEGACY_WORLD = legacyGetter("getWorldSupport", WorldSupport.class);
    private static final MethodHandle LEGACY_ENTITY = legacyGetter("getEntitySupport", EntitySupport.class);
    private static final MethodHandle LEGACY_COMBAT = legacyGetter("getCombatSupport", CombatSupport.class);
    private static final MethodHandle LEGACY_POSITION_CACHE = legacyGetter("getPositionCache", PositionCache.class);
    private static final MethodHandle LEGACY_ROLE_STATS = legacyGetter("getRoleStats", RoleStats.class);

    private NpcSupportAccess() {
    }

    /** Binds Update 6 callback support and returns the previous nested value. */
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

    /**
     * Resolves support without a live reference only inside a bound NPC callback on Update 6.
     */
    @Nullable
    public static StateSupport state(@Nullable Role role) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getStateSupport() : null;
        }
        return invokeLegacy(LEGACY_STATE, role, StateSupport.class);
    }

    @Nullable
    public static StateSupport state(@Nullable Role role,
                                     @Nullable Ref<EntityStore> ref,
                                     ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getStateSupport() : getState(ref, accessor);
        }
        return invokeLegacy(LEGACY_STATE, role, StateSupport.class);
    }

    @Nullable
    public static MarkedEntitySupport markedEntity(@Nullable Role role,
                                                    @Nullable Ref<EntityStore> ref,
                                                    ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getMarkedEntitySupport() : getMarkedEntity(ref, accessor);
        }
        return invokeLegacy(LEGACY_MARKED_ENTITY, role, MarkedEntitySupport.class);
    }

    /**
     * Resolves support without a live reference only inside a bound NPC callback on Update 6.
     */
    @Nullable
    public static WorldSupport world(@Nullable Role role) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getWorldSupport() : null;
        }
        return invokeLegacy(LEGACY_WORLD, role, WorldSupport.class);
    }

    @Nullable
    public static WorldSupport world(@Nullable Role role,
                                     @Nullable Ref<EntityStore> ref,
                                     ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getWorldSupport() : getWorld(ref, accessor);
        }
        return invokeLegacy(LEGACY_WORLD, role, WorldSupport.class);
    }

    /**
     * Resolves support without a live reference only inside a bound NPC callback on Update 6.
     */
    @Nullable
    public static EntitySupport entity(@Nullable Role role) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getEntitySupport() : null;
        }
        return invokeLegacy(LEGACY_ENTITY, role, EntitySupport.class);
    }

    @Nullable
    public static EntitySupport entity(@Nullable Role role,
                                       @Nullable Ref<EntityStore> ref,
                                       ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getEntitySupport() : getEntity(ref, accessor);
        }
        return invokeLegacy(LEGACY_ENTITY, role, EntitySupport.class);
    }

    @Nullable
    public static CombatSupport combat(@Nullable Role role,
                                       @Nullable Ref<EntityStore> ref,
                                       ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getCombatSupport() : getCombat(ref, accessor);
        }
        return invokeLegacy(LEGACY_COMBAT, role, CombatSupport.class);
    }

    @Nullable
    public static PositionCache positionCache(@Nullable Role role,
                                              @Nullable Ref<EntityStore> ref,
                                              ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            ExecutionSupport active = matchingActiveSupport(role);
            return active != null ? active.getPositionCache() : getPositionCache(ref, accessor);
        }
        return invokeLegacy(LEGACY_POSITION_CACHE, role, PositionCache.class);
    }

    @Nullable
    public static RoleStats roleStats(@Nullable Role role,
                                      @Nullable Ref<EntityStore> ref,
                                      ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            PositionCache positionCache = positionCache(role, ref, accessor);
            return positionCache != null ? positionCache.getRoleStats() : null;
        }
        return invokeLegacy(LEGACY_ROLE_STATS, role, RoleStats.class);
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

    @Nullable
    private static CombatSupport getCombat(@Nullable Ref<EntityStore> ref,
                                           ComponentAccessor<EntityStore> accessor) {
        return isUsable(ref, accessor) ? CombatSupport.get(ref, accessor) : null;
    }

    @Nullable
    private static PositionCache getPositionCache(@Nullable Ref<EntityStore> ref,
                                                  ComponentAccessor<EntityStore> accessor) {
        return isUsable(ref, accessor) ? PositionCache.get(ref, accessor) : null;
    }

    private static boolean isUsable(@Nullable Ref<EntityStore> ref,
                                    @Nullable ComponentAccessor<EntityStore> accessor) {
        return ref != null && ref.isValid() && accessor != null;
    }

    @Nullable
    private static MethodHandle legacyGetter(String methodName, Class<?> returnType) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    Role.class,
                    methodName,
                    MethodType.methodType(returnType));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nullable
    private static <T> T invokeLegacy(@Nullable MethodHandle handle,
                                      @Nullable Role role,
                                      Class<T> returnType) {
        if (role == null) {
            return null;
        }
        if (handle == null) {
            throw new IllegalStateException("Missing Update 5 Role support accessor for " + returnType.getSimpleName());
        }
        try {
            return returnType.cast(handle.invoke(role));
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not resolve Update 5 " + returnType.getSimpleName(), throwable);
        }
    }

    record Binding(@Nullable Role role, @Nonnull ExecutionSupport support) {
    }
}
