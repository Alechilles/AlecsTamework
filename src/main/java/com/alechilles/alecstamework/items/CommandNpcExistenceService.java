package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Locates live NPC entities by UUID across all loaded worlds.
 */
final class CommandNpcExistenceService {

    @Nullable
    LiveNpcMatch findLiveNpc(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        Map<String, World> worldsByName = universe.getWorlds();
        if (worldsByName == null || worldsByName.isEmpty()) {
            return null;
        }
        Collection<World> worlds = new ArrayList<>(worldsByName.values());
        for (World world : worlds) {
            if (world == null) {
                continue;
            }
            Ref<EntityStore> reference = world.getEntityRef(npcUuid);
            if (reference == null || !reference.isValid()) {
                continue;
            }
            Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
            if (store == null) {
                continue;
            }
            NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
            if (npc == null) {
                continue;
            }
            return new LiveNpcMatch(world, reference, npc);
        }
        return null;
    }

    boolean despawnIfPresent(UUID npcUuid) {
        LiveNpcMatch liveNpc = findLiveNpc(npcUuid);
        if (liveNpc == null || liveNpc.npc() == null) {
            return false;
        }
        liveNpc.npc().setToDespawn();
        return true;
    }

    record LiveNpcMatch(World world, Ref<EntityStore> reference, NPCEntity npc) {
        String worldName() {
            return world != null ? world.getName() : "unknown";
        }
    }
}
