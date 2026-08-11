package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.EntityFilterBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/**
 * Adapts Tamework entity filters to the Update 5 and Update 6 callback signatures.
 */
public abstract class TameworkEntityFilterBase extends EntityFilterBase {
    /** Update 5 callback retained for dual-version loading. */
    public abstract boolean matchesEntity(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Ref<EntityStore> targetRef,
                                          @Nonnull Role role,
                                          @Nonnull Store<EntityStore> store);

    @Override
    public final boolean matchesEntity(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull Ref<EntityStore> targetRef,
                                       @Nonnull ExecutionSupport support,
                                       @Nonnull Store<EntityStore> store) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            return matchesEntity(ref, targetRef, support.getRole(), store);
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }

    /** Update 5 lifecycle callback retained for dual-version loading. */
    public void registerWithSupport(@Nonnull Role role) {
    }

    @Override
    public final void registerWithSupport(@Nonnull ExecutionSupport support) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            registerWithSupport(support.getRole());
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }
}
