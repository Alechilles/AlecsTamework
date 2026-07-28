package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components
        .TameworkCommandLinksComponent;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable live-NPC facts extracted from a component snapshot on the world thread.
 *
 * <p>Only strings, UUIDs, immutable collections, and primitive coordinates survive this
 * boundary. The mutable component graph used to produce these facts must not be retained by
 * asynchronous persistence continuations.</p>
 */
record SpawnerCaptureLiveFacts(
        @Nonnull UUID npcUuid,
        @Nullable String displayName,
        @Nullable String roleId,
        @Nonnull String metadataJson,
        @Nonnull List<UUID> toolIds,
        @Nullable Vector3View homePosition
) {
    SpawnerCaptureLiveFacts {
        if (npcUuid == null || metadataJson == null || toolIds == null) {
            throw new IllegalArgumentException(
                    "Complete frozen live capture facts are required"
            );
        }
        displayName = normalize(displayName);
        roleId = normalize(roleId);
        toolIds = List.copyOf(toolIds);
    }

    /**
     * Reads every component-derived adoption and event value before async work can begin.
     */
    @Nonnull
    static SpawnerCaptureLiveFacts freeze(
            @Nonnull CoopResidentStateSnapshot snapshot
    ) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            throw new IllegalArgumentException(
                    "Complete live component snapshot is required"
            );
        }
        TameworkCommandLinksComponent links = snapshot.commandLinks();
        return new SpawnerCaptureLiveFacts(
                snapshot.npcUuid(),
                snapshot.npcName() == null
                        ? null : snapshot.npcName().getName(),
                snapshot.roleId(),
                metadata(snapshot),
                toolIds(links),
                home(links)
        );
    }

    @Nonnull
    private static String metadata(CoopResidentStateSnapshot snapshot) {
        JsonObject metadata = new JsonObject();
        if (snapshot.owner() != null
                && snapshot.owner().getOwnerName() != null) {
            metadata.addProperty(
                    "owner_name", snapshot.owner().getOwnerName()
            );
        }
        if (snapshot.npcName() != null
                && snapshot.npcName().getName() != null) {
            metadata.addProperty(
                    "custom_name", snapshot.npcName().getName()
            );
        }
        metadata.addProperty(
                "tamed",
                snapshot.tamed() != null && snapshot.tamed().isTamed()
        );
        return metadata.toString();
    }

    @Nonnull
    private static List<UUID> toolIds(
            @Nullable TameworkCommandLinksComponent links
    ) {
        if (links == null || links.getToolIds() == null) {
            return List.of();
        }
        ArrayList<UUID> parsed = new ArrayList<>();
        for (String value : links.getToolIds()) {
            try {
                parsed.add(UUID.fromString(value));
            } catch (RuntimeException ignored) {
                // Invalid legacy text is not canonical link evidence.
            }
        }
        return parsed.stream().distinct().sorted().toList();
    }

    @Nullable
    private static Vector3View home(
            @Nullable TameworkCommandLinksComponent links
    ) {
        return links == null || !links.hasHome()
                ? null
                : new Vector3View(
                        links.getHomeX(),
                        links.getHomeY(),
                        links.getHomeZ()
                );
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
