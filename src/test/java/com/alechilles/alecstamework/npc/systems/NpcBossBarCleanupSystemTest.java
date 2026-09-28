package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.builtin.encountermanager.EncounterBossBarState;
import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents boss bars from surviving removal or unload when no TTL tick follows. */
class NpcBossBarCleanupSystemTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void removalAndUnloadRevertLiveMembersAndForgetInvalidMembers() {
        for (RemoveReason reason : List.of(RemoveReason.REMOVE, RemoveReason.UNLOAD)) {
            ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
            ComponentType<EntityStore, NPCEntity> npcType = registry.registerComponent(NPCEntity.class, NPCEntity::new);
            ComponentType<EntityStore, EncounterMembers> membersType =
                    registry.registerComponent(EncounterMembers.class, EncounterMembers::new);
            ComponentType<EntityStore, EncounterBossBarState> barType =
                    (ComponentType) registry.registerComponent(RecordingBossBar.class, RecordingBossBar::new);
            registry.registerSystem(new NpcBossBarCleanupSystem(npcType, membersType, barType));
            Store<EntityStore> store = registry.addStore(null, null);
            try {
                Ref<EntityStore> viewer = store.addEntity(registry.newHolder(), AddReason.SPAWN);
                Ref<EntityStore> disconnected = new Ref<>(null);
                EncounterMembers members = new EncounterMembers();
                members.stampMember(viewer, 0.75f);
                members.stampMember(disconnected, 0.75f);
                RecordingBossBar bar = new RecordingBossBar();
                Holder<EntityStore> holder = registry.newHolder();
                holder.addComponent(npcType, new NPCEntity());
                holder.addComponent(membersType, members);
                holder.addComponent(barType, bar);
                Ref<EntityStore> boss = store.addEntity(holder, AddReason.SPAWN);

                store.removeEntity(boss, reason);

                assertEquals(List.of(viewer), bar.reverted, reason.toString());
                assertEquals(List.of(disconnected), bar.forgotten, reason.toString());
                assertTrue(members.isEmpty(), reason.toString());
            } finally {
                registry.removeStore(store);
                registry.shutdown();
            }
        }
    }

    private static final class RecordingBossBar extends EncounterBossBarState {
        private final List<Ref<EntityStore>> reverted = new ArrayList<>();
        private final List<Ref<EntityStore>> forgotten = new ArrayList<>();

        @Override
        public void revertPlayer(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> playerRef) {
            reverted.add(playerRef);
        }

        @Override
        public void forgetPlayer(Ref<EntityStore> playerRef) {
            forgotten.add(playerRef);
        }
    }
}
