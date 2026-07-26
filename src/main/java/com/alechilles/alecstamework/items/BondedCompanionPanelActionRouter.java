package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;

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
        if (player == null || config == null || !config.usesBondedCompanionRoster()
                || feature == null || feature.bonded() == null) return;
        String world = player.getWorld() == null ? null : player.getWorld().getName();
        var context = contexts.create(
                player, store, feature.bonded().roleId());
        var outcome = actions.perform(
                action, player.getUuid(), world, context, feature.bonded());
        if (!outcome.applied()) {
            feedback.showWarning(player, outcome.reason());
        }
    }
}
