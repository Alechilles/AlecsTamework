package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.persistence.CompanionLifecycleAuthorResult;
import com.alechilles.alecstamework.items.persistence.CompanionRestorationCompletionListener;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Maps canonical restoration outcomes to released command-item player feedback.
 */
public final class CommandRestorationCompletionListener
        implements CompanionRestorationCompletionListener {
    private final CommandFeedbackService feedback =
            new CommandFeedbackService(new TameworkUiMessageService());

    @Override
    public void complete(
            @Nonnull CompanionLifecycleAuthorResult result,
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> actorRef,
            @Nonnull Player player
    ) {
        switch (result.status()) {
            case PUBLISHED -> feedback.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.respawn.success",
                    LocalizedText.resolve(
                            player,
                            "tamework.ui.notifications.command.shared.defaultCompanionName"
                    )
            );
            case RESTORATION_DISABLED -> feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.disabled"
            );
            case PROFILE_CONFLICT -> feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.notDeadOrLost"
            );
            case COOLDOWN_ACTIVE, INVALID_CONTEXT, INVALID_EVIDENCE,
                    EVIDENCE_FAILED, PROFILE_READ_FAILED,
                    SNAPSHOT_DECODE_FAILED, SUBMISSION_REJECTED,
                    WORKFLOW_FAILED -> feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.failed"
            );
        }
    }
}
