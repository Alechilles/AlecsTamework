package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.npc.role.Role;

/** Resolves interaction configs and role-scoped parameters. */
final class InteractionResolution {
    private final InteractionParamAccess paramAccess;
    private final InteractionConfigResolver configResolver;

    InteractionResolution(InteractionParamAccess paramAccess, InteractionConfigResolver configResolver) {
        this.paramAccess = paramAccess;
        this.configResolver = configResolver;
    }

    // Builds the cached interaction snapshot for a player and role.
    InteractionContextSnapshot buildContextSnapshot(Player player, Role role) {
        return paramAccess.buildContextSnapshot(player, role);
    }

    // Resolves the interaction config using overrides and role params.
    TwInteractionConfig resolveConfig(Role role, InteractionContextSnapshot ctx) {
        return configResolver.resolveConfig(role, ctx);
    }

    // Resolves a string param from role scopes.
    String getRoleStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramAccess.getRoleStringParam(role, ctx, paramName);
    }

    // Resolves a string array param from role scopes.
    String[] getRoleStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramAccess.getRoleStringArrayParam(role, ctx, paramName);
    }

    // Resolves a boolean param from role scopes.
    boolean getRoleBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramAccess.getRoleBooleanParam(role, ctx, paramName);
    }

    // Resolves a numeric param from role scopes with a default value.
    double getRoleNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        return paramAccess.getRoleNumberParam(role, ctx, paramName, defaultValue);
    }

    // Resolves loved items from overrides or role params.
    String[] resolveLovedItems(Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveLovedItems(role, ctx);
    }

    // Resolves whether the role is harvestable using overrides or params.
    boolean resolveIsHarvestable(Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveIsHarvestable(role, ctx);
    }

    // Resolves whether the role is mountable using overrides or params.
    boolean resolveIsMountable(Role role, InteractionContextSnapshot ctx) {
        return paramAccess.resolveIsMountable(role, ctx);
    }
}
