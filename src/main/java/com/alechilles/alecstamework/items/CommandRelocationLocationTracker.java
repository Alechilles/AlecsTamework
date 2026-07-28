package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Resolves process-local relocation world and position hints.
 */
final class CommandRelocationLocationTracker {
    private final Map<UUID, Vector3d> lastKnownByNpc;
    private final Map<UUID, World> knownWorldByNpc;
    private final CommandRelocationWorldAccess worldAccess;

    CommandRelocationLocationTracker(
            Map<UUID, Vector3d> lastKnownByNpc,
            Map<UUID, World> knownWorldByNpc,
            CommandRelocationWorldAccess worldAccess
    ) {
        this.lastKnownByNpc = Objects.requireNonNull(
                lastKnownByNpc,
                "lastKnownByNpc"
        );
        this.knownWorldByNpc = Objects.requireNonNull(
                knownWorldByNpc,
                "knownWorldByNpc"
        );
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
    }

    /**
     * Seeds a process-local route hint without blocking on unloaded worlds.
     */
    void rememberSourceWorld(
            @Nullable UUID npcUuid,
            @Nullable String worldName
    ) {
        if (npcUuid == null || knownWorldByNpc.containsKey(npcUuid)) {
            return;
        }
        World sourceWorld = worldAccess.resolveLoadedWorld(worldName);
        if (sourceWorld != null) {
            knownWorldByNpc.putIfAbsent(npcUuid, sourceWorld);
        }
    }

    Location resolve(
            @Nullable UUID npcUuid,
            @Nullable Vector3d fallbackPosition,
            @Nullable String fallbackWorldName
    ) {
        Vector3d position = npcUuid == null
                ? null
                : lastKnownByNpc.get(npcUuid);
        World world = npcUuid == null
                ? null
                : knownWorldByNpc.get(npcUuid);
        String worldName = world == null
                ? worldAccess.normalizeWorldName(fallbackWorldName)
                : worldAccess.normalizeWorldName(world.getName());
        return new Location(
                worldName,
                position == null
                        ? worldAccess.copyPosition(fallbackPosition)
                        : new Vector3d(position)
        );
    }

    record Location(
            @Nullable String worldName,
            @Nullable Vector3d position
    ) {
    }
}
