package com.alechilles.alecstamework.persistence.bonded;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import javax.annotation.Nullable;

/** Reads resolved health from the loaded NPC role builder without creating a live role. */
final class HytaleBondedCompanionRoleHealthResolver
        implements BondedCompanionRoleHealthResolver {

    @Override
    @Nullable
    public Double resolveMaximumHealth(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null) {
                return null;
            }
            int roleIndex = plugin.getIndex(roleId.trim());
            if (roleIndex < 0) {
                return null;
            }
            return resolveLoadedRole(plugin.tryGetCachedValidRole(roleIndex),
                    plugin.getBuilderManager());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static Double resolveLoadedRole(
            @Nullable Builder<Role> builder,
            @Nullable BuilderManager builders
    ) {
        if (builder == null || builders == null) {
            return null;
        }
        try {
            ExecutionContext context = new ExecutionContext();
            Scope scope = resolveScope(builder, builders, context);
            Builder<Role> concrete = resolveConcreteRole(builder, builders);
            if (!(concrete instanceof BuilderRole role) || scope == null) {
                return null;
            }
            context.setScope(scope);
            return readMaximumHealth(role, builders, context);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static Double readMaximumHealth(
            BuilderRole role,
            BuilderManager builders,
            ExecutionContext context
    ) {
        BuilderSupport support = new BuilderSupport(
                builders, null, null, context, role, new RoleStats());
        double maximum = role.getMaxHealth(support);
        return Double.isFinite(maximum) && maximum > 0.0D
                ? maximum : null;
    }

    @Nullable
    private static Scope resolveScope(
            Builder<Role> builder,
            BuilderManager builders,
            ExecutionContext context
    ) {
        if (builder instanceof BuilderRoleVariant variant) {
            return variant.createModifierScope(context);
        }
        return builder.getBuilderParameters() == null
                ? null : builder.getBuilderParameters().createScope();
    }

    @Nullable
    private static Builder<Role> resolveConcreteRole(
            Builder<Role> builder,
            BuilderManager builders
    ) {
        Builder<Role> current = builder;
        while (current instanceof BuilderRoleVariant variant) {
            current = builders.getCachedBuilder(
                    variant.getReferenceIndex(), Role.class);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
