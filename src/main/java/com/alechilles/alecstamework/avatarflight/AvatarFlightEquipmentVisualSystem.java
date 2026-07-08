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
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps transformed avatar-flight players from rendering normal player equipment.
 */
public final class AvatarFlightEquipmentVisualSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType;
    private final ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType;
    private final AvatarFlightModelService modelService = new AvatarFlightModelService();
    private final AvatarFlightRiderVisualService riderVisualService = new AvatarFlightRiderVisualService();
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, InventorySystems.SyncEquipmentSystem.class)
    );

    public AvatarFlightEquipmentVisualSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
            @Nonnull ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType,
            @Nonnull ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType) {
        this.flightType = flightType;
        this.visualType = visualType;
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
        refreshRiderVisualIfNeeded(ref, commandBuffer, settings);
        if (settings.isHideOwnerEquipment()) {
            queueHiddenOwnerUpdate(ref, commandBuffer, visible, settings);
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

    private void queueHiddenOwnerUpdate(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull EntityTrackerSystems.Visible visible,
            @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings) {
        EquipmentUpdate update = AvatarFlightEquipmentPacketService.createHiddenOwnerEquipmentUpdate(
                ref,
                commandBuffer,
                settings
        );
        queueAllExceptSelf(ref, update, visible.visibleTo);
        queueAllExceptSelf(ref, update, visible.newlyVisibleTo);
        queueSelfIfHiddenOwnerEquipmentChanged(ref, commandBuffer, visible, update);
    }

    private void refreshRiderVisualIfNeeded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull TwAvatarFlightConfig.RiderVisualSettings settings) {
        if (!settings.isShowRider()) {
            return;
        }
        AvatarFlightRiderVisualComponent visual = commandBuffer.getComponent(ref, visualType);
        if (visual == null || visual.isRiderEntity()) {
            return;
        }
        AvatarFlightEquipmentAttachmentResolver.EquipmentSnapshot equipment =
                AvatarFlightEquipmentAttachmentResolver.resolveSnapshot(ref, commandBuffer);
        if (equipment.armorSignature().equals(visual.getEquipmentSignature())) {
            return;
        }
        UUID ownerUuid = parseOwnerUuid(visual);
        if (ownerUuid == null) {
            return;
        }
        Model savedModel = modelService.savedModelCopy(ownerUuid);
        if (savedModel == null) {
            return;
        }
        if (riderVisualService.refresh(commandBuffer, ref, savedModel, equipment)) {
            AvatarFlightRiderVisualComponent updated = visual.clone();
            updated.setEquipmentSignature(equipment.armorSignature());
            commandBuffer.putComponent(ref, visualType, updated);
        }
    }

    @Nullable
    private static UUID parseOwnerUuid(@Nonnull AvatarFlightRiderVisualComponent visual) {
        if (visual.getOwnerUuid().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(visual.getOwnerUuid());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void queueAll(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull EquipmentUpdate update,
                                 @Nonnull Map<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> visibleTo) {
        for (EntityTrackerSystems.EntityViewer viewer : visibleTo.values()) {
            viewer.queueUpdate(ref, update);
        }
    }

    private static void queueAllExceptSelf(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull EquipmentUpdate update,
            @Nonnull Map<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> visibleTo) {
        for (Map.Entry<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> entry : visibleTo.entrySet()) {
            if (ref.equals(entry.getKey())) {
                continue;
            }
            entry.getValue().queueUpdate(ref, update);
        }
    }

    private void queueSelfIfHiddenOwnerEquipmentChanged(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull EntityTrackerSystems.Visible visible,
            @Nonnull EquipmentUpdate update) {
        AvatarFlightRiderVisualComponent visual = commandBuffer.getComponent(ref, visualType);
        if (visual == null || visual.isRiderEntity()) {
            return;
        }
        String signature = AvatarFlightEquipmentPacketService.equipmentSignature(update);
        if (signature.equals(visual.getHiddenOwnerEquipmentSignature())) {
            return;
        }
        if (!queueSelf(ref, update, visible.visibleTo) && !queueSelf(ref, update, visible.newlyVisibleTo)) {
            return;
        }
        AvatarFlightRiderVisualComponent updated = visual.clone();
        updated.setHiddenOwnerEquipmentSignature(signature);
        commandBuffer.putComponent(ref, visualType, updated);
    }

    private static boolean queueSelf(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull EquipmentUpdate update,
            @Nonnull Map<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> visibleTo) {
        EntityTrackerSystems.EntityViewer viewer = visibleTo.get(ref);
        if (viewer == null) {
            return false;
        }
        viewer.queueUpdate(ref, update);
        return true;
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
