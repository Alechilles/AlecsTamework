package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Captures and revalidates immutable parent identity, breeding state, and cooldown fingerprints. */
final class BreedingParentStateService {
    @Nonnull
    BreedingParentIdentity resolveIdentity(@Nonnull Ref<EntityStore> ref,
                                           @Nonnull NPCEntity npc,
                                           @Nonnull Store<EntityStore> store) {
        UUID entityUuid = Objects.requireNonNull(npc.getUuid(), "parent NPC UUID");
        String profileId = projectionProfileId(ref, store);
        if (profileId == null) {
            profileId = "entity:" + entityUuid;
        }
        return new BreedingParentIdentity(entityUuid, profileId);
    }

    boolean matchesIdentity(@Nonnull BreedingParentIdentity expected,
                            @Nonnull Ref<EntityStore> ref,
                            @Nonnull NPCEntity npc,
                            @Nonnull Store<EntityStore> store) {
        if (!expected.entityUuid().equals(npc.getUuid())) {
            return false;
        }
        return expected.profileId().equals(resolveIdentity(ref, npc, store).profileId());
    }

    boolean matchesFingerprint(@Nonnull AppliedCooldownFingerprint expected,
                               @Nonnull TameworkBreedingComponent breeding,
                               @Nonnull NPCEntity npc) {
        if (!expected.applied()) {
            return true;
        }
        ParentBreedingSnapshot.AlarmSnapshot alarm = snapshotAlarm(npc, breeding);
        return alarm != null
                && breeding.isReady() == expected.ready()
                && breeding.getCooldownUntilMs() == expected.cooldownUntilMs()
                && breeding.getCooldownStartedAtMs() == expected.cooldownStartedAtMs()
                && breeding.getCooldownDurationMs() == expected.cooldownDurationMs()
                && Objects.equals(breeding.getLastPartnerUuid(), expected.lastPartnerUuid())
                && breeding.getLastHappinessUpdateMs() == expected.lastHappinessUpdateMs()
                && Objects.equals(
                        breeding.getManualBreedingPlayerUuid(),
                        expected.manualBreedingPlayerUuid()
                )
                && breeding.getManualBreedingUntilMs() == expected.manualBreedingUntilMs()
                && alarm.equals(expected.alarm());
    }

    boolean restoreIfFingerprintMatches(@Nonnull BreedingParentIdentity identity,
                                        @Nonnull ParentBreedingSnapshot snapshot,
                                        @Nonnull AppliedCooldownFingerprint fingerprint,
                                        @Nonnull Ref<EntityStore> ref,
                                        @Nonnull NPCEntity npc,
                                        @Nonnull TameworkBreedingComponent breeding,
                                        @Nonnull Store<EntityStore> store) {
        return restoreIfFingerprintMatches(
                identity, snapshot, fingerprint, ref, npc, breeding, store, null
        );
    }

    boolean restoreIfFingerprintMatches(@Nonnull BreedingParentIdentity identity,
                                        @Nonnull ParentBreedingSnapshot snapshot,
                                        @Nonnull AppliedCooldownFingerprint fingerprint,
                                        @Nonnull Ref<EntityStore> ref,
                                        @Nonnull NPCEntity npc,
                                        @Nonnull TameworkBreedingComponent breeding,
                                        @Nonnull Store<EntityStore> store,
                                        @Nullable CommandBuffer<EntityStore> commandBuffer) {
        if (!matchesIdentity(identity, ref, npc, store)
                || !matchesFingerprint(fingerprint, breeding, npc)) {
            return false;
        }
        UUID manualPlayer = snapshot.manualBreedingPlayerUuid();
        long manualUntil = snapshot.manualBreedingUntilMs();
        if (manualPlayer != null && ManualBreedingClock.nowMs() >= manualUntil) {
            manualPlayer = null;
            manualUntil = 0L;
        }
        TameworkBreedingComponent restored = new TameworkBreedingComponent(
                snapshot.configId(),
                snapshot.happiness(),
                snapshot.lastHappinessUpdateMs(),
                snapshot.ready(),
                snapshot.enabled(),
                snapshot.cooldownUntilMs(),
                snapshot.lastPartnerUuid(),
                snapshot.cooldownStartedAtMs(),
                snapshot.cooldownDurationMs(),
                manualPlayer,
                manualUntil
        );
        ComponentType<EntityStore, TameworkBreedingComponent> type =
                TameworkBreedingComponent.getComponentType();
        if (type == null || !restoreAlarm(snapshot.alarm(), ref, npc, store)) {
            return false;
        }
        if (commandBuffer != null) {
            commandBuffer.putComponent(ref, type, restored);
        } else {
            store.putComponent(ref, type, restored);
        }
        return true;
    }

