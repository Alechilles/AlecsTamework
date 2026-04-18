package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamOperator;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;

/** Evaluates parameter-based requirements against role scopes. */
final class InteractionParamMatcher {
    private final InteractionParamAccess paramAccess;
    private final StdScopeLookupCache scopeLookupCache = new StdScopeLookupCache();

    InteractionParamMatcher(InteractionParamAccess paramAccess) {
        this.paramAccess = paramAccess;
    }

    // Matches a parameter requirement using the resolved role scope.
    boolean matchesParamRequirement(ParamRequirement requirement, Role role) {
        if (requirement == null || requirement.getName() == null || requirement.getName().isBlank()) {
            return false;
        }
        String[] targets = requirement.getValues();
        if (targets == null || targets.length == 0) {
            return false;
        }
        StdScope[] scopes = paramAccess.resolveRoleScopes(role);
        if (scopes == null || scopes.length == 0) {
            return false;
        }
        ParamOperator operator = requirement.getOperator();
        TwInteractionConfig.MatchType matchType = requirement.getMatch();

        boolean anyMatched = false;
        for (String target : targets) {
            if (target == null) {
                continue;
            }
            boolean matched = false;
            for (StdScope scope : scopes) {
                if (scope == null) {
                    continue;
                }
                matched = evaluateParamTarget(
                        operator,
                        target,
                        scopeLookupCache.getBoolean(scope, requirement.getName()),
                        scopeLookupCache.getNumber(scope, requirement.getName()),
                        scopeLookupCache.getString(scope, requirement.getName())
                );
                if (matched) {
                    break;
                }
            }
            if (matchType == TwInteractionConfig.MatchType.Any) {
                if (matched) {
                    return true;
                }
            } else {
                if (!matched) {
                    return false;
                }
            }
            anyMatched |= matched;
        }
        return anyMatched;
    }

    // Evaluates a single target value against a parameter supplier.
    private boolean evaluateParamTarget(ParamOperator operator,
                                        String target,
                                        Boolean actualBoolean,
                                        Double actualNumber,
                                        String actualString) {
        if (target == null) {
            return false;
        }
        boolean targetIsBoolean = target.equalsIgnoreCase("true") || target.equalsIgnoreCase("false");
        if ((operator == ParamOperator.Equals || operator == ParamOperator.NotEquals) && targetIsBoolean && actualBoolean != null) {
            boolean actual = actualBoolean;
            boolean expected = Boolean.parseBoolean(target);
            return operator == ParamOperator.Equals ? actual == expected : actual != expected;
        }
        Double targetNumber = null;
        try {
            targetNumber = Double.parseDouble(target);
        } catch (NumberFormatException ignored) {
            targetNumber = null;
        }
        if (actualNumber != null && targetNumber != null) {
            double actual = actualNumber;
            int compare = Double.compare(actual, targetNumber);
            return switch (operator) {
                case Equals -> compare == 0;
                case NotEquals -> compare != 0;
                case GreaterThan -> compare > 0;
                case GreaterThanOrEqual -> compare >= 0;
                case LessThan -> compare < 0;
                case LessThanOrEqual -> compare <= 0;
            };
        }
        if ((operator == ParamOperator.Equals || operator == ParamOperator.NotEquals) && actualString != null) {
            boolean matches = actualString.equalsIgnoreCase(target);
            return operator == ParamOperator.Equals ? matches : !matches;
        }
        return false;
    }
}
