package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcBuilderAccess;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import javax.annotation.Nullable;

/** Safely snapshots role-builder parameters for interaction expression resolution. */
final class InteractionRoleParameterScope {
    private InteractionRoleParameterScope() {
    }

    @Nullable
    static StdScope snapshot(@Nullable BuilderSupport support) {
        if (support == null) {
            return null;
        }
        try {
            Scope globalScope = support.getGlobalScope();
            if (globalScope != null) {
                return globalScope instanceof StdScope
                        ? StdScope.copyOf((StdScope) globalScope)
                        : new StdScope(globalScope);
            }
            Builder<?> roleBuilder = NpcBuilderAccess.getRoleRoot(support);
            BuilderParameters parameters = roleBuilder != null ? roleBuilder.getBuilderParameters() : null;
            return parameters != null ? parameters.createScope() : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
