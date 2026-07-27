package com.alechilles.alecstamework.persistence.bonded;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import javax.annotation.Nullable;

/** Reads the resolved health from Hytale's fully built NPC role asset. */
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
                    plugin.getRoleBuilderInfo(roleIndex),
                    plugin.getBuilderManager(), roleIndex);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static Double resolveLoadedRole(
            @Nullable Builder<Role> builder,
            @Nullable BuilderInfo builderInfo,
            @Nullable BuilderManager builders,
            int roleIndex
    ) {
        if (builder == null || builderInfo == null || builders == null
                || roleIndex < 0) {
            return null;
        }
        try {
            // Variant roles carry their concrete MaxHealth in Modify, so build
            // the resolved role rather than inspecting template parameters.
            BuilderSupport support = new BuilderSupport(
                    builders, null, null, new ExecutionContext(), builder,
                    new RoleStats());
            Role role = NPCPlugin.buildRole(builder, builderInfo, support,
                    roleIndex);
            double maximum = role.getInitialMaxHealth();
            return Double.isFinite(maximum) && maximum > 0.0D
                    ? maximum : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
