package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamOperator;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Evaluates parameter-based requirements against role scopes. */
final class InteractionParamMatcher {
    private final InteractionParamAccess paramAccess;

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
        StdScope scope = paramAccess.resolveRoleScope(role);
        if (scope == null) {
            return false;
        }
        ParamOperator operator = requirement.getOperator();
        TwInteractionConfig.MatchType matchType = requirement.getMatch();

        BooleanSupplier booleanSupplier;
        try {
            booleanSupplier = scope.getBooleanSupplier(requirement.getName());
        } catch (IllegalStateException ignored) {
            booleanSupplier = null;
        }

        DoubleSupplier numberSupplier;
        try {
            numberSupplier = scope.getNumberSupplier(requirement.getName());
        } catch (IllegalStateException ignored) {
            numberSupplier = null;
        }

        Supplier<String> stringSupplier;
        try {
            stringSupplier = scope.getStringSupplier(requirement.getName());
        } catch (IllegalStateException ignored) {
            stringSupplier = null;
        }

        boolean anyMatched = false;
        for (String target : targets) {
            if (target == null) {
                continue;
            }
            boolean matched = evaluateParamTarget(operator, target, booleanSupplier, numberSupplier, stringSupplier);
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
                                        BooleanSupplier booleanSupplier,
                                        DoubleSupplier numberSupplier,
                                        Supplier<String> stringSupplier) {
        if (target == null) {
            return false;
        }
        boolean targetIsBoolean = target.equalsIgnoreCase("true") || target.equalsIgnoreCase("false");
        if ((operator == ParamOperator.Equals || operator == ParamOperator.NotEquals) && targetIsBoolean && booleanSupplier != null) {
            boolean actual = booleanSupplier.getAsBoolean();
            boolean expected = Boolean.parseBoolean(target);
            return operator == ParamOperator.Equals ? actual == expected : actual != expected;
        }
        Double targetNumber = null;
        try {
            targetNumber = Double.parseDouble(target);
        } catch (NumberFormatException ignored) {
            targetNumber = null;
        }
        if (numberSupplier != null && targetNumber != null) {
            double actual = numberSupplier.getAsDouble();
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
        if ((operator == ParamOperator.Equals || operator == ParamOperator.NotEquals) && stringSupplier != null) {
            String value = stringSupplier.get();
            boolean matches = value != null && value.equalsIgnoreCase(target);
            return operator == ParamOperator.Equals ? matches : !matches;
        }
        return false;
    }
}
