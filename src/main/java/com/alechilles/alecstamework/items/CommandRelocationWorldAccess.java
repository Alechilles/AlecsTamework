package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Encapsulates relocation world-thread dispatch and entity access primitives. */
final class CommandRelocationWorldAccess {
    private static final int CHUNK_SIZE = 32;
    boolean isAtDestination(Vector3d current, Vector3d destination, double tolerance) {
        return isNear(current, destination, tolerance);
    }

    boolean isNear(@Nullable Vector3d left, @Nullable Vector3d right, double tolerance) {
        if (left == null || right == null) {
            return false;
        }
        double dx = left.x - right.x;
        double dy = left.y - right.y;
        double dz = left.z - right.z;
        return (dx * dx + dy * dy + dz * dz) <= (tolerance * tolerance);
    }

    int toChunk(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CHUNK_SIZE);
    }

    @Nullable
    String normalizeWorldName(@Nullable String worldName) {
        return worldName == null || worldName.isBlank() ? null : worldName.trim();
    }

    boolean hasExpectedLiveOwner(
            Store<EntityStore> store,
            Ref<EntityStore> ref,
            PendingRelocation pending
    ) {
        TameworkOwnerComponent owner = safeGetComponent(
                store, ref, TameworkOwnerComponent.getComponentType()
        );
        return owner != null && Objects.equals(
                owner.getOwnerId(), pending.ownerUuid
        );
    }

    @Nullable
    World resolveLoadedWorld(@Nullable String worldName) {
        String normalized = normalizeWorldName(worldName);
        Universe universe = Universe.get();
        if (normalized == null || universe == null) {
            return null;
        }
        for (World world : universe.getWorlds().values()) {
            if (world != null && normalized.equals(normalizeWorldName(world.getName()))) {
                return world;
            }
        }
        return null;
    }

    @Nullable
    Vector3d copyPosition(@Nullable Vector3d position) {
        return position == null ? null : new Vector3d(position);
    }

    void execute(World world, Runnable task, Runnable rejected) {
        if (world == null) {
            runRejected(rejected);
            return;
        }
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> {
                    try {
                        task.run();
                    } catch (RuntimeException | LinkageError exception) {
                        runRejected(rejected);
                    }
                },
                () -> runRejected(rejected)
        );
    }

    private static void runRejected(Runnable rejected) {
        try {
            rejected.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The caller supplied terminal cleanup and has no further safe world-thread path.
        }
    }

    @Nullable
    <T extends Component<EntityStore>> T safeGetComponent(
            Store<EntityStore> store,
            Ref<EntityStore> reference,
            @Nullable ComponentType<EntityStore, T> componentType
    ) {
        if (componentType == null) {
            return null;
        }
        try {
            return store.getComponent(reference, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException exception) {
            return null;
        }
    }
}
