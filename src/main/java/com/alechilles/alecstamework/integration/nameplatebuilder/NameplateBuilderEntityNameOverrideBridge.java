package com.alechilles.alecstamework.integration.nameplatebuilder;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.frotty27.nameplatebuilder.api.NameplateAPI;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Registers Tamework's pet-name override for NameplateBuilder's built-in entity-name segment.
 */
public final class NameplateBuilderEntityNameOverrideBridge {
    private static final String ENTITY_NAME_SEGMENT_ID = "entity-name";
    private static final String OVERRIDE_DESCRIPTION = "Pet Names";

    private NameplateBuilderEntityNameOverrideBridge() {
    }

    public static void initialize(Tamework plugin) {
        NameplateAPI.override(
                plugin,
                ENTITY_NAME_SEGMENT_ID,
                OVERRIDE_DESCRIPTION,
                NameplateBuilderEntityNameOverrideBridge::resolveOverrideName
        );
    }

    @Nullable
    private static String resolveOverrideName(Store<EntityStore> store,
                                              Ref<EntityStore> entityRef,
                                              int variantIndex) {
        if (store == null || entityRef == null || !entityRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType == null) {
            return null;
        }
        TameworkNpcNameComponent nameComponent = store.getComponent(entityRef, nameType);
        if (nameComponent == null || nameComponent.getName() == null || nameComponent.getName().isBlank()) {
            return null;
        }
        return nameComponent.getName();
    }
}
