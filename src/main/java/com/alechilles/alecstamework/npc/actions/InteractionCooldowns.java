package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;

// Handles cooldown calculations and alarm updates for interactions.
// NOTE: Interaction cooldowns intentionally use wall-clock time (Instant.now),
// while other alarms may use game time (WorldTimeResource) via InteractionAlarmHelper.
final class InteractionCooldowns {
    private final ActionTameworkInteract owner;
    private final String alarmPrefix;

    InteractionCooldowns(ActionTameworkInteract owner, String alarmPrefix) {
        this.owner = owner;
        this.alarmPrefix = alarmPrefix;
    }

    // Determines the cooldown duration for a specific interaction entry.
    int resolveCooldownSeconds(TwInteractionConfig config, InteractionEntry entry) {
        if (entry != null && entry.getCooldownSeconds() != null) {
            return Math.max(0, entry.getCooldownSeconds());
        }
        if (config != null && config.getCooldowns() != null
                && config.getCooldowns().getInteractionSeconds() != null) {
            return Math.max(0, config.getCooldowns().getInteractionSeconds());
        }
        return 0;
    }

    // Builds the alarm name used to track interaction cooldowns.
    String buildCooldownAlarmName(TwInteractionConfig config, int index) {
        String configId = config != null ? config.getId() : null;
        String safeConfigId = sanitizeAlarmSegment(configId);
        return alarmPrefix + "_" + safeConfigId + "_" + index;
    }

    // Applies the cooldown alarm after a successful interaction.
    void applyInteractionCooldown(ActionTameworkInteract.ResolvedInteraction interaction,
                                  Ref<EntityStore> npcRef,
                                  Store<EntityStore> store) {
        if (interaction == null || interaction.cooldownSeconds <= 0) {
            return;
        }
        if (interaction.cooldownAlarmName == null || interaction.cooldownAlarmName.isBlank()) {
            return;
        }
        NPCEntity npc = owner.resolveNpcEntity(npcRef, store);
        if (npc == null) {
            return;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return;
        }
        Alarm alarm = alarmStore.get(npc, interaction.cooldownAlarmName);
        if (alarm == null) {
            return;
        }
        alarm.set(npcRef, Instant.now().plusSeconds(interaction.cooldownSeconds), store);
    }

    // Checks whether a cooldown alarm is ready to allow another interaction.
    boolean isCooldownReady(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        NPCEntity npc = owner.resolveNpcEntity(npcRef, store);
        if (npc == null || alarmName == null || alarmName.isBlank()) {
            return false;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return false;
        }
        Alarm alarm = alarmStore.get(npc, alarmName);
        if (alarm == null) {
            return true;
        }
        if (!alarm.isSet()) {
            return true;
        }
        return alarm.hasPassed(Instant.now());
    }

    // Normalizes a config id into a safe alarm name segment.
    private String sanitizeAlarmSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        return safe.toString();
    }
}
