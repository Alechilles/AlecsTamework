package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalResult;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RosterSummons;
import com.alechilles.alecstamework.companion.flow.StoreFlow;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs the command panel's roster actions: Summon and Dismiss through {@link RosterSummons} on the
 * companion index, and paid revival through the revival API.
 *
 * <p>Every entry point runs on the player's world thread. Summon places the companion near the
 * player as Recover does; flow outcomes come back on another thread and are shown on the panel's
 * world thread to the owner, if the owner is still there.</p>
 */
final class CommandPanelFeatureActionService {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String PAID_REVIVAL_CALLER =
            "Alechilles:Tamework:CommandPanel";
    private static final String AUTHORITY_NOT_READY =
            "tamework.ui.notifications.persistence.authorityNotReady";
    private static final String ROSTER_KEYS = "tamework.ui.notifications.command.roster.";

    private final CommandPanelFeaturePresentationSource presentations;
    private final Supplier<RosterSummons> summons;
    private final Supplier<PaidCommandRevivalApi> paidRevival;
    private final CommandCompanionPlacementService placements;
    private final double safeSpawnDistance;
    private final CommandFeedbackService feedback;

    /**
     * @param summons           the roster summon flow; null (or a null supply) when the companion
     *                          index is not ready, and Summon and Dismiss then report that
     * @param safeSpawnDistance how far from the player a summoned companion may be placed
     */
    CommandPanelFeatureActionService(
            @Nonnull CommandPanelFeaturePresentationSource presentations,
            @Nonnull Supplier<RosterSummons> summons,
            @Nonnull Supplier<PaidCommandRevivalApi> paidRevival,
            @Nonnull CommandCompanionPlacementService placements,
            double safeSpawnDistance,
            @Nonnull CommandFeedbackService feedback
    ) {
        this.presentations = Objects.requireNonNull(
                presentations, "Presentation source is required"
        );
        this.summons = Objects.requireNonNull(summons, "Roster summons are required");
        this.paidRevival = Objects.requireNonNull(
                paidRevival, "Paid revival API is required"
        );
        this.placements = Objects.requireNonNull(placements, "Placement service is required");
        this.safeSpawnDistance = safeSpawnDistance;
        this.feedback = Objects.requireNonNull(
                feedback, "Feedback service is required"
        );
    }

