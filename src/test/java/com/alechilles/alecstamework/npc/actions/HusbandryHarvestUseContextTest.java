package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.npc.actions.HusbandryHarvestUseContext.CapturedUse;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A toolless harvest (milking with a bucket) by a player who is not the owner must stay credited
 * to that player through its drop, and through a later drop of the same harvest. Before the fix
 * the use was dropped at begin (no tool) or at finish, so the activity fell back to the owner.
 */
class HusbandryHarvestUseContextTest {
    private static final UUID HARVESTER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID COMPANION = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearPendingUses() {
        HusbandryHarvestUseContext.clearPendingUses();
    }

    @Test
    void toollessHarvestNamesTheHarvestingPlayerAfterItsDrop() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(new EntityStore(null))) {
            Ref<EntityStore> npcRef = store.createReference();
            NPCEntity npc = new NPCEntity();
            npc.setLegacyUUID(COMPANION);
            store.put(npcRef, NPCEntity.getComponentType(), npc);

            HusbandryHarvestUseContext.begin(npcRef, store, CapturedUse.empty().withActor(HARVESTER));
            HusbandryHarvestUseContext.finish(npcRef, store);

            CapturedUse afterDrop = HusbandryHarvestUseContext.current(npcRef, store);
            assertEquals(HARVESTER, afterDrop.actorId());
            assertFalse(afterDrop.tool().present());
        }
    }
}
