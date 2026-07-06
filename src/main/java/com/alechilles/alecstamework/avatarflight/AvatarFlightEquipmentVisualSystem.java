package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.server.core.inventory.InventorySystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps transformed avatar-flight players from rendering normal player equipment.
 */
public final class AvatarFlightEquipmentVisualSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, AvatarFlightRiderVisualComponent> riderVisualType;
    private final ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, InventorySystems.SyncEquipmentSystem.class)
    );

    public AvatarFlightEquipmentVisualSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
            @Nonnull ComponentType<EntityStore, AvatarFlightRiderVisualComponent> riderVisualType,
            @Nonnull ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType) {
        this.flightType = flightType;
        this.riderVisualType = riderVisualType;
        this.visibleType = visibleType;
        this.query = Query.and(flightType, visibleType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        AvatarFlightComponent flight = archetypeChunk.getComponent(index, flightType);
        EntityTrackerSystems.Visible visible = archetypeChunk.getComponent(index, visibleType);
        if (ref == null || visible == null || flight == null) {
            return;
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        TwAvatarFlightConfig.RiderVisualSettings settings = config.getRiderVisual();
        AvatarFlightRiderVisualComponent riderVisual = commandBuffer.getComponent(ref, riderVisualType);
        if (riderVisual != null && settings.isHideOwnerEquipment()) {
            riderVisual = queueHiddenOwnerUpdate(ref, commandBuffer, visible, settings, riderVisual);
        }
    }

    public static void restoreCurrentEquipment(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull ComponentAccessor<EntityStore> accessor) {
        EntityTrackerSystems.Visible visible = accessor.getComponent(
                ref,
                EntityTrackerSystems.Visible.getComponentType()
        );
        if (visible == null) {
            return;
        }
        EquipmentUpdate update = AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate(ref, accessor);
        queueAll(ref, update, visible.visibleTo);
        queueAll(ref, update, visible.newlyVisibleTo);
    }

    @Nonnull
    private AvatarFlightRiderVisualComponent queueHiddenOwnerUpdate(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull EntityTrackerSystems.Visible visible,
            @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings,
            @Nonnull AvatarFlightRiderVisualComponent riderVisual) {
        EquipmentUpdate update = AvatarFlightEquipmentPacketService.createHiddenOwnerEquipmentUpdate(
                ref,
                commandBuffer,
                settings
        );
        EquipmentUpdate current = AvatarFlightEquipmentPacketService.createCurrentEquipmentUpdate(ref, commandBuffer);
        String signature = AvatarFlightEquipmentPacketService.equipmentSignature(current)
                + "->"
                + AvatarFlightEquipmentPacketService.equipmentSignature(update);
        AvatarFlightRiderVisualComponent updated = queueIfEquipmentChanged(
                ref,
                commandBuffer,
                riderVisual,
                "owner",
                signature,
                settings.getEquipmentResendIntervalMs(),
                () -> queueAll(ref, update, visible.visibleTo),
                () -> queueAll(ref, update, visible.newlyVisibleTo)
        );
        return updated == null ? riderVisual : updated;
    }

    @Nullable
    private AvatarFlightRiderVisualComponent queueIfEquipmentChanged(
            @Nonnull Ref<EntityStore> ownerRef,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull AvatarFlightRiderVisualComponent riderVisual,
            @Nonnull String key,
            @Nonnull String signature,
            long resendIntervalMs,
            @Nonnull Runnable queueVisible,
            @Nonnull Runnable queueNewlyVisible) {
        long now = System.currentTimeMillis();
        boolean changed = !signature.equals(readSignature(riderVisual.getEquipmentSignature(), key));
        boolean expired = now - riderVisual.getLastEquipmentSentAtMs() >= resendIntervalMs;
        if (changed || expired) {
            queueVisible.run();
        }
        queueNewlyVisible.run();
        if (!changed && !expired) {
            return null;
        }
        AvatarFlightRiderVisualComponent updated = riderVisual.clone();
        updated.setEquipmentSignature(writeSignature(riderVisual.getEquipmentSignature(), key, signature));
        updated.setLastEquipmentSentAtMs(now);
        commandBuffer.putComponent(ownerRef, riderVisualType, updated);
        return updated;
    }

    @Nonnull
    private static String readSignature(@Nonnull String composite, @Nonnull String key) {
        String prefix = key + "=";
        for (String part : composite.split("\\n")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return "";
    }

    @Nonnull
    private static String writeSignature(@Nonnull String composite,
                                         @Nonnull String key,
                                         @Nonnull String signature) {
        String prefix = key + "=";
        StringBuilder result = new StringBuilder();
        boolean replaced = false;
        for (String part : composite.split("\\n")) {
            if (part.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            if (part.startsWith(prefix)) {
                result.append(prefix).append(signature);
                replaced = true;
            } else {
                result.append(part);
            }
        }
        if (!replaced) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(prefix).append(signature);
        }
        return result.toString();
    }

    private static void queueAll(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull EquipmentUpdate update,
                                 @Nonnull Map<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> visibleTo) {
        for (EntityTrackerSystems.EntityViewer viewer : visibleTo.values()) {
            viewer.queueUpdate(ref, update);
        }
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.QUEUE_UPDATE_GROUP;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }
}
