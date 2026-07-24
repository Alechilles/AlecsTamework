package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.items.persistence.FreeCompanionRestorationAuthor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Translates one released command-panel respawn action into a canonical restoration intent.
 *
 * <p>All restoration state, alias rotation, spawning, and publication remain owned by
 * {@link FreeCompanionRestorationAuthor}. This service only freezes world-thread placement and
 * stable command identity.</p>
 */
final class CommandCompanionRestorationService {
    enum RequestStatus {
        STARTED,
        UNAVAILABLE,
        INVALID_CONTEXT,
        NOT_DORMANT
    }

    private final CommandCompanionPlacementService placements;
    private final CommandPersistenceView persistence;
    private final FreeCompanionRestorationAuthor author;

    CommandCompanionRestorationService(
            @Nonnull CommandCompanionPlacementService placements,
            @Nonnull CommandPersistenceView persistence,
            @Nonnull FreeCompanionRestorationAuthor author
    ) {
        this.placements = Objects.requireNonNull(
                placements, "Command placement service is required"
        );
        this.persistence = Objects.requireNonNull(
                persistence, "Command persistence view is required"
        );
        this.author = Objects.requireNonNull(
                author, "Restoration author is required"
        );
    }

    @Nonnull
    RequestStatus request(
            @Nullable Player player,
            @Nullable Ref<EntityStore> playerRef,
            @Nullable Store<EntityStore> store,
            @Nullable String toolId,
            @Nullable LinkedNpcRecord record,
            double safeSpawnDistance
    ) {
        World world = player == null ? null : player.getWorld();
        if (player == null || player.getUuid() == null
                || playerRef == null || !playerRef.isValid()
                || store == null || world == null
                || toolId == null || toolId.isBlank()
                || record == null || record.npcUuid == null) {
            return RequestStatus.INVALID_CONTEXT;
        }
        CommandPersistenceView.ProfileSnapshot profile =
                persistence.find(record).orElse(null);
        if (profile != null && !profile.restorable()) {
            return RequestStatus.NOT_DORMANT;
        }
        ProfileId profileId = profile != null
                ? profile.profileId()
                : persistence.profileId(record);
        if (profileId == null) {
            return RequestStatus.INVALID_CONTEXT;
        }
        String roleId = profile != null && profile.roleId() != null
                ? profile.roleId()
                : record.cachedRoleId;
        CompanionSpawnPlacement placement =
                placements.computeRestorationPlacement(
                        playerRef,
                        store,
                        safeSpawnDistance,
                        roleId,
                        record.lastKnownPosition
                );
        if (placement == null) {
            return RequestStatus.INVALID_CONTEXT;
        }
        FreeCompanionRestorationAuthor.Intent intent =
                new FreeCompanionRestorationAuthor.Intent(
                        intentKey(toolId, profileId, record),
                        player.getUuid(),
                        placement.worldKey(),
                        profileId,
                        placement
                );
        try {
            author.restore(intent);
            return RequestStatus.STARTED;
        } catch (RuntimeException | LinkageError ignored) {
            return RequestStatus.UNAVAILABLE;
        }
    }

    private String intentKey(
            String toolId,
            ProfileId profileId,
            LinkedNpcRecord record
    ) {
        return "command-free-restoration:"
                + toolId.trim()
                + ":" + profileId
                + ":" + record.npcUuid;
    }
}
