package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkInteractionActive;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/** Reads active interaction chains on the NPC world thread without retaining player state. */
public final class EntityFilterTameworkInteractionActive extends TameworkEntityFilterBase {
    private final String rootInteractionId;

    public EntityFilterTameworkInteractionActive(@Nonnull BuilderEntityFilterTameworkInteractionActive builder,
                                                 @Nonnull BuilderSupport support) {
        rootInteractionId = builder.getRootInteractionId(support);
    }

    @Override
    public boolean matchesEntity(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Ref<EntityStore> targetRef,
                                 @Nonnull Role role,
                                 @Nonnull Store<EntityStore> store) {
        if (!targetRef.isValid()) {
            return false;
        }
        InteractionModule module = InteractionModule.get();
        if (module == null || module.getInteractionManagerComponent() == null) {
            return false;
        }
        InteractionManager manager = store.getComponent(targetRef, module.getInteractionManagerComponent());
        if (manager == null) {
            return false;
        }
        // Only the candidate's small active-chain map is read; no global entity scan or cached live refs.
        for (InteractionChain chain : manager.getChains().values()) {
            if (chain.getServerState() == InteractionState.NotFinished
                    && chain.getInitialRootInteraction() != null
                    && rootInteractionId.equals(chain.getInitialRootInteraction().getId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int cost() {
        return 100;
    }
}
