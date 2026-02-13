package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

final class InteractionParamResolver {
    private final StdScope globalScopeSnapshot;
    private final StdScope execScopeSnapshot;
    private final StdScope sensorScopeSnapshot;

    InteractionParamResolver(StdScope globalScopeSnapshot,
                             StdScope execScopeSnapshot,
                             StdScope sensorScopeSnapshot) {
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

    private StdScope[] orderedScopes(StdScope primary) {
        StdScope[] scopes = new StdScope[4];
        int count = 0;
        if (primary != null) {
            scopes[count++] = primary;
        }
        if (globalScopeSnapshot != null && globalScopeSnapshot != primary) {
            scopes[count++] = globalScopeSnapshot;
        }
        if (execScopeSnapshot != null && execScopeSnapshot != primary) {
            scopes[count++] = execScopeSnapshot;
        }
        if (sensorScopeSnapshot != null
                && sensorScopeSnapshot != primary
                && sensorScopeSnapshot != globalScopeSnapshot
                && sensorScopeSnapshot != execScopeSnapshot) {
            scopes[count++] = sensorScopeSnapshot;
        }
        return count == scopes.length ? scopes : Arrays.copyOf(scopes, count);
    }

    private String getStringFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        Supplier<String> supplier;
        try {
            supplier = scope.getStringSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return supplier != null ? supplier.get() : null;
    }

    private String[] getStringArrayFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        Supplier<String[]> arraySupplier;
        try {
            arraySupplier = scope.getStringArraySupplier(paramName);
        } catch (IllegalStateException ignored) {
            arraySupplier = null;
        }
        if (arraySupplier != null) {
            String[] values = arraySupplier.get();
            if (values != null && values.length > 0) {
                return values;
            }
        }
        Supplier<String> stringSupplier;
        try {
            stringSupplier = scope.getStringSupplier(paramName);
        } catch (IllegalStateException ignored) {
            stringSupplier = null;
        }
        if (stringSupplier == null) {
            return null;
        }
        String value = stringSupplier.get();
        if (value == null || value.isBlank()) {
            return null;
        }
        return new String[] { value };
    }

    private Boolean getBooleanFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        BooleanSupplier supplier;
        try {
            supplier = scope.getBooleanSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return supplier != null ? supplier.getAsBoolean() : null;
    }

    private Double getNumberFromScope(StdScope scope, String paramName) {
        if (scope == null) {
            return null;
        }
        DoubleSupplier supplier;
        try {
            supplier = scope.getNumberSupplier(paramName);
        } catch (IllegalStateException ignored) {
            return null;
        }
        return supplier != null ? supplier.getAsDouble() : null;
    }
}
