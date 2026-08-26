package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.population.domain.PopulationAdmissionFailureFeedback;
import com.alechilles.alecstamework.items.persistence.CompanionLifecycleAuthorResult;
import com.alechilles.alecstamework.items.persistence.CompanionRestorationCompletionListener;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Maps canonical restoration outcomes to released command-item player feedback.
 */
public final class CommandRestorationCompletionListener
        implements CompanionRestorationCompletionListener {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
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
            case COOLDOWN_ACTIVE -> feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.shared.cooldown"
            );
            case INVALID_CONTEXT, SUBMISSION_REJECTED ->
                    feedback.showWarningKey(
                            player,
                            "tamework.ui.notifications.command.respawn.unavailable"
                    );
            case INVALID_EVIDENCE, EVIDENCE_FAILED, PROFILE_READ_FAILED,
                    SNAPSHOT_DECODE_FAILED -> feedback.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.respawn.recoverFailed"
            );
            case WORKFLOW_FAILED -> showWorkflowFailure(player, result);
        }
    }

    private void showWorkflowFailure(
            Player player,
            CompanionLifecycleAuthorResult result
    ) {
        var entry = LOGGER.at(Level.WARNING);
        if (result.failure() != null) {
            entry = entry.withCause(result.failure());
        }
        entry.log("Companion revival workflow failed (status="
                + result.workflowStatus() + ", detail=" + result.detail() + ").");
        feedback.showWarning(player, workflowFailureMessage(result));
    }

    static String workflowFailureMessage(CompanionLifecycleAuthorResult result) {
        if (hasFailureCode(
                result.failure(), "operation_scope_policy_mismatch:"
        )) {
            return "Companion revival is unavailable because its persistence "
                    + "setup is incompatible.";
        }
        String specific = PopulationAdmissionFailureFeedback.describe(
                result.failure(), "revive"
        );
        if (specific != null) return specific;
        if (result.workflowStatus() == null) {
            return "Companion revival failed before its workflow could start.";
        }
        return switch (result.workflowStatus()) {
            case PREPARE_FAILED, INVALID_PHASE ->
                    "Companion revival could not validate its saved state.";
            case TRANSITION_FAILED, LIVE_RETRYABLE, LIVE_UNKNOWN ->
                    "Companion revival could not restore the companion in the world.";
            case DURABLE_READ_FAILED, DURABLE_COMMIT_FAILED ->
                    "Companion revival could not save the restored companion.";
            case PUBLICATION_PENDING, TERMINALIZATION_FAILED ->
                    "Companion revival is incomplete. Try again shortly.";
            case COMPENSATED, COMPENSATION_REQUIRED,
                    COMPENSATION_PREPARE_FAILED, COMPENSATION_RETRYABLE,
                    COMPENSATION_UNKNOWN, COMPENSATION_COMMIT_FAILED ->
                    "Companion revival was rolled back. Try again shortly.";
            case PUBLISHED -> "Companion revival completed.";
        };
    }

    private static boolean hasFailureCode(Throwable failure, String prefix) {
        while (failure != null) {
            if (failure.getMessage() != null
                    && failure.getMessage().startsWith(prefix)) {
                return true;
            }
            failure = failure.getCause();
        }
        return false;
    }
}
