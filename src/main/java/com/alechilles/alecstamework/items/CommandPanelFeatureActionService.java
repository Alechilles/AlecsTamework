package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalResult;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors command-panel summon, dismiss, and paid revival mutations through
 * replacement public APIs.
 */
final class CommandPanelFeatureActionService {
    private static final String PAID_REVIVAL_CALLER =
            "Alechilles:Tamework:CommandPanel";

    private final CommandPanelFeaturePresentationSource presentations;
    private final Supplier<CommandTimedSummoningApi> timedSummoning;
    private final Supplier<PaidCommandRevivalApi> paidRevival;
    private final CommandFeedbackService feedback;

    CommandPanelFeatureActionService(
            @Nonnull CommandPanelFeaturePresentationSource presentations,
            @Nonnull CommandTimedSummoningApi timedSummoning,
            @Nonnull PaidCommandRevivalApi paidRevival,
            @Nonnull CommandFeedbackService feedback
    ) {
        this(
                presentations,
                () -> timedSummoning,
                () -> paidRevival,
                feedback
        );
    }

    CommandPanelFeatureActionService(
            @Nonnull CommandPanelFeaturePresentationSource presentations,
            @Nonnull Supplier<CommandTimedSummoningApi> timedSummoning,
            @Nonnull Supplier<PaidCommandRevivalApi> paidRevival,
            @Nonnull CommandFeedbackService feedback
    ) {
        this.presentations = Objects.requireNonNull(
                presentations, "Presentation source is required"
        );
        this.timedSummoning = Objects.requireNonNull(
                timedSummoning, "Timed summon API is required"
        );
        this.paidRevival = Objects.requireNonNull(
                paidRevival, "Paid revival API is required"
        );
        this.feedback = Objects.requireNonNull(
                feedback, "Feedback service is required"
        );
    }

    void summon(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        transition(player, config, presentationUuid, true);
    }

    void dismiss(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        transition(player, config, presentationUuid, false);
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

    private void transition(
            Player player,
            TwCommandItemConfig config,
            UUID presentationUuid,
            boolean summon
    ) {
        ActionContext context = context(
                player, config, presentationUuid
        );
        if (context == null || !transitionAllowed(context, summon)) {
            warn(player);
            return;
        }
        String action = summon ? "summon" : "dismiss";
        CommandTimedSummoningRequest request =
                new CommandTimedSummoningRequest(
                        context.ownerUuid(),
                        context.familyId(),
                        context.member().profileId(),
                        "command-panel:" + action + ":"
                                + context.member().profileId() + ":"
                                + context.presentation().roster().revision()
                );
        try {
            CommandTimedSummoningApi current = currentTimedSummoning();
            var stage = summon
                    ? current.summon(request)
                    : current.dismiss(request);
            if (stage == null) {
                warn(player);
                return;
            }
            stage.whenComplete((result, failure) ->
                    reportTimed(context, result, failure));
        } catch (RuntimeException | LinkageError failure) {
            warn(player);
        }
    }

    @Nullable
    private ActionContext context(
            @Nullable Player player,
            @Nullable TwCommandItemConfig config,
            @Nullable UUID presentationUuid
    ) {
        WorldPlayerResolver.ResolvedPlayer resolved = player == null
                ? null
                : WorldPlayerResolver.resolveCurrent(player);
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
                || !familyId.equals(
                member.view().membership().familyKey().familyId()
        )) {
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

    private void reportTimed(
            ActionContext context,
            CommandTimedSummoningResult result,
            Throwable failure
    ) {
        boolean succeeded = failure == null && result != null
                && (result.status()
                == CommandTimedSummoningResult.Status.SUCCESS
                || result.status()
                == CommandTimedSummoningResult.Status.IDEMPOTENT);
        if (!succeeded) {
            warn(context);
        }
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

    private boolean transitionAllowed(
            ActionContext context,
            boolean summon
    ) {
        return summon
                ? context.presentation().roster().summonEnabled()
                : context.presentation().roster().dismissEnabled();
    }

    private String revivalIdempotencyKey(ActionContext context) {
        return "command-panel:revive:"
                + context.member().profileId() + ":"
                + context.presentation().roster().revision() + ":"
                + context.presentation().revival().configRevision();
    }

    private CommandTimedSummoningApi currentTimedSummoning() {
        return resolve(
                timedSummoning, CommandTimedSummoningApi.unavailable()
        );
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
        context.world().execute(() -> {
            WorldPlayerResolver.ResolvedPlayer live =
                    WorldPlayerResolver.resolve(
                            context.world(), context.ownerUuid()
                    );
            if (live != null) {
                warn(live.player());
            }
        });
    }

    private void warn(Player player) {
        if (player != null) {
            feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.persistence.authorityNotReady"
            );
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
