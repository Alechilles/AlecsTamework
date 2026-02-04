package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;

final class TameworkCommandTargeting {
    private static final double MAX_DISTANCE = 6.0;
    private static final double MIN_DOT = 0.7; // ~45 degrees

    private TameworkCommandTargeting() {
    }

    // Finds the closest NPC in the player's view cone within MAX_DISTANCE.
    static Candidate findTargetNpc(Store<EntityStore> store, Ref<EntityStore> playerRef) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }

        Vector3d playerPos = new Vector3d(transform.getPosition());
        Vector3f rotation = new Vector3f(transform.getRotation());
        HeadRotation headRotation = store.getComponent(playerRef, HeadRotation.getComponentType());
        Vector3f headRot = headRotation != null ? headRotation.getRotation() : rotation;

        // Forward vector uses head rotation so targeting matches the camera.
        Vector3f forward = new Vector3f(Vector3f.FORWARD);
        forward.rotateY(headRot.getYaw());
        forward.rotateX(headRot.getPitch());
        forward.normalize();
        Vector3d forwardDir = new Vector3d(forward.x, forward.y, forward.z);

        BestCandidate best = new BestCandidate();

        // Scan NPCs and score by view alignment and distance.
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                if (npc == null) {
                    continue;
                }
                TransformComponent npcTransform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (npcTransform == null) {
                    continue;
                }
                Vector3d npcPos = npcTransform.getPosition();
                Vector3d toNpc = new Vector3d(npcPos).subtract(playerPos);
                double dist = toNpc.length();
                if (dist <= 0.1 || dist > MAX_DISTANCE) {
                    continue;
                }
                Vector3d dir = new Vector3d(toNpc).normalize();
                double dot = forwardDir.dot(dir);
                if (dot < MIN_DOT) {
                    continue;
                }
                double score = dot / dist;
                if (score > best.score) {
                    best.score = score;
                    best.ref = chunk.getReferenceTo(i);
                    best.npcUuid = npc.getUuid();
                }
            }
        });

        if (best.ref == null || best.npcUuid == null) {
            return null;
        }
        return new Candidate(best.ref, best.npcUuid);
    }

    static final class Candidate {
        final Ref<EntityStore> ref;
        final UUID npcUuid;

        Candidate(Ref<EntityStore> ref, UUID npcUuid) {
            this.ref = ref;
            this.npcUuid = npcUuid;
        }
    }

    private static final class BestCandidate {
        private Ref<EntityStore> ref;
        private UUID npcUuid;
        private double score = -1.0;
    }
}