    /** Summons a stored member next to the player. */
    void summon(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        WorldPlayerResolver.ResolvedPlayer resolved = player == null
                ? null : WorldPlayerResolver.resolveCurrent(player);
        ActionContext context = context(resolved, config, presentationUuid);
        RosterSummons flow = currentSummons();
        if (context == null || flow == null
                || !context.presentation().roster().summonVisible()) {
            warn(player);
            return;
        }
        World world = context.world();
        if (CompanionDestinationAdmissionPolicy.assess(world)
                == CompanionDestinationAdmissionPolicy.Decision.NPCS_FROZEN) {
            feedback.showWarningKey(resolved.player(),
                    "tamework.ui.notifications.command.destination.npcsFrozen");
            return;
        }
        CompanionSpawnPlacement placement = placements.computeRestorationPlacement(
                resolved.ref(), resolved.store(), safeSpawnDistance,
                context.member().roleId(), null);
        if (placement == null) {
            feedback.showWarningKey(resolved.player(), ROSTER_KEYS + "summonFailed");
            return;
        }
        UUID profileId = context.member().record().profileId();
        long generation = context.member().record().generation();
        CompletableFuture<RestoreFlow.Result> started;
        try {
            started = flow.summon(profileId, generation, RestoreFlow.Destination.of(placement));
        } catch (RuntimeException failure) {
            started = CompletableFuture.failedFuture(failure);
        }
        started.whenComplete((result, failure) -> {
            if (failure != null) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Panel summon of companion %s failed", profileId);
            }
            String key = summonFailureKey(failure != null || result == null
                    ? RestoreFlow.Result.COMMIT_FAILED : result);
            if (key != null) {
                warnLater(context, key);
            }
        });
    }

    /** Returns a summoned member to the roster. */
    void dismiss(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        ActionContext context = context(player, config, presentationUuid);
        RosterSummons flow = currentSummons();
        if (context == null || flow == null
                || !context.presentation().roster().dismissEnabled()) {
            warn(player);
            return;
        }
        UUID profileId = context.member().record().profileId();
        CompletableFuture<StoreFlow.Result> started;
        try {
            started = flow.store(profileId);
        } catch (RuntimeException failure) {
            started = CompletableFuture.failedFuture(failure);
        }
        started.whenComplete((result, failure) -> {
            if (failure != null) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Panel dismiss of companion %s failed", profileId);
            }
            // CONFLICT: another store of this companion won (a double click); nothing to report.
            if (failure != null || result != StoreFlow.Result.STORED && result != StoreFlow.Result.CONFLICT) {
                warnLater(context, ROSTER_KEYS + "dismissFailed");
            }
        });
    }

    /**
     * The warning for a summon outcome; null when it succeeded or lost to a concurrent summon of the
     * same companion (CONFLICT, for example a double click).
     */
    @Nullable
    static String summonFailureKey(@Nonnull RestoreFlow.Result result) {
        return switch (result) {
            case RESTORED, CONFLICT -> null;
            case COOLDOWN -> "tamework.ui.notifications.command.shared.cooldown";
            case OWNED_LIMIT -> "tamework.ui.population.ownedLimit";
            case GROUP_LIMIT -> "tamework.ui.population.groupLimit";
            default -> ROSTER_KEYS + "summonFailed";
        };
    }

    void revive(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        ActionContext context = context(
                player, config, presentationUuid
        );
        if (context == null
                || !context.presentation().managesPaidRevival()
                || context.presentation().revival() == null
                || !context.presentation().revival().confirmEnabled()) {
            warn(player);
            return;
        }
        PaidCommandRevivalRequest request =
                new PaidCommandRevivalRequest(
                        PAID_REVIVAL_CALLER,
                        revivalIdempotencyKey(context),
                        context.ownerUuid(),
                        context.member().profileId(),
                        context.familyId()
                );
        try {
            var stage = currentPaidRevival().revive(request);
            if (stage == null) {
                warn(player);
                return;
            }
            stage.whenComplete((result, failure) ->
                    reportRevival(context, result, failure));
        } catch (RuntimeException | LinkageError failure) {
            warn(player);
        }
    }

    @Nullable
    private RosterSummons currentSummons() {
        try {
            return summons.get();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private ActionContext context(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        return context(player == null ? null : WorldPlayerResolver.resolveCurrent(player),
                config, presentationUuid);
    }

    /** The checked row for the player's action; it holds no live component, so completions may carry it. */
    @Nullable
    private ActionContext context(
            @Nullable WorldPlayerResolver.ResolvedPlayer resolved,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        if (resolved == null) {
            return null;
        }
        UUID ownerUuid = resolved.player().getUuid();
        String familyId = config == null
                ? null
                : normalize(config.getCommandFamilyId());
        if (ownerUuid == null || familyId == null
                || !config.usesOwnerCommandFamilyRoster()) {
            return null;
        }
        CommandRosterPanelRecordSource.PanelMember member =
                presentations.resolveMember(
                        ownerUuid, config, presentationUuid
                );
        CommandPanelFeaturePresentation presentation =
                presentations.presentation(
                        ownerUuid,
                        resolved.world().getName(),
                        config,
                        presentationUuid
                );
        if (member == null || presentation == null
                || !familyId.equals(member.record().rosterId())
                || !ownerUuid.equals(member.record().ownerUuid())) {
            return null;
        }
        return new ActionContext(
                ownerUuid,
                familyId,
                member,
                presentation,
                resolved.world()
        );
    }

    private void reportRevival(
            ActionContext context,
            PaidCommandRevivalResult result,
            Throwable failure
    ) {
        if (failure != null || result == null || !result.succeeded()) {
            warn(context);
        }
    }

    private String revivalIdempotencyKey(ActionContext context) {
        return "command-panel:revive:"
                + context.member().profileId() + ":"
                + context.presentation().roster().revision() + ":"
                + context.presentation().revival().configRevision();
    }

    private PaidCommandRevivalApi currentPaidRevival() {
        return resolve(paidRevival, PaidCommandRevivalApi.unavailable());
    }

    private static <T> T resolve(Supplier<T> source, T unavailable) {
        try {
            T resolved = source.get();
            return resolved == null ? unavailable : resolved;
        } catch (RuntimeException | LinkageError ignored) {
            return unavailable;
        }
    }

    private void warn(ActionContext context) {
        warnLater(context, AUTHORITY_NOT_READY);
    }

    /** Shows {@code key} to the owner on the panel's world thread, if the owner is still there. */
    private void warnLater(ActionContext context, String key) {
        context.world().execute(() -> {
            WorldPlayerResolver.ResolvedPlayer live =
                    WorldPlayerResolver.resolve(
                            context.world(), context.ownerUuid()
                    );
            if (live != null) {
                feedback.showWarningKey(live.player(), key);
            }
        });
    }

    private void warn(Player player) {
        if (player != null) {
            feedback.showWarningKey(player, AUTHORITY_NOT_READY);
        }
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ActionContext(
            @Nonnull UUID ownerUuid,
            @Nonnull String familyId,
            @Nonnull CommandRosterPanelRecordSource.PanelMember member,
            @Nonnull CommandPanelFeaturePresentation presentation,
            @Nonnull World world
    ) {
        ActionContext {
            Objects.requireNonNull(ownerUuid, "Owner is required");
            familyId = Objects.requireNonNull(
                    normalize(familyId), "Family is required"
            );
            Objects.requireNonNull(member, "Member is required");
            Objects.requireNonNull(
                    presentation, "Presentation is required"
            );
            Objects.requireNonNull(world, "World is required");
        }
    }
}
