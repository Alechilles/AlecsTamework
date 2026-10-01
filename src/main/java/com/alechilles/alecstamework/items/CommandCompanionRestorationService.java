package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.items.persistence.HytaleUuidCompletionDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Starts the restore behind the panel's Revive (DEAD) and Recover (LOST, or LIVE with no loaded
 * body) buttons.
 *
 * <p>{@link #request} runs on the player's world thread. It checks the destination's frozen-NPC
 * state, the owner, whether revive is turned on and the companion's state, then freezes a
 * placement near the player and hands it to {@link RestoreFlow}. The flow re-checks the record and
 * hands its entity work to the world threads itself. Its outcome returns to the player on the
 * world thread the placement was taken in, through {@link CommandRestorationCompletionListener};
 * a player who left that world or disconnected gets no message.</p>
 */
final class CommandCompanionRestorationService {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    enum RequestStatus {
        /** The completion listener answers the player. */
        STARTED,
        REVIVE_DISABLED,
        UNAVAILABLE,
        INVALID_CONTEXT,
        NOT_DORMANT,
        DESTINATION_NPCS_FROZEN
    }

    /** What the button does for one companion. */
    enum Decision {
        REVIVE,
        RECOVER,
        REVIVE_DISABLED,
        NOT_DORMANT,
        UNAVAILABLE
    }

    private final CommandCompanionPlacementService placements;
    private final CommandPersistenceView persistence;
    private final RestoreFlow<Ref<EntityStore>> restoreFlow;
    private final CompanionQueries companions;
    private final HytaleUuidCompletionDispatcher completions = new HytaleUuidCompletionDispatcher();
    private final CommandRestorationCompletionListener listener = new CommandRestorationCompletionListener();

    CommandCompanionRestorationService(
            @Nonnull CommandCompanionPlacementService placements,
            @Nonnull CommandPersistenceView persistence,
            @Nonnull RestoreFlow<Ref<EntityStore>> restoreFlow,
            @Nonnull CompanionQueries companions
    ) {
        this.placements = Objects.requireNonNull(
                placements, "Command placement service is required"
        );
        this.persistence = Objects.requireNonNull(
                persistence, "Command persistence view is required"
        );
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "Restore flow is required");
        this.companions = Objects.requireNonNull(companions, "Companion queries are required");
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
        if (CompanionDestinationAdmissionPolicy.assess(world)
                == CompanionDestinationAdmissionPolicy.Decision.NPCS_FROZEN) {
            return RequestStatus.DESTINATION_NPCS_FROZEN;
        }
        CommandPersistenceView.ProfileSnapshot profile =
                persistence.find(record).orElse(null);
        if (profile == null) {
            return RequestStatus.UNAVAILABLE;
        }
        UUID profileId = profile.profileId().value();
        String roleId = profile.roleId() != null
                ? profile.roleId()
                : record.cachedRoleId;
        RestoreRules.Reason reason;
        switch (decide(profile, player.getUuid(), companions.loadedBody(profileId) != null,
                profile.dead() && CompanionRevivePolicy.featureEnabled(roleId))) {
            case REVIVE -> reason = RestoreRules.Reason.REVIVE;
            case RECOVER -> reason = RestoreRules.Reason.RECOVER;
            case REVIVE_DISABLED -> {
                return RequestStatus.REVIVE_DISABLED;
            }
            case NOT_DORMANT -> {
                return RequestStatus.NOT_DORMANT;
            }
            default -> {
                return RequestStatus.UNAVAILABLE;
            }
        }
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
        RestoreFlow.Destination destination = new RestoreFlow.Destination(placement.worldKey(),
                placement.x(), placement.y(), placement.z(), placement.yawRadians(), placement.pitchRadians());
        String name = profile.displayName() != null && !profile.displayName().isBlank()
                ? profile.displayName()
                : profile.customName();
        UUID playerUuid = player.getUuid();
        restoreFlow.restore(profileId, reason, destination).whenComplete((result, error) -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error).log("Panel " + reason + " of profile=" + profileId
                        + " failed unexpectedly.");
            }
            RestoreFlow.Result outcome = error != null || result == null ? RestoreFlow.Result.COMMIT_FAILED : result;
            completions.dispatch(placement.worldKey(), playerUuid,
                    (currentWorld, currentStore, actorRef, actor) -> listener.complete(outcome, actor, name));
        });
        return RequestStatus.STARTED;
    }

    /**
     * Picks what the button does. {@code reviveEnabled} only matters for a dead companion; a live
     * one is recovered only when no body of it is loaded.
     */
    @Nonnull
    static Decision decide(
            @Nonnull CommandPersistenceView.ProfileSnapshot profile,
            @Nonnull UUID playerUuid,
            boolean bodyLoaded,
            boolean reviveEnabled
    ) {
        if (!playerUuid.equals(profile.ownerUuid())) {
            return Decision.UNAVAILABLE;
        }
        if (profile.dead()) {
            return reviveEnabled ? Decision.REVIVE : Decision.REVIVE_DISABLED;
        }
        if (profile.lost()
                || profile.lifecycleState() == LifecycleState.ACTIVE && !bodyLoaded) {
            return Decision.RECOVER;
        }
        if (profile.lifecycleState() == LifecycleState.RELEASED
                || profile.lifecycleState() == LifecycleState.UNRESOLVED) {
            return Decision.UNAVAILABLE;
        }
        return Decision.NOT_DORMANT;
    }
}
