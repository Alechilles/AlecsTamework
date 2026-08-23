package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/** Routes only explicitly bonded command-item rows to bonded mutations. */
final class BondedCompanionPanelActionRouter {
    private final BondedCompanionPanelActionService actions;
    private final CommandFeedbackService feedback;
    private final HytaleBondedCompanionActionContextFactory contexts;
    private final BiConsumer<UUID, String> refresh;
    private final CurrentPlayerResolver players;

    BondedCompanionPanelActionRouter(BondedCompanionPanelActionService actions,
                                     CommandFeedbackService feedback) {
        this(actions, feedback,
                new HytaleBondedCompanionActionContextFactory(), (owner, roster) -> { },
                BondedCompanionPanelActionRouter::resolvePlayerFromEvent);
    }

    BondedCompanionPanelActionRouter(BondedCompanionPanelActionService actions,
                                     CommandFeedbackService feedback,
                                     HytaleBondedCompanionActionContextFactory contexts) {
        this(actions, feedback, contexts, (owner, roster) -> { },
                BondedCompanionPanelActionRouter::resolvePlayerFromEvent);
    }

    BondedCompanionPanelActionRouter(BondedCompanionPanelActionService actions,
                                     CommandFeedbackService feedback,
                                     HytaleBondedCompanionActionContextFactory contexts,
                                     BiConsumer<UUID, String> refresh) {
        this(actions, feedback, contexts, refresh,
                BondedCompanionPanelActionRouter::resolvePlayerFromEvent);
    }

