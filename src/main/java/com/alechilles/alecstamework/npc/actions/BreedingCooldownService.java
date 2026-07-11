package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies one signed-time-safe provisional breeding cooldown to a parent and its NPC alarm.
 */
final class BreedingCooldownService {
    private static final String BREEDING_COOLDOWN_ALARM_NAME = "Breeding_Cooldown";

    void applyParentCooldown(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull TameworkBreedingComponent breeding,
                             @Nullable NPCEntity npc,
                             @Nullable UUID partnerUuid,
                             long cooldownMs,
                             long nowMs,
                             @Nonnull Store<EntityStore> store,
                             @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (!npcRef.isValid()) {
            return;
        }
        CooldownWindow window = resolveWindow(nowMs, cooldownMs);
        breeding.setReady(false);
        breeding.setCooldownUntilMs(window.untilMs());
        breeding.setCooldownStartedAtMs(window.startedAtMs());
        breeding.setCooldownDurationMs(window.durationMs());
        breeding.setLastPartnerUuid(partnerUuid);
        breeding.setLastHappinessUpdateMs(System.currentTimeMillis());
        breeding.clearManualBreedingReady();
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type != null) {
            putComponent(npcRef, store, commandBuffer, type, breeding);
        }
        applyCooldownAlarm(npcRef, npc, window.untilMs(), store);
    }

    @Nonnull
    static CooldownWindow resolveWindow(long nowMs, long cooldownMs) {
        long durationMs = Math.max(0L, cooldownMs);
        return new CooldownWindow(
                BreedingTimeService.deadlineAfter(nowMs, durationMs),
                BreedingTimeService.cooldownStartedAt(nowMs, durationMs),
                durationMs
        );
    }

    private static void applyCooldownAlarm(Ref<EntityStore> npcRef,
                                           @Nullable NPCEntity npc,
                                           long cooldownUntilMs,
                                           Store<EntityStore> store) {
        if (npc == null || cooldownUntilMs == 0L) {
            return;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        Alarm alarm = alarmStore != null ? alarmStore.get(npc, BREEDING_COOLDOWN_ALARM_NAME) : null;
        if (alarm != null) {
            alarm.set(npcRef, Instant.ofEpochMilli(cooldownUntilMs), store);
        }
    }

    private static <T extends Component<EntityStore>> void putComponent(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer,
            ComponentType<EntityStore, T> componentType,
            T component) {
        if (commandBuffer != null) {
            commandBuffer.putComponent(npcRef, componentType, component);
        } else {
            store.putComponent(npcRef, componentType, component);
        }
    }

    record CooldownWindow(long untilMs, long startedAtMs, long durationMs) {
    }
}
