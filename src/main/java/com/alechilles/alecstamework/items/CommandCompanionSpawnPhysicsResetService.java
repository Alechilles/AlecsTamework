package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Clears spawn-time physics state that should not survive companion respawn or lost-recovery replacement.
 */
public final class CommandCompanionSpawnPhysicsResetService {
    private CommandCompanionSpawnPhysicsResetService() {
    }

    @Nonnull
    public static String resetSpawnedCompanionPhysics(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable NPCEntity npc,
                                                      @Nullable Store<EntityStore> store) {
        return resetSpawnedCompanionPhysics(
                npc, resolveVelocity(npcRef, store));
    }

    @Nonnull
    static String resetSpawnedCompanionPhysics(
            @Nullable NPCEntity npc,
            @Nullable Velocity velocity
    ) {
        double beforeFallDistance = npc != null ? npc.getCurrentFallDistance() : 0.0;
        String beforeVelocity = "<missing>";
        int clearedInstructions = 0;
        boolean velocityComponent = velocity != null;

        if (npc != null) {
            npc.setCurrentFallDistance(0.0);
        }
        if (velocity != null) {
            beforeVelocity = describeVector(velocity.getVelocity());
            List<Velocity.Instruction> instructions = velocity.getInstructions();
            if (instructions != null) {
                clearedInstructions = instructions.size();
                instructions.clear();
            }
            velocity.setZero();
            velocity.setClient(0.0, 0.0, 0.0);
        }

        return String.format(
                Locale.ROOT,
                "fallDistanceBefore=%.3f velocityBefore=%s velocityComponent=%s clearedVelocityInstructions=%d",
                beforeFallDistance,
                beforeVelocity,
                velocityComponent,
                clearedInstructions
        );
    }

    @Nullable
    private static Velocity resolveVelocity(
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store
    ) {
        if (npcRef == null || store == null) {
            return null;
        }
        try {
            if (!npcRef.isValid()) {
                return null;
            }
            ComponentType<EntityStore, Velocity> velocityType =
                    Velocity.getComponentType();
            return velocityType == null
                    ? null
                    : store.getComponent(npcRef, velocityType);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nonnull
    private static String describeVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return "<null>";
        }
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", vector.x, vector.y, vector.z);
    }
}
