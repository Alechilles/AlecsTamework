package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Encapsulates relocation world-thread dispatch and cross-world entity access primitives. */
final class CommandRelocationWorldAccess {
    private static final int CHUNK_SIZE = 32;
    private final Map<UUID, World> knownWorldByNpc;
    private final BiConsumer<Level, String> diagnostic;

    CommandRelocationWorldAccess(Map<UUID, World> knownWorldByNpc,
                                 BiConsumer<Level, String> diagnostic) {
        this.knownWorldByNpc = knownWorldByNpc;
        this.diagnostic = diagnostic;
    }

    boolean isSameWorld(@Nullable World left, @Nullable World right) {
        if (left == null || right == null) {
            return false;
        }
        if (left == right) {
            return true;
        }
        String leftName = left.getName();
        String rightName = right.getName();
        return leftName != null && leftName.equals(rightName);
    }

    boolean isEntityPresent(@Nullable World world, @Nullable UUID npcUuid) {
        if (world == null || npcUuid == null) {
            return false;
        }
        Ref<EntityStore> ref = world.getEntityRef(npcUuid);
        Store<EntityStore> store = world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        return ref != null && ref.isValid() && store != null
                && safeGetComponent(store, ref, NPCEntity.getComponentType()) != null;
    }

    boolean isUnsafeTransferState(@Nullable Store<EntityStore> sourceStore,
                                  @Nullable Ref<EntityStore> sourceRef,
                                  @Nullable NPCEntity sourceNpc) {
        if (sourceStore == null || sourceRef == null || !sourceRef.isValid() || sourceNpc == null
                || sourceNpc.getRole() == null) {
            return true;
        }
        ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
        return mountType != null && safeGetComponent(sourceStore, sourceRef, mountType) != null;
    }

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

    @Nullable
    Vector3d copyPosition(@Nullable Vector3d position) {
        return position == null ? null : new Vector3d(position);
    }

    boolean restoreSourceEntity(World sourceWorld,
                                @Nullable Store<EntityStore> sourceStore,
                                @Nullable Holder<EntityStore> drainedHolder,
                                @Nullable UUID npcUuid) {
        if (sourceWorld == null || sourceStore == null || drainedHolder == null) {
            return false;
        }
        if (npcUuid != null && isEntityPresent(sourceWorld, npcUuid)) {
            knownWorldByNpc.put(npcUuid, sourceWorld);
            return true;
        }
        try {
            Ref<EntityStore> restored = sourceStore.addEntity(drainedHolder, AddReason.SPAWN);
            boolean present = npcUuid == null
                    ? restored != null && restored.isValid()
                    : isEntityPresent(sourceWorld, npcUuid);
            if (npcUuid != null && present) {
                knownWorldByNpc.put(npcUuid, sourceWorld);
            }
            return present;
        } catch (Exception | LinkageError exception) {
            if (npcUuid != null && isEntityPresent(sourceWorld, npcUuid)) {
                knownWorldByNpc.put(npcUuid, sourceWorld);
                return true;
            }
            diagnostic.accept(
                    Level.WARNING,
                    "Cross-world restore skipped for npc=" + npcUuid
                            + ", sourceWorld=" + sourceWorld.getName()
                            + ", reason=" + exception.getClass().getSimpleName()
                            + ": " + exception.getMessage()
            );
            return false;
        }
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
    private static <T extends Component<EntityStore>> T safeGetComponent(
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
