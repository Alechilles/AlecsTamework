package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.compat.HytaleSpatialAccess;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
                        @Nullable Ref<EntityStore> ownerRef,
                        @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (systemId.isBlank()) return false;
        try {
            if (ParticleSystem.getAssetMap().getAsset(systemId) == null) {
                warnOnce(systemId, "AvatarFlight particle system is missing: " + systemId);
                return false;
            }
            Vector3d position = new Vector3d(x, y, z);
            List<Ref<EntityStore>> recipients = resolveRecipients(position, ownerRef, componentAccessor);
            if (recipients.isEmpty()) {
                warnOnce(systemId, "AvatarFlight particle system has no packet recipients: " + systemId);
                return false;
            }
            ParticleUtil.spawnParticleEffect(
                    systemId,
                    x,
                    y,
                    z,
                    yaw,
                    0.0f,
                    0.0f,
                    scale,
                    null,
                    null,
                    recipients,
                    componentAccessor,
                    maxDurationSeconds
            );
            return true;
        } catch (RuntimeException error) {
            warnOnce(systemId, "AvatarFlight particle emission failed for " + systemId
                    + ": " + error.getMessage());
            return false;
        }
    }

    @Nonnull
    private static List<Ref<EntityStore>> resolveRecipients(
            @Nonnull Vector3d position,
            @Nullable Ref<EntityStore> ownerRef,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        List<Ref<EntityStore>> recipients = SpatialResource.getThreadLocalReferenceList();
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource =
                componentAccessor.getResource(EntityModule.get().getPlayerSpatialResourceType());
        if (playerSpatialResource != null) {
            HytaleSpatialAccess.collect(
                    playerSpatialResource.getSpatialStructure(),
                    position,
                    ParticleUtil.DEFAULT_PARTICLE_DISTANCE,
                    recipients
            );
        }
        ensureOwnerRecipient(recipients, ownerRef);
        return recipients;
    }

    static void ensureOwnerRecipient(@Nonnull List<Ref<EntityStore>> recipients,
                                     @Nullable Ref<EntityStore> ownerRef) {
        if (ownerRef == null || !ownerRef.isValid()) return;
        for (Ref<EntityStore> recipient : recipients) {
            if (sameRef(recipient, ownerRef)) return;
        }
        recipients.add(ownerRef);
    }

    private static boolean sameRef(@Nullable Ref<EntityStore> first,
                                   @Nonnull Ref<EntityStore> second) {
        return first == second || first != null
                && first.getStore() == second.getStore()
                && first.getIndex() == second.getIndex();
    }

    private void warnOnce(@Nonnull String systemId, @Nonnull String message) {
        if (!warnedSystemIds.add(systemId)) return;
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().at(Level.WARNING).log(message);
        }
    }
}
