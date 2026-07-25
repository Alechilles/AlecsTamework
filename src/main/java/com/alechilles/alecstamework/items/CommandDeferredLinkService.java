package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Completes one command-item link after the orchestrator defers targeting. */
final class CommandDeferredLinkService {
    private final CommandToolInventoryService tools;
    private final CommandLinkMutationService links;
    private final CommandFeedbackService feedback;

    CommandDeferredLinkService(
            @Nonnull CommandToolInventoryService tools,
            @Nonnull CommandLinkMutationService links,
            @Nonnull CommandFeedbackService feedback
    ) {
        this.tools = Objects.requireNonNull(tools, "Tools are required");
        this.links = Objects.requireNonNull(links, "Links are required");
        this.feedback = Objects.requireNonNull(
                feedback, "Feedback is required"
        );
    }

    void handle(
            Player player,
            Store<EntityStore> store,
            Ref<EntityStore> targetRef,
            String toolId,
            TwCommandItemConfig config
    ) {
        LinkToggleResult[] resultHolder = new LinkToggleResult[1];
        boolean mutated = tools.mutateToolStack(player, toolId, stack -> {
            LinkToggleResult result = links.tryToggleLink(
                    player,
                    store,
                    targetRef,
                    toolId,
                    config,
                    stack,
                    null
            );
            resultHolder[0] = result;
            return result != null && result.updatedItem != null
                    ? result.updatedItem
                    : stack;
        });
        emitFeedback(player, mutated, resultHolder[0]);
    }

    private void emitFeedback(
            Player player,
            boolean mutated,
            LinkToggleResult result
    ) {
        if (!mutated || result == null || !result.toggled
                || result.updatedItem == null) {
            feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.link.failed"
            );
            return;
        }
        if (result.linked && !result.active) {
            feedback.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.link.successInactive",
                    result.npcName
            );
            return;
        }
        feedback.showSuccessKey(
                player,
                result.linked
                        ? "tamework.ui.notifications.command.link.success"
                        : "tamework.ui.notifications.command.link.unlinked",
                result.npcName
        );
    }
}
