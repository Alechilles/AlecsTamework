package com.alechilles.alecstamework.avatarflight;

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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventorySystems;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps transformed avatar-flight players from rendering normal player held-item equipment.
 */
public final class AvatarFlightEquipmentVisualSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, InventorySystems.SyncEquipmentSystem.class)
    );

    public AvatarFlightEquipmentVisualSystem(
            @Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
            @Nonnull ComponentType<EntityStore, EntityTrackerSystems.Visible> visibleType) {
        this.flightType = flightType;
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
        EntityTrackerSystems.Visible visible = archetypeChunk.getComponent(index, visibleType);
        if (ref == null || visible == null) {
            return;
        }
        queueHiddenHandUpdate(ref, commandBuffer, visible);
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
        EquipmentUpdate update = createCurrentEquipmentUpdate(ref, accessor);
        queue(ref, update, visible.visibleTo);
        queue(ref, update, visible.newlyVisibleTo);
    }

    private static void queueHiddenHandUpdate(@Nonnull Ref<EntityStore> ref,
                                              @Nonnull ComponentAccessor<EntityStore> accessor,
                                              @Nonnull EntityTrackerSystems.Visible visible) {
        EquipmentUpdate update = createCurrentEquipmentUpdate(ref, accessor);
        update.rightHandItemId = BlockType.EMPTY_KEY;
        update.leftHandItemId = BlockType.EMPTY_KEY;
        queue(ref, update, visible.visibleTo);
        queue(ref, update, visible.newlyVisibleTo);
    }

    @Nonnull
    private static EquipmentUpdate createCurrentEquipmentUpdate(@Nonnull Ref<EntityStore> ref,
                                                               @Nonnull ComponentAccessor<EntityStore> accessor) {
        PlayerSettings playerSettings = accessor.getComponent(ref, PlayerSettings.getComponentType());
        InventoryComponent.Armor armor = accessor.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utility = accessor.getComponent(ref, InventoryComponent.Utility.getComponentType());
        return InventoryUtils.createEquipmentUpdate(ref, accessor, playerSettings, armor, utility);
    }

    private static void queue(@Nonnull Ref<EntityStore> ref,
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
