package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the original native mount role's configured parameter scope without rebuilding a live role. */
public final class NativeMountMovementScopeResolver {
    @Nullable
    StdScope[] resolve(@Nullable NPCMountComponent mount) {
        if (mount == null) {
            return null;
        }
        return resolveForRoleIndex(mount.getOriginalRoleIndex());
    }

    @Nullable
    public StdScope[] resolveForRoleIndex(int roleIndex) {
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            return null;
        }
        try {
            Builder<Role> builder = plugin.tryGetCachedValidRole(roleIndex);
            BuilderManager builders = plugin.getBuilderManager();
            Scope scope = resolveScope(builder, builders, new ExecutionContext());
            return scope instanceof StdScope standard ? new StdScope[] {standard} : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private Scope resolveScope(@Nullable Builder<Role> builder,
                               @Nullable BuilderManager builders,
                               ExecutionContext context) {
        if (builder == null) {
            return null;
        }
        if (builder instanceof BuilderRoleVariant variant) {
            return variant.createModifierScope(context);
        }
        Builder<Role> concrete = resolveConcreteRole(builder, builders);
        return concrete == null || concrete.getBuilderParameters() == null
                ? null : concrete.getBuilderParameters().createScope();
    }

    @Nullable
    private Builder<Role> resolveConcreteRole(@Nonnull Builder<Role> builder, @Nullable BuilderManager builders) {
        Builder<Role> current = builder;
        while (current instanceof BuilderRoleVariant variant) {
            if (builders == null) {
                return null;
            }
            current = builders.getCachedBuilder(variant.getReferenceIndex(), Role.class);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
