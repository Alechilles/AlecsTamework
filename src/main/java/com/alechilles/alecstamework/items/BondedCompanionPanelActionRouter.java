package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Routes only explicitly bonded command-item rows to bonded mutations. */
final class BondedCompanionPanelActionRouter {
    private final BondedCompanionPanelActionService actions;
    private final CommandFeedbackService feedback;
    private final HytaleBondedCompanionActionContextFactory contexts;

    BondedCompanionPanelActionRouter(BondedCompanionPanelActionService actions,
                                     CommandFeedbackService feedback) {
        this(actions, feedback,
                new HytaleBondedCompanionActionContextFactory());
    }

    BondedCompanionPanelActionRouter(BondedCompanionPanelActionService actions,
                                     CommandFeedbackService feedback,
                                     HytaleBondedCompanionActionContextFactory contexts) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    static BondedCompanionPanelActionRouter production(
            CommandFeedbackService feedback) {
        return new BondedCompanionPanelActionRouter(
                new BondedCompanionPanelActionService(() -> {
                    try {
                        Tamework plugin = Tamework.getInstance();
                        return plugin == null || plugin.getApi() == null
                                ? BondedCompanionApi.unavailable()
                                : plugin.getApi().bondedCompanions();
                    } catch (RuntimeException | LinkageError ignored) {
                        return BondedCompanionApi.unavailable();
                    }
                }), feedback);
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
        route(player, store, config, feature, action, () -> true);
    }

    /** Applies a bonded lifecycle action only while its physical authority holds. */
    void route(Player player, Store<EntityStore> store,
               TwCommandItemConfig config,
               CommandPanelFeaturePresentation feature,
               BondedCompanionPanelActionService.Action action,
               BooleanSupplier lifecycleAuthority) {
        if (lifecycleAuthority == null || !lifecycleAuthority.getAsBoolean()) return;
        if (player == null || config == null || !config.usesBondedCompanionRoster()
                || feature == null || feature.bonded() == null) return;
        String world = player.getWorld() == null ? null : player.getWorld().getName();
        var context = contexts.create(
                player, store, feature.bonded().roleId(),
                action == BondedCompanionPanelActionService.Action.SUMMON);
        UUID ownerUuid = player.getUuid();
        var outcome = actions.performAsync(
                action, ownerUuid, world, context, feature.bonded());
        var owningWorld = player.getWorld();
        outcome.whenComplete((resolved, failure) -> {
            if (failure == null && resolved != null && resolved.applied()) return;
            String reason = resolved == null
                    ? "Bonded action failed." : resolved.reason();
            if (owningWorld == null || ownerUuid == null) return;
            try {
                owningWorld.execute(() -> showWarning(
                        owningWorld, store, ownerUuid, reason));
            } catch (RuntimeException | LinkageError ignored) {
                // A changed world is already an unavailable action context.
            }
        });
    }

    private void showWarning(
            com.hypixel.hytale.server.core.universe.world.World world,
            Store<EntityStore> store,
            UUID ownerUuid,
            String reason
    ) {
        try {
            store.assertThread();
            var playerRef = world.getEntityRef(ownerUuid);
            if (playerRef == null || !playerRef.isValid()
                    || playerRef.getStore() != store
                    || Player.getComponentType() == null) return;
            Player live = store.getComponent(
                    playerRef, Player.getComponentType());
            if (live != null) feedback.showWarning(live, reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Feedback is best-effort after the durable mutation result.
        }
    }
}
