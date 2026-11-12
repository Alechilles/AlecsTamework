package com.alechilles.alecstamework.npc.params;

import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import javax.annotation.Nullable;

/**
 * Reads the parameter values a cached role was built with, without building a live role.
 *
 * <p>A Variant declares no parameters of its own for its template's keys: its values are the
 * template's parameters with the variant's {@code Modify} applied, so reading
 * {@code getBuilderParameters()} of a variant misses them.</p>
 */
public final class RoleParameterScopes {
    private RoleParameterScopes() {
    }

    /**
     * Returns the role's parameter scope: for a variant, its template chain's parameters with each
     * {@code Modify} applied (computed values read the variant's own parameters first); for any
     * other role, its declared parameters. Null when the builder is missing or an expression fails.
     */
    @Nullable
    public static StdScope resolve(@Nullable Builder<Role> builder) {
        try {
            if (builder instanceof BuilderRoleVariant variant) {
                ExecutionContext context = new ExecutionContext();
                context.setScope(variant.createExecutionScope());
                Scope scope = variant.createModifierScope(context);
                return scope instanceof StdScope standard ? standard : scope != null ? new StdScope(scope) : null;
            }
            BuilderParameters parameters = builder != null ? builder.getBuilderParameters() : null;
            return parameters != null ? parameters.createScope() : null;
        } catch (RuntimeException | LinkageError exception) {
            // Optional lookups must not terminate the owning world's tick.
            return null;
        }
    }
}
