package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Resolves and broadcasts bounded world-space avatar-flight particle systems.
 */
public final class AvatarFlightParticleEmitter {
    private final Set<String> warnedSystemIds = ConcurrentHashMap.newKeySet();

    public boolean emit(@Nonnull String systemId,
                        double x,
                        double y,
                        double z,
                        float yaw,
                        float scale,
                        float maxDurationSeconds,
                        @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (systemId.isBlank()) return false;
        try {
            if (ParticleSystem.getAssetMap().getAsset(systemId) == null) {
                warnOnce(systemId, "AvatarFlight launch particle system is missing: " + systemId);
                return false;
            }
            ParticleUtil.spawnParticleEffect(
                    systemId,
                    new Vector3d(x, y, z),
                    yaw,
                    0.0f,
                    0.0f,
                    scale,
                    maxDurationSeconds,
                    componentAccessor
            );
            return true;
        } catch (RuntimeException error) {
            warnOnce(systemId, "AvatarFlight launch particle emission failed for " + systemId
                    + ": " + error.getMessage());
            return false;
        }
    }

    private void warnOnce(@Nonnull String systemId, @Nonnull String message) {
        if (!warnedSystemIds.add(systemId)) return;
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().at(Level.WARNING).log(message);
        }
    }
}
