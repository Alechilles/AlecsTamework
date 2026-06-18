package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.Arrays;

/** Interaction param resolver. */
final class InteractionParamResolver {
    private final StdScope roleParameterScopeSnapshot;
    private final StdScope globalScopeSnapshot;
    private final StdScope execScopeSnapshot;
    private final StdScope sensorScopeSnapshot;
    private final StdScopeLookupCache scopeLookupCache = new StdScopeLookupCache();

    InteractionParamResolver(StdScope globalScopeSnapshot,
                             StdScope execScopeSnapshot,
                             StdScope sensorScopeSnapshot) {
        this(null, globalScopeSnapshot, execScopeSnapshot, sensorScopeSnapshot);
    }

    InteractionParamResolver(StdScope roleParameterScopeSnapshot,
                             StdScope globalScopeSnapshot,
                             StdScope execScopeSnapshot,
                             StdScope sensorScopeSnapshot) {
        this.roleParameterScopeSnapshot = roleParameterScopeSnapshot;
        this.globalScopeSnapshot = globalScopeSnapshot;
        this.execScopeSnapshot = execScopeSnapshot;
        this.sensorScopeSnapshot = sensorScopeSnapshot;
    }

    StdScope resolveRoleScope(Role role) {
        if (role == null) {
            return null;
        }
        EntitySupport support = role.getEntitySupport();
        return support != null ? support.getSensorScope() : null;
    }

    StdScope[] resolveRoleScopes(Role role, InteractionContextSnapshot ctx) {
        if (ctx != null && ctx.roleScopes != null) {
            return ctx.roleScopes;
        }
        return orderedScopes(resolveRoleScope(role));
    }

    String getStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        for (StdScope scope : resolveRoleScopes(role, ctx)) {
            String value = getStringFromScope(scope, paramName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    String[] getStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        for (StdScope scope : resolveRoleScopes(role, ctx)) {
            String[] values = getStringArrayFromScope(scope, paramName);
            if (values != null && values.length > 0) {
                return values;
            }
        }
        return null;
    }

    boolean getBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return false;
        }
        for (StdScope scope : resolveRoleScopes(role, ctx)) {
            Boolean value = getBooleanFromScope(scope, paramName);
            if (value != null) {
                return value;
            }
        }
        return false;
    }

    double getNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        if (paramName == null || paramName.isBlank()) {
            return defaultValue;
        }
        for (StdScope scope : resolveRoleScopes(role, ctx)) {
            Double value = getNumberFromScope(scope, paramName);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    double[] getNumberArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        for (StdScope scope : resolveRoleScopes(role, ctx)) {
            double[] values = getNumberArrayFromScope(scope, paramName);
            if (values != null && values.length > 0) {
                return values;
            }
        }
        return null;
    }

    private StdScope[] orderedScopes(StdScope primary) {
        StdScope[] scopes = new StdScope[5];
        int count = 0;
        if (primary != null) {
            scopes[count++] = primary;
        }
        if (roleParameterScopeSnapshot != null && roleParameterScopeSnapshot != primary) {
            scopes[count++] = roleParameterScopeSnapshot;
        }
        if (globalScopeSnapshot != null
                && globalScopeSnapshot != primary
                && globalScopeSnapshot != roleParameterScopeSnapshot) {
            scopes[count++] = globalScopeSnapshot;
        }
        if (execScopeSnapshot != null
                && execScopeSnapshot != primary
                && execScopeSnapshot != roleParameterScopeSnapshot
                && execScopeSnapshot != globalScopeSnapshot) {
            scopes[count++] = execScopeSnapshot;
        }
        if (sensorScopeSnapshot != null
                && sensorScopeSnapshot != primary
                && sensorScopeSnapshot != roleParameterScopeSnapshot
                && sensorScopeSnapshot != globalScopeSnapshot
                && sensorScopeSnapshot != execScopeSnapshot) {
            scopes[count++] = sensorScopeSnapshot;
        }
        return count == scopes.length ? scopes : Arrays.copyOf(scopes, count);
    }

    private String getStringFromScope(StdScope scope, String paramName) {
        return scopeLookupCache.getString(scope, paramName);
    }

    private String[] getStringArrayFromScope(StdScope scope, String paramName) {
        return scopeLookupCache.getStringArrayOrString(scope, paramName);
    }

    private Boolean getBooleanFromScope(StdScope scope, String paramName) {
        return scopeLookupCache.getBoolean(scope, paramName);
    }

    private Double getNumberFromScope(StdScope scope, String paramName) {
        return scopeLookupCache.getNumber(scope, paramName);
    }

    private double[] getNumberArrayFromScope(StdScope scope, String paramName) {
        return scopeLookupCache.getNumberArray(scope, paramName);
    }
}
