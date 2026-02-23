package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandFeedback;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
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
        String defaultMessage = resolveDefaultMessage(commandLabel, affected, queued);
        CommandFeedback feedback = command.getFeedback();
        String hudMessage = resolveFeedbackText(
                feedback != null ? feedback.getHudMessage() : null,
                commandLabel,
                affected,
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

    void showSuccess(Player player, String text) {
        show(player, text, NotificationStyle.Success);
    }

    void showWarning(Player player, String text) {
        show(player, text, NotificationStyle.Warning);
    }

    private String resolveCommandLabel(CommandEntry command, Function<CommandEntry, String> commandLabelResolver) {
        if (commandLabelResolver == null) {
            return command != null && command.getId() != null ? command.getId() : "Unknown";
        }
        String label = commandLabelResolver.apply(command);
        if (label == null || label.isBlank()) {
            return command != null && command.getId() != null ? command.getId() : "Unknown";
        }
        return label;
    }

    private String resolveDefaultMessage(String commandLabel, int affected, int queued) {
        if (affected > 0 && queued > 0) {
            return "Command " + commandLabel + " applied to " + affected + " NPC(s), queued for " + queued + " unloaded NPC(s).";
        }
        if (affected > 0) {
            return "Command " + commandLabel + " applied to " + affected + " NPC(s).";
        }
        if (queued > 0) {
            return "Command " + commandLabel + " queued for " + queued + " unloaded NPC(s).";
        }
        return "No NPCs could execute that command.";
    }

    private String resolveFeedbackText(String template,
                                       String commandLabel,
                                       int affected,
                                       String fallback) {
        String value = (template != null && !template.isBlank()) ? template : fallback;
        if (value == null || value.isBlank()) {
            return null;
        }
        return value
                .replace("%count%", Integer.toString(affected))
                .replace("%command%", commandLabel)
                .replace("{count}", Integer.toString(affected))
                .replace("{command}", commandLabel);
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
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform != null) {
            Vector3d position = transform.getPosition();
            SoundUtil.playSoundEvent3d(
                    soundEventIndex,
                    SoundCategory.SFX,
                    position.x,
                    position.y,
                    position.z,
                    DEFAULT_COMMAND_FEEDBACK_VOLUME,
                    DEFAULT_COMMAND_FEEDBACK_PITCH,
                    store
            );
            return;
        }
        SoundUtil.playSoundEvent2d(
                playerRef,
                soundEventIndex,
                SoundCategory.SFX,
                DEFAULT_COMMAND_FEEDBACK_VOLUME,
                DEFAULT_COMMAND_FEEDBACK_PITCH,
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
        ParticleUtil.spawnParticleEffect(particleSystem, position, store);
    }

    private void show(Player player, String text, NotificationStyle style) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        uiMessageService.show(player, text, style);
    }
}

