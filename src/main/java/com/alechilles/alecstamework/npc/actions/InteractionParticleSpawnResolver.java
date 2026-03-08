package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParticleAttachTarget;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SpawnParticlesEffect;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nullable;

/**
 * Resolves SpawnParticles interaction settings, including param-driven values and model-attach metadata.
 */
final class InteractionParticleSpawnResolver {
    private final ActionTameworkInteract owner;

    InteractionParticleSpawnResolver(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    @Nullable
    ResolvedParticleSpawn resolve(SpawnParticlesEffect effect,
                                  Ref<EntityStore> npcRef,
                                  Role role,
                                  Store<EntityStore> store,
                                  InteractionContextSnapshot ctx) {
        if (effect == null || npcRef == null || store == null) {
            return null;
        }
        String particleSystem = resolveParticleSystem(effect, role, ctx);
        if (particleSystem == null || particleSystem.isBlank()) {
            return null;
        }
        Vector3d position = resolveBasePosition(npcRef, store);
        if (position == null) {
            return null;
        }

        Vector3d offset = resolveOffset(effect, role, ctx);
        ParticleAttachTarget attachTarget = effect.getAttachTarget();
        position.x += offset.x;
        position.y += offset.y;
        position.z += offset.z;

        String attachNode = attachTarget == ParticleAttachTarget.Node ? effect.getAttachNode() : null;
        boolean attachToNpc = attachTarget == ParticleAttachTarget.Entity;
        return new ResolvedParticleSpawn(
                particleSystem,
                position,
                offset,
                attachTarget,
                attachNode,
                attachToNpc
        );
    }

    private String resolveParticleSystem(SpawnParticlesEffect effect,
                                         Role role,
                                         InteractionContextSnapshot ctx) {
        String particleParam = effect.getParticleSystemParam();
        if (particleParam != null && !particleParam.isBlank()) {
            String resolved = owner.getRoleStringParam(role, ctx, particleParam);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return effect.getParticleSystem();
    }

    private Vector3d resolveOffset(SpawnParticlesEffect effect,
                                   Role role,
                                   InteractionContextSnapshot ctx) {
        Vector3d literalOffset = effect.getOffset();
        Vector3d resolved = literalOffset != null
                ? new Vector3d(literalOffset)
                : new Vector3d(0.0, 0.0, 0.0);

        String offsetParam = effect.getOffsetParam();
        if (offsetParam == null || offsetParam.isBlank()) {
            return resolved;
        }

        Vector3d paramOffset = resolveOffsetFromParam(role, ctx, offsetParam);
        if (paramOffset != null) {
            return paramOffset;
        }
        return resolved;
    }

    @Nullable
    private Vector3d resolveOffsetFromParam(Role role,
                                            InteractionContextSnapshot ctx,
                                            String offsetParam) {
        double[] numeric = owner.getRoleNumberArrayParam(role, ctx, offsetParam);
        Vector3d numericVector = toVector(numeric);
        if (numericVector != null) {
            return numericVector;
        }

        String[] raw = owner.getRoleStringArrayParam(role, ctx, offsetParam);
        if (raw == null || raw.length == 0) {
            return null;
        }
        if (raw.length >= 3) {
            Double x = parseDouble(raw[0]);
            Double y = parseDouble(raw[1]);
            Double z = parseDouble(raw[2]);
            if (x != null && y != null && z != null) {
                return new Vector3d(x, y, z);
            }
        }
        return parseSingleOffsetToken(raw[0]);
    }

    @Nullable
    private Vector3d parseSingleOffsetToken(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("[") && cleaned.endsWith("]") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        String[] parts = cleaned.split("[,\\s]+");
        if (parts.length < 3) {
            return null;
        }
        Double x = parseDouble(parts[0]);
        Double y = parseDouble(parts[1]);
        Double z = parseDouble(parts[2]);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new Vector3d(x, y, z);
    }

    @Nullable
    private Double parseDouble(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private Vector3d toVector(@Nullable double[] value) {
        if (value == null || value.length < 3) {
            return null;
        }
        if (!Double.isFinite(value[0]) || !Double.isFinite(value[1]) || !Double.isFinite(value[2])) {
            return null;
        }
        return new Vector3d(value[0], value[1], value[2]);
    }

    @Nullable
    private Vector3d resolveBasePosition(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return new Vector3d(transform.getPosition());
    }

    static final class ResolvedParticleSpawn {
        final String particleSystem;
        final Vector3d position;
        final Vector3d offset;
        final ParticleAttachTarget attachTarget;
        final String attachNode;
        final boolean attachToNpc;

        ResolvedParticleSpawn(String particleSystem,
                             Vector3d position,
                             Vector3d offset,
                             ParticleAttachTarget attachTarget,
                             String attachNode,
                             boolean attachToNpc) {
            this.particleSystem = particleSystem;
            this.position = position;
            this.offset = offset;
            this.attachTarget = attachTarget;
            this.attachNode = attachNode;
            this.attachToNpc = attachToNpc;
        }
    }
}
