package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.item.CaptureItemFlows;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionReviveSettings;
import com.alechilles.alecstamework.items.persistence.HytaleUuidCompletionDispatcher;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
 * a population refusal that names its own message (an admission provider's denial, a domain
 * limit) shows that message instead. A player who left that world or disconnected gets no
 * message. The owner's open panels are then asked to refresh, so their cards show the restored
 * companion.</p>
 *
 * <p>A revive with a configured item cost takes the exact items from the player's inventory on
 * that same world thread, before the flow starts. Any result other than RESTORED gives the items
 * back (see {@link CommandReviveCostInventory}), so the refund does not depend on the message
 * reaching the player. A revive with no cost, and every recover, skips payment and refund.</p>
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
    private final CommandFeedbackService feedback = new CommandFeedbackService(new TameworkUiMessageService());
    @Nullable
    private volatile CaptureItemFlows captureItemFlows;
    /** Asks the owner's open panels to rebuild their cards; must be safe from any thread. */
    private volatile Consumer<UUID> panelRefresh = owner -> { };

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

    /**
     * Sets what refreshes the owner's open panels when a restore ends. The click that starts a
     * restore refreshes the page before the restore has run, so without this the old card (for
     * example a captured companion on the Stored tab) stays until the next refresh.
     */
    void usePanelRefresh(@Nonnull Consumer<UUID> refresh) {
        panelRefresh = Objects.requireNonNull(refresh, "refresh");
    }

    /** Lets a Recall of a captured companion empty the owner's held copies of its item. */
    void useCaptureItemFlows(@Nullable CaptureItemFlows flows) {
        captureItemFlows = flows;
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
        UUID playerUuid = player.getUuid();
        RestoreFlow.Destination destination = RestoreFlow.Destination.of(placement);
        String name = profile.displayName() != null && !profile.displayName().isBlank()
                ? profile.displayName()
                : profile.customName();
        List<ItemStack> paid = List.of();
        if (reason == RestoreRules.Reason.REVIVE) {
            TwCompanionReviveSettings revive = TwCompanionConfig.resolveEffectiveForRole(roleId).getRevive();
            var cost = revive.getCosts();
            if (cost.length > 0) {
                // The flow would refuse these after payment; refuse them before charging instead.
                RestoreRules.Verdict verdict = RestoreRules.forRecord(
                        companions.get(profileId), reason, System.currentTimeMillis());
                if (verdict != RestoreRules.Verdict.ALLOWED) {
                    RestoreFlow.Result refused = refusal(verdict);
                    completions.dispatch(placement.worldKey(), playerUuid,
                            (currentWorld, currentStore, actorRef, actor) -> listener.complete(refused, actor, name));
                    return RequestStatus.STARTED;
                }
                CommandReviveCostInventory.Charge charge = CommandReviveCostInventory.charge(store, playerRef, cost);
                switch (charge.status()) {
                    case PAID -> paid = charge.paid();
                    case INSUFFICIENT -> {
                        listener.cannotAfford(player, revive.getInsufficientCostMessage());
                        return RequestStatus.STARTED;
                    }
                    case UNAVAILABLE -> {
                        return RequestStatus.UNAVAILABLE;
                    }
                }
            }
        }
        CompletableFuture<RestoreFlow.Outcome> restoring;
        try {
            CaptureItemFlows flows = captureItemFlows;
            // Recall of a captured companion also empties the owner's held copies of its item.
            restoring = reason == RestoreRules.Reason.RECOVER && flows != null
                    ? flows.recallOutcome(profileId, destination)
                    : restoreFlow.restoreOutcome(RestoreFlow.Request.of(profileId, reason, destination));
        } catch (RuntimeException failure) {
            restoring = CompletableFuture.failedFuture(failure);
        }
        List<ItemStack> charged = paid;
        restoring.whenComplete((result, error) -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error).log("Panel " + reason + " of profile=" + profileId
                        + " failed unexpectedly.");
            }
            RestoreFlow.Result outcome = error != null || result == null
                    ? RestoreFlow.Result.COMMIT_FAILED : result.result();
            String refusalKey = error != null || result == null ? null : result.messageKey();
            if (outcome != RestoreFlow.Result.RESTORED) {
                CommandReviveCostInventory.refund(playerUuid, charged);
            }
            completions.dispatch(placement.worldKey(), playerUuid, (currentWorld, currentStore, actorRef, actor) -> {
                if (outcome != RestoreFlow.Result.RESTORED && refusalKey != null) {
                    feedback.showWarningKey(actor, refusalKey);
                } else {
                    listener.complete(outcome, actor, name);
                }
            });
            // The record has its final state now; a refusal can also change what the card shows.
            try {
                panelRefresh.accept(playerUuid);
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Panel refresh after " + reason
                        + " of profile=" + profileId + " failed.");
            }
        });
        return RequestStatus.STARTED;
    }

    @Nonnull
    private static RestoreFlow.Result refusal(@Nonnull RestoreRules.Verdict verdict) {
        return switch (verdict) {
            case NOT_FOUND -> RestoreFlow.Result.NOT_FOUND;
            case NOT_ALLOWED -> RestoreFlow.Result.NOT_ALLOWED;
            case NO_SNAPSHOT -> RestoreFlow.Result.NO_SNAPSHOT;
            case COOLDOWN -> RestoreFlow.Result.COOLDOWN;
            case STALE -> RestoreFlow.Result.STALE;
            case ALLOWED -> RestoreFlow.Result.RESTORED;
        };
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
        // A captured or cooped companion is recovered from its snapshot when its item or coop is out
        // of reach (spec 8.14); the generation bump makes the old item or slot entry stale.
        if (profile.lost() || profile.captured() || profile.inCoop()
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