    @Nullable
    ParentBreedingSnapshot snapshot(@Nonnull TameworkBreedingComponent breeding,
                                    @Nonnull NPCEntity npc) {
        ParentBreedingSnapshot.AlarmSnapshot alarm = snapshotAlarm(npc, breeding);
        if (alarm == null) {
            return null;
        }
        return new ParentBreedingSnapshot(
                breeding.getConfigId(),
                breeding.getHappiness(),
                breeding.getLastHappinessUpdateMs(),
                breeding.isReady(),
                breeding.isEnabled(),
                breeding.getCooldownUntilMs(),
                breeding.getCooldownStartedAtMs(),
                breeding.getCooldownDurationMs(),
                breeding.getLastPartnerUuid(),
                breeding.getManualBreedingPlayerUuid(),
                breeding.getManualBreedingUntilMs(),
                alarm
        );
    }

    @Nonnull
    AppliedCooldownFingerprint fingerprint(@Nonnull NPCEntity npc,
                                           @Nonnull BreedingCooldownService.CooldownWindow window,
                                           @Nonnull UUID partnerUuid,
                                           long happinessUpdatedAtMs) {
        ParentBreedingSnapshot.AlarmSnapshot alarm = hasBreedingAlarm(npc)
                ? alarmAfterCooldown(window.untilMs())
                : ParentBreedingSnapshot.AlarmSnapshot.missing();
        return new AppliedCooldownFingerprint(
                true,
                false,
                window.untilMs(),
                window.startedAtMs(),
                window.durationMs(),
                partnerUuid,
                happinessUpdatedAtMs,
                null,
                0L,
                alarm
        );
    }

    @Nullable
    private ParentBreedingSnapshot.AlarmSnapshot snapshotAlarm(
            NPCEntity npc,
            TameworkBreedingComponent breeding) {
        Alarm alarm = breedingAlarm(npc);
        if (alarm == null) {
            return ParentBreedingSnapshot.AlarmSnapshot.missing();
        }
        if (!alarm.isSet()) {
            return ParentBreedingSnapshot.AlarmSnapshot.unset();
        }
        long untilMs = breeding.getCooldownUntilMs();
        if (untilMs == 0L) {
            return null;
        }
        return ParentBreedingSnapshot.AlarmSnapshot.set(untilMs);
    }

    @Nonnull
    private ParentBreedingSnapshot.AlarmSnapshot alarmAfterCooldown(long untilMs) {
        return untilMs == 0L
                ? ParentBreedingSnapshot.AlarmSnapshot.unset()
                : ParentBreedingSnapshot.AlarmSnapshot.set(untilMs);
    }

    private boolean hasBreedingAlarm(NPCEntity npc) {
        return breedingAlarm(npc) != null;
    }

    private boolean restoreAlarm(ParentBreedingSnapshot.AlarmSnapshot snapshot,
                                 Ref<EntityStore> ref,
                                 NPCEntity npc,
                                 Store<EntityStore> store) {
        Alarm alarm = breedingAlarm(npc);
        if (!snapshot.exists()) {
            return alarm == null;
        }
        if (alarm == null) {
            return false;
        }
        if (snapshot.set()) {
            alarm.set(ref, Instant.ofEpochMilli(snapshot.untilMs()), store);
            return true;
        }
        try {
            alarm.set(ref, null, store);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Nullable
    private Alarm breedingAlarm(NPCEntity npc) {
        AlarmStore store = npc.getAlarmStore();
        return store == null ? null : store.get(npc, BreedingCooldownService.BREEDING_COOLDOWN_ALARM_NAME);
    }

    @Nullable
    private String projectionProfileId(Ref<EntityStore> ref, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                TameworkProjectionIdentityComponent.getComponentType();
        TameworkProjectionIdentityComponent marker = type != null ? store.getComponent(ref, type) : null;
        return normalize(marker != null ? marker.getProfileId() : null);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
