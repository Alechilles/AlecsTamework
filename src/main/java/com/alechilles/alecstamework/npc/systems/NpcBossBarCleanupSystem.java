package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.builtin.encountermanager.EncounterBossBarState;
import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Hides native NPC boss bars when removal leaves no further tick to expire members. */
public final class NpcBossBarCleanupSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, EncounterMembers> membersType;
    private final ComponentType<EntityStore, EncounterBossBarState> barType;
    private final Query<EntityStore> query;

    /** Construct only after EncounterManagerPlugin has registered its native component types. */
    public NpcBossBarCleanupSystem(@Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                  @Nonnull ComponentType<EntityStore, EncounterMembers> membersType,
                                  @Nonnull ComponentType<EntityStore, EncounterBossBarState> barType) {
        this.membersType = Objects.requireNonNull(membersType, "membersType");
        this.barType = Objects.requireNonNull(barType, "barType");
        this.query = Query.and(Objects.requireNonNull(npcType, "npcType"), membersType, barType);
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Membership is driven only by the NPC action.
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EncounterMembers members = commandBuffer.getComponent(reference, membersType);
        EncounterBossBarState bar = commandBuffer.getComponent(reference, barType);
        if (members == null || bar == null) {
            return;
        }
        for (Ref<EntityStore> member : members.getMemberTtl().keySet()) {
            if (member.isValid()) {
                bar.revertPlayer(commandBuffer, member);
            } else {
                bar.forgetPlayer(member);
            }
        }
        members.clearMembers();
        bar.clear();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
