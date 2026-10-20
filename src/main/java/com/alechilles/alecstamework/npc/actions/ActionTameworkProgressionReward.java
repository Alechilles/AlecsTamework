package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/**
 * Grants happiness and companion XP to the NPC from a role instruction, for example after play.
 */
public final class ActionTameworkProgressionReward extends TameworkActionBase {

    private final double happiness;
    private final double xp;

    public ActionTameworkProgressionReward(BuilderActionTameworkProgressionReward builder, BuilderSupport support) {
        super(builder);
        this.happiness = builder.getHappiness(support);
        this.xp = builder.getXp(support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid();
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
        if (happiness != 0.0) {
            CompanionHappinessService.applyImpulse(npcRef, store, happiness);
        }
        if (xp > 0.0) {
            CompanionLevelingService.awardXp(npcRef, store, xp);
        }
        return true;
    }
}
