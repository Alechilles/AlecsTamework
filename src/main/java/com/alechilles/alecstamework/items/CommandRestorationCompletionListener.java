package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.server.core.entity.entities.Player;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maps {@link RestoreFlow} results for the panel's Revive and Recover buttons to command-item
 * player feedback. Every method runs on the player's world thread.
 */
public final class CommandRestorationCompletionListener {
    private static final String PREFIX = "tamework.ui.notifications.command.";
    private final CommandFeedbackService feedback =
            new CommandFeedbackService(new TameworkUiMessageService());

    /** The message key for one restore outcome. Only the success message takes the companion name. */
    @Nonnull
    static String keyFor(@Nonnull RestoreFlow.Result result) {
        return switch (result) {
            case RESTORED -> PREFIX + "respawn.success";
            case COOLDOWN -> PREFIX + "shared.cooldown";
            case NOT_ALLOWED, NOT_FOUND, STALE -> PREFIX + "respawn.notDeadOrLost";
            case NO_SNAPSHOT, SPAWN_FAILED -> PREFIX + "respawn.recoverFailed";
            case COMMIT_FAILED, CONFLICT -> PREFIX + "respawn.unavailable";
            case OWNED_LIMIT -> "tamework.ui.population.ownedLimit";
            case DEPLOYED_LIMIT -> CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY;
            case GROUP_LIMIT -> "tamework.ui.population.groupLimit";
            case PROVIDER_DENIED -> CompanionAdmission.PROVIDER_DENIED_MESSAGE_KEY;
            case PROVIDER_UNAVAILABLE -> CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY;
        };
    }

    /**
     * Tells the player a paid revive was refused for missing items. {@code configuredMessage} is
     * the role's optional InsufficientCostMessage language key; without one the default is shown.
     */
    void cannotAfford(@Nonnull Player player, @Nullable String configuredMessage) {
        String language = player.getPlayerRef() == null ? null : player.getPlayerRef().getLanguage();
        feedback.showWarning(player, LocalizedText.resolveConfigValue(
                language, configuredMessage, LocalizedText.resolve(language, PREFIX + "respawn.cannotAfford")));
    }

    /**
     * Tells the player how a panel restore ended. {@code companionName} is the record's name;
     * without one the localized default companion name is shown.
     */
    void complete(@Nonnull RestoreFlow.Result result, @Nonnull Player player, @Nullable String companionName) {
        if (result != RestoreFlow.Result.RESTORED) {
            feedback.showWarningKey(player, keyFor(result));
            return;
        }
        String name = companionName != null && !companionName.isBlank()
                ? companionName
                : LocalizedText.resolve(player, PREFIX + "shared.defaultCompanionName");
        feedback.showSuccessKey(player, keyFor(result), name);
    }
}
