package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nullable;

/**
 * Action that performs explicit needs resource consumption driven by template flow.
 */
public final class ActionTameworkNeedsResourceConsume extends TameworkActionBase {
    @Nullable
    private final String resourceType;
    @Nullable
    private final String[] foodItemIds;

    public ActionTameworkNeedsResourceConsume(BuilderActionTameworkNeedsResourceConsume builder, BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
        this.foodItemIds = builder.getFoodItemIds(support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && store != null && npcRef.isValid();
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        return CompanionNeedsService.applyResourceConsumeWithDiagnostics(
                npcRef,
                store,
                roleId,
                resourceType,
                foodItemIds
        );
    }
}
