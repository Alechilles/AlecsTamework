package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.Phobia;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for removing highlight helpers before a player mounts their NPC. */
class CommandActiveNpcHighlightMountCleanupServiceTest {
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;
    private ComponentType<EntityStore, MountedComponent> mountedType;
    private ComponentType<EntityStore, ModelComponent> modelType;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        mountedType = registry.registerComponent(
                MountedComponent.class,
                () -> new MountedComponent(null, new Vector3f(), MountController.Minecart)
        );
        modelType = registry.registerComponent(
                ModelComponent.class,
                () -> new ModelComponent(null)
        );
        store = registry.addStore(null, null);
    }

    @AfterEach
    void tearDown() {
        registry.removeStore(store);
        registry.shutdown();
    }

    @Test
    void removesOnlyHighlightHelpersMountedToNpc() {
        Ref<EntityStore> targetNpc = store.addEntity(registry.newHolder(), AddReason.SPAWN);
        Ref<EntityStore> anotherNpc = store.addEntity(registry.newHolder(), AddReason.SPAWN);
        Ref<EntityStore> targetHelper = addMountedModel(
                targetNpc, CommandActiveNpcHighlightProxyService.MODEL_ASSET_ID
        );
        Ref<EntityStore> anotherNpcHelper = addMountedModel(
                anotherNpc, CommandActiveNpcHighlightProxyService.MODEL_ASSET_ID
        );
        Ref<EntityStore> unrelatedPassenger = addMountedModel(targetNpc, "Unrelated_Model");
        CommandActiveNpcHighlightMountCleanupService service =
                new CommandActiveNpcHighlightMountCleanupService(mountedType, modelType);

        service.removeBeforeMount(store, targetNpc);

        assertFalse(targetHelper.isValid());
        assertTrue(anotherNpcHelper.isValid());
        assertTrue(unrelatedPassenger.isValid());
    }

    private Ref<EntityStore> addMountedModel(Ref<EntityStore> parent, String modelAssetId) {
        Holder<EntityStore> holder = registry.newHolder();
        holder.addComponent(
                mountedType,
                new MountedComponent(parent, new Vector3f(), MountController.Minecart)
        );
        holder.addComponent(modelType, new ModelComponent(model(modelAssetId)));
        return store.addEntity(holder, AddReason.SPAWN);
    }

    private static Model model(String modelAssetId) {
        return new Model(
                modelAssetId,
                1.0f,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Phobia.None,
                null
        );
    }
}
