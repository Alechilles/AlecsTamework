package com.alechilles.alecstamework.companion.live;

import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.reference.PersistentRefCount;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionRespawnTest {
    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void preparedHolderHasAFreshIdentityTheNewStampAndTheDestination() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, UUIDComponent> uuidType = registry.registerComponent(UUIDComponent.class, () -> new UUIDComponent(OLD));
        ComponentType<EntityStore, NPCEntity> npcType = registry.registerComponent(NPCEntity.class, NPCEntity::new);
        ComponentType<EntityStore, TransformComponent> transformType = registry.registerComponent(TransformComponent.class, TransformComponent::new);
        ComponentType<EntityStore, PersistentRefCount> refCountType = registry.registerComponent(PersistentRefCount.class, PersistentRefCount::new);
        ComponentType<EntityStore, SpawnMarkerReference> markerType = registry.registerComponent(SpawnMarkerReference.class, SpawnMarkerReference::new);
        ComponentType<EntityStore, TameworkCompanionComponent> stampType = registry.registerComponent(TameworkCompanionComponent.class, TameworkCompanionComponent::new);

        Holder<EntityStore> holder = registry.newHolder();
        holder.addComponent(uuidType, new UUIDComponent(OLD));
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(OLD);
        npc.setDespawning(true);
        holder.addComponent(npcType, npc);
        holder.addComponent(transformType, new TransformComponent(new Vector3d(1, 2, 3), new Rotation3f(0, 0, 0)));
        holder.addComponent(refCountType, new PersistentRefCount());
        holder.addComponent(markerType, new SpawnMarkerReference());
        holder.addComponent(stampType, new TameworkCompanionComponent(UUID.randomUUID(), 2));

        CompanionRespawn.Types types = new CompanionRespawn.Types(uuidType, npcType, transformType, refCountType,
                null, null, null, stampType, List.of(markerType));
        UUID fresh = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        int refCountBefore = holder.getComponent(refCountType).get();

        new CompanionRespawn(types).prepare(holder, new Vector3d(100, 64, -50), new Rotation3f(0, 90, 0), fresh, profile, 3);

        assertEquals(fresh, holder.getComponent(uuidType).getUuid());
        assertEquals(new Vector3d(100, 64, -50), holder.getComponent(transformType).getPosition());
        assertEquals(new Vector3d(100, 64, -50), holder.getComponent(npcType).getLeashPoint());
        assertNull(holder.getComponent(markerType));
        assertEquals(refCountBefore + 1, holder.getComponent(refCountType).get());
        assertEquals(profile, holder.getComponent(stampType).getProfileId());
        assertEquals(3L, holder.getComponent(stampType).getGeneration());
        assertFalse(holder.getComponent(npcType).isDespawning());
    }

    @Test
    void stripDocumentRemovesOnlyThePathManager() {
        BsonDocument npc = new BsonDocument("RoleName", new BsonString("Sheep")).append("PathManager", new BsonDocument("CurrentPath", new BsonString("x")));
        BsonDocument entity = new BsonDocument("Components", new BsonDocument("NPC", npc));

        BsonDocument stripped = CompanionRespawn.stripDocument(entity);

        assertFalse(stripped.getDocument("Components").getDocument("NPC").containsKey("PathManager"));
        assertEquals("Sheep", stripped.getDocument("Components").getDocument("NPC").getString("RoleName").getValue());
    }

    @Test
    void stripDocumentPutsBackTheMotionControllerARideReplaced() {
        assertEquals("Walk", activeControllerAfterStrip("Walk"));
        assertEquals("TameworkFly", activeControllerAfterStrip("  "));
    }

    private static String activeControllerAfterStrip(String previousController) {
        BsonDocument components = new BsonDocument("NPC", new BsonDocument("ActiveMC", new BsonString("TameworkFly")))
                .append("TameworkRideMount", new BsonDocument("PreviousMotionController", new BsonString(previousController)));
        BsonDocument stripped = CompanionRespawn.stripDocument(new BsonDocument("Components", components));
        return stripped.getDocument("Components").getDocument("NPC").getString("ActiveMC").getValue();
    }
}