    BondedCompanionPanelActionRouter(BondedCompanionPanelActionService actions,
                                     CommandFeedbackService feedback,
                                     HytaleBondedCompanionActionContextFactory contexts,
                                     BiConsumer<UUID, String> refresh,
                                     CurrentPlayerResolver players) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.players = Objects.requireNonNull(players, "players");
    }

    static BondedCompanionPanelActionRouter production(
            CommandFeedbackService feedback,
            java.util.function.Supplier<BondedCompanionApi> api,
            BondedCompanionPanelEntrySourceService readModel) {
        return new BondedCompanionPanelActionRouter(
                new BondedCompanionPanelActionService(api == null
                        ? BondedCompanionApi::unavailable : api), feedback,
                new HytaleBondedCompanionActionContextFactory(),
                readModel == null ? (owner, roster) -> { } : readModel::refresh);
    }

    void route(Player player, TwCommandItemConfig config,
               CommandPanelFeaturePresentation feature,
               BondedCompanionPanelActionService.Action action) {
        route(player, player == null || player.getReference() == null
                        ? null : player.getReference().getStore(),
                config, feature, action);
    }

    void route(Player player, Store<EntityStore> store,
               TwCommandItemConfig config,
               CommandPanelFeaturePresentation feature,
               BondedCompanionPanelActionService.Action action) {
        route(player, store, config, feature, action, ignored -> true);
    }

    /** Applies a bonded lifecycle action only while its physical authority holds. */
    void route(Player player, Store<EntityStore> store,
               TwCommandItemConfig config,
               CommandPanelFeaturePresentation feature,
               BondedCompanionPanelActionService.Action action,
               CommandSelectionPageService.BondedLifecycleAuthority lifecycleAuthority) {
        route(player == null ? null : player.getUuid(),
                player == null ? null : player.getReference(), store, config,
                feature, action, lifecycleAuthority);
    }

    /**
     * Routes a page event using only its stable owner identity and the event's
     * current entity-store context. The page may outlive a world transfer, so
     * callers must not substitute the player or store that originally opened it.
     */
    void route(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
               Store<EntityStore> eventStore, TwCommandItemConfig config,
               CommandPanelFeaturePresentation feature,
               BondedCompanionPanelActionService.Action action,
               CommandSelectionPageService.BondedLifecycleAuthority lifecycleAuthority) {
        if (ownerUuid == null || config == null
                || !config.usesBondedCompanionRoster()
                || feature == null || feature.bonded() == null) return;
        Player player = players.resolve(
                ownerUuid, eventPlayerRef, eventStore);
        if (player == null) return;
        if (lifecycleAuthority == null || !lifecycleAuthority.allows(player)) return;
        String world = player.getWorld() == null ? null : player.getWorld().getName();
        var context = action == BondedCompanionPanelActionService.Action.ABANDON
                ? null : contexts.create(
                        player, eventStore, feature.bonded().roleId(),
                        action == BondedCompanionPanelActionService.Action.SUMMON);
        var outcome = actions.performAsync(
                action, ownerUuid, world, context, feature.bonded());
        var owningWorld = player.getWorld();
        outcome.whenComplete((resolved, failure) -> {
            if (failure == null && resolved != null && resolved.applied()) return;
            refresh.accept(ownerUuid, feature.bonded().rosterId());
            BondedCompanionActionBlockReason reason = resolved == null
                    ? BondedCompanionActionBlockReason.GENERIC_FAILURE
                    : resolved.blockReason();
            if (owningWorld == null || ownerUuid == null) return;
            try {
                owningWorld.execute(() -> showWarning(
                        owningWorld, eventStore, ownerUuid, reason));
            } catch (RuntimeException | LinkageError ignored) {
                // A changed world is already an unavailable action context.
            }
        });
    }

    /**
     * Routes one opaque command-UI action through the existing bonded service
     * and returns its durable outcome instead of inferring success from the
     * legacy page callback.
     */
    CompletionStage<CommandUiActionResult> routeForUi(
            UUID ownerUuid,
            Ref<EntityStore> eventPlayerRef,
            Store<EntityStore> eventStore,
            TwCommandItemConfig config,
            CommandPanelFeaturePresentation feature,
            BondedCompanionPanelActionService.Action action,
            CommandSelectionPageService.BondedLifecycleAuthority lifecycleAuthority
    ) {
        if (ownerUuid == null || config == null
                || !config.usesBondedCompanionRoster()) {
            return completed(CommandUiActionResult.unavailable(
                    "bonded action route is unavailable"));
        }
        if (feature == null || feature.bonded() == null) {
            return completed(CommandUiActionResult.notFound(
                    "bonded companion is unavailable"));
        }
        Player player = players.resolve(ownerUuid, eventPlayerRef, eventStore);
        if (player == null || lifecycleAuthority == null
                || !lifecycleAuthority.allows(player)) {
            return completed(CommandUiActionResult.denied(
                    "current bonded authority denied the action"));
        }
        String world = player.getWorld() == null
                ? null : player.getWorld().getName();
        var presentation = feature.bonded();
        BondedCompanionActionContext context;
        try {
            context = action == BondedCompanionPanelActionService.Action.ABANDON
                    ? null : contexts.create(player, eventStore, presentation.roleId(),
                            action == BondedCompanionPanelActionService.Action.SUMMON);
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "bonded action context failed"));
        }
        CompletionStage<BondedCompanionPanelActionService.Outcome> outcome;
        try {
            outcome = actions.performAsync(action, ownerUuid, world, context,
                    presentation);
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "bonded action failed"));
        }
        if (outcome == null) {
            return completed(CommandUiActionResult.failed(
                    "bonded action returned no result"));
        }
        return outcome.handle((resolved, failure) -> {
            if (failure != null || resolved == null) {
                return CommandUiActionResult.failed("bonded action failed");
            }
            return toUiResult(resolved);
        });
    }

    private static CommandUiActionResult toUiResult(
            BondedCompanionPanelActionService.Outcome outcome) {
        if (outcome.applied()) return CommandUiActionResult.applied();
        BondedCompanionActionBlockReason reason = outcome.blockReason();
        if (reason == BondedCompanionActionBlockReason.NOT_FOUND) {
            return CommandUiActionResult.notFound("bonded companion is unavailable");
        }
        if (reason == BondedCompanionActionBlockReason.REVISION_CONFLICT) {
            return CommandUiActionResult.conflict("bonded presentation is stale");
        }
        if (reason == BondedCompanionActionBlockReason.WORLD_UNAVAILABLE
                || reason == BondedCompanionActionBlockReason.AUTHORITY_UNAVAILABLE) {
            return CommandUiActionResult.unavailable("bonded action is unavailable");
        }
        return CommandUiActionResult.denied("bonded action was denied");
    }

    private static CompletionStage<CommandUiActionResult> completed(
            CommandUiActionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    static Player resolvePlayerFromEvent(
            UUID ownerUuid, Ref<EntityStore> playerRef,
            Store<EntityStore> store
    ) {
        if (ownerUuid == null || playerRef == null || !playerRef.isValid()
                || store == null || playerRef.getStore() != store) return null;
        try {
            store.assertThread();
            if (Player.getComponentType() == null) return null;
            Player player = store.getComponent(playerRef,
                    Player.getComponentType());
            if (player == null || !ownerUuid.equals(player.getUuid())
                    || player.getReference() != playerRef
                    || player.getWorld() == null
                    || player.getWorld().getEntityStore().getStore() != store) {
                return null;
            }
            return player;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @FunctionalInterface
    interface CurrentPlayerResolver {
        Player resolve(UUID ownerUuid, Ref<EntityStore> playerRef,
                       Store<EntityStore> store);
    }

    private void showWarning(
            com.hypixel.hytale.server.core.universe.world.World world,
            Store<EntityStore> store,
            UUID ownerUuid,
            BondedCompanionActionBlockReason reason
    ) {
        try {
            store.assertThread();
            var playerRef = world.getEntityRef(ownerUuid);
            if (playerRef == null || !playerRef.isValid()
                    || playerRef.getStore() != store
                    || Player.getComponentType() == null) return;
            Player live = store.getComponent(
                    playerRef, Player.getComponentType());
            if (live != null) feedback.showWarningKey(live,
                    BondedCompanionActionFeedbackMapper.localizationKey(
                            reason == null
                                    ? BondedCompanionActionBlockReason.GENERIC_FAILURE
                                    : reason));
        } catch (RuntimeException | LinkageError ignored) {
            // Feedback is best-effort after the durable mutation result.
        }
    }
}
