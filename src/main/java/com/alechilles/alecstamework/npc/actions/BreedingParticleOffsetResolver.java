package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.npc.util.expression.ValueType;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the breeding particle spawn offset from the NPC role template's {@code ParticleOffset} parameter.
 */
final class BreedingParticleOffsetResolver {
    private static final String PARTICLE_OFFSET_PARAMETER = "ParticleOffset";
    private static final double[] DEFAULT_OFFSET = new double[]{0.0, 1.0, 0.0};

    /**
     * Resolves the role-configured particle offset for the NPC, or a default offset when unavailable.
     */
    @Nonnull
    Vector3d resolveOffset(@Nullable NPCEntity npcEntity) {
        if (npcEntity == null) {
            return toVector(DEFAULT_OFFSET);
        }
        int roleIndex = npcEntity.getRoleIndex();
        if (roleIndex < 0) {
            return toVector(DEFAULT_OFFSET);
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return toVector(DEFAULT_OFFSET);
        }
        Builder<Role> roleBuilder = npcPlugin.tryGetCachedValidRole(roleIndex);
        if (roleBuilder == null) {
            return toVector(DEFAULT_OFFSET);
        }
        BuilderParameters builderParameters = roleBuilder.getBuilderParameters();
        if (builderParameters == null) {
            return toVector(DEFAULT_OFFSET);
        }
        try {
            StdScope scope = builderParameters.createScope();
            if (scope.getType(PARTICLE_OFFSET_PARAMETER) != ValueType.NUMBER_ARRAY) {
                return toVector(DEFAULT_OFFSET);
            }
            Supplier<double[]> supplier = scope.getNumberArraySupplier(PARTICLE_OFFSET_PARAMETER);
            double[] value = supplier != null ? supplier.get() : null;
            return toVector(value);
        } catch (Exception ignored) {
            return toVector(DEFAULT_OFFSET);
        }
    }

    @Nonnull
    private static Vector3d toVector(@Nullable double[] value) {
        if (value == null || value.length < 3) {
            return new Vector3d(DEFAULT_OFFSET[0], DEFAULT_OFFSET[1], DEFAULT_OFFSET[2]);
        }
        double x = sanitize(value[0], DEFAULT_OFFSET[0]);
        double y = sanitize(value[1], DEFAULT_OFFSET[1]);
        double z = sanitize(value[2], DEFAULT_OFFSET[2]);
        return new Vector3d(x, y, z);
    }

    private static double sanitize(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}

