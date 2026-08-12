package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.compat.HytaleParticleAccess;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandFeedback;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.function.Function;

/**
 * Centralizes command feedback presentation (notifications, sounds, and particles).
 */
final class CommandFeedbackService {
    private static final float DEFAULT_COMMAND_FEEDBACK_VOLUME = 1.0f;
    private static final float DEFAULT_COMMAND_FEEDBACK_PITCH = 1.0f;

    private final TameworkUiMessageService uiMessageService;

    CommandFeedbackService(TameworkUiMessageService uiMessageService) {
        this.uiMessageService = uiMessageService != null ? uiMessageService : new TameworkUiMessageService();
    }

    void emitCommandExecutionFeedback(Player player,
                                      Ref<EntityStore> playerRef,
                                      Store<EntityStore> store,
                                      CommandEntry command,
                                      int affected,
                                      int queued,
                                      Function<CommandEntry, String> commandLabelResolver) {
        if (player == null || command == null) {
            return;
        }
        String commandLabel = resolveCommandLabel(command, commandLabelResolver);
        String defaultMessage = resolveDefaultMessage(player, commandLabel, affected, queued);
        int accepted = acceptedRecipientCount(affected, queued);
        CommandFeedback feedback = command.getFeedback();
        String hudMessage = resolveFeedbackText(
                player,
                feedback != null ? feedback.getHudMessage() : null,
                commandLabel,
                accepted,
                defaultMessage
        );
        if (hudMessage != null && !hudMessage.isBlank()) {
            showSuccess(player, hudMessage);
        }
        if (feedback != null) {
            emitFeedbackSound(feedback.getSoundEvent(), playerRef, store);
            emitFeedbackParticles(feedback.getParticleSystem(), feedback.getParticleOffset(), playerRef, store);
        }
    }

    void showDefault(Player player, String text) {
        show(player, text, NotificationStyle.Default);
    }

    void showDefaultKey(Player player, String key, Object... args) {
        show(player, LocalizedText.format(player, key, args), NotificationStyle.Default);
    }

    void showSuccess(Player player, String text) {
        show(player, text, NotificationStyle.Success);
    }

    void showSuccessKey(Player player, String key, Object... args) {
        show(player, LocalizedText.format(player, key, args), NotificationStyle.Success);
    }

    void showWarning(Player player, String text) {
        show(player, text, NotificationStyle.Warning);
    }

    void showWarningKey(Player player, String key, Object... args) {
        show(player, LocalizedText.format(player, key, args), NotificationStyle.Warning);
    }

    void emitRestorationRequestFeedback(
            Player player,
            CommandCompanionRestorationService.RequestStatus status
    ) {
        if (player == null) {
            return;
        }
        String key = restorationRequestFeedbackKey(status);
        if (key != null) {
            showWarningKey(player, key);
        }
    }

    static String restorationRequestFeedbackKey(
            CommandCompanionRestorationService.RequestStatus status
    ) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case NOT_DORMANT ->
                    "tamework.ui.notifications.command.respawn.notDeadOrLost";
            case DESTINATION_NPCS_FROZEN ->
                    "tamework.ui.notifications.command.destination.npcsFrozen";
            case UNAVAILABLE, INVALID_CONTEXT ->
                    "tamework.ui.notifications.command.respawn.unavailable";
            case STARTED -> null;
        };
    }

    private String resolveCommandLabel(CommandEntry command, Function<CommandEntry, String> commandLabelResolver) {
        if (commandLabelResolver == null) {
            return command != null && command.getId() != null
                    ? command.getId()
                    : LocalizedText.resolve((Player) null, "tamework.ui.notifications.command.unknown");
        }
        String label = commandLabelResolver.apply(command);
        if (label == null || label.isBlank()) {
            return command != null && command.getId() != null
                    ? command.getId()
                    : LocalizedText.resolve((Player) null, "tamework.ui.notifications.command.unknown");
        }
        return label;
    }

    private String resolveDefaultMessage(Player player, String commandLabel, int affected, int queued) {
        if (affected > 0 && queued > 0) {
            return LocalizedText.format(
                    player,
                    "tamework.ui.notifications.command.execution.appliedAndQueued",
                    commandLabel,
                    affected,
                    queued
            );
        }
        if (affected > 0) {
            return LocalizedText.format(
                    player,
                    "tamework.ui.notifications.command.execution.applied",
                    commandLabel,
                    affected
            );
        }
        if (queued > 0) {
            return LocalizedText.format(
                    player,
                    "tamework.ui.notifications.command.execution.queued",
                    commandLabel,
                    queued
            );
        }
        return LocalizedText.resolve(player, "tamework.ui.notifications.command.execution.none");
    }

    private String resolveFeedbackText(Player player,
                                       String template,
                                       String commandLabel,
                                       int affected,
                                       String fallback) {
        String language = player != null && player.getPlayerRef() != null
                ? player.getPlayerRef().getLanguage()
                : null;
        String value = LocalizedText.resolveConfigValue(language, template, fallback);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value
                .replace("%count%", Integer.toString(affected))
                .replace("%command%", commandLabel)
                .replace("{count}", Integer.toString(affected))
                .replace("{command}", commandLabel);
    }

    static int acceptedRecipientCount(int affected, int queued) {
        return Math.max(0, affected) + Math.max(0, queued);
    }

    private void emitFeedbackSound(String soundEventId,
                                   Ref<EntityStore> playerRef,
                                   Store<EntityStore> store) {
        if (playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        if (soundEventId == null || soundEventId.isBlank()) {
            return;
        }
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEventId);
        if (soundEventIndex <= 0) {
            return;
        }
        // Always play local feedback for the activating player.
        SoundUtil.playSoundEvent2d(
                playerRef,
                soundEventIndex,
                SoundCategory.SFX,
                DEFAULT_COMMAND_FEEDBACK_VOLUME,
                DEFAULT_COMMAND_FEEDBACK_PITCH,
                store
        );
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = transform.getPosition();
        // Emit positional sound for nearby players, excluding the source player.
        SoundUtil.playSoundEvent3d(
                playerRef,
                soundEventIndex,
                position.x,
                position.y,
                position.z,
                true,
                store
        );
    }

    private void emitFeedbackParticles(String particleSystem,
                                       Vector3d offset,
                                       Ref<EntityStore> playerRef,
                                       Store<EntityStore> store) {
        if (particleSystem == null || particleSystem.isBlank() || playerRef == null || !playerRef.isValid() || store == null) {
            return;
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        if (offset != null) {
            position.x += offset.x;
            position.y += offset.y;
            position.z += offset.z;
        }
        HytaleParticleAccess.spawn(particleSystem, position, store);
    }

    private void show(Player player, String text, NotificationStyle style) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        uiMessageService.show(player, text, style);
    }
}

