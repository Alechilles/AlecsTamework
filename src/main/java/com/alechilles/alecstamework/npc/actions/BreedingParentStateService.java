package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
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
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Captures and revalidates immutable parent identity, breeding state, and cooldown fingerprints. */
final class BreedingParentStateService {
    private final Supplier<CompanionIdentityResolver> identityResolverSupplier;

    BreedingParentStateService() {
        this(BreedingParentStateService::runtimeIdentityResolver);
    }

    BreedingParentStateService(@Nonnull Supplier<CompanionIdentityResolver> identityResolverSupplier) {
        this.identityResolverSupplier = Objects.requireNonNull(
                identityResolverSupplier, "identityResolverSupplier"
        );
    }

    @Nonnull
    BreedingParentIdentity resolveIdentity(@Nonnull Ref<EntityStore> ref,
                                           @Nonnull NPCEntity npc,
                                           @Nonnull Store<EntityStore> store) {
        UUID entityUuid = Objects.requireNonNull(npc.getUuid(), "parent NPC UUID");
        String profileId = resolveProfileId(entityUuid, projectionProfileId(ref, store));
        return new BreedingParentIdentity(entityUuid, profileId);
    }

    @Nonnull
    String resolveProfileId(@Nonnull UUID entityUuid, @Nullable String projectionProfileId) {
        String markerProfile = normalize(projectionProfileId);
        CompanionIdentityResolver resolver = identityResolverSupplier.get();
        String aliasProfile = resolver == null
                ? null
                : resolver.resolveProfileId(entityUuid).orElse(null);
        aliasProfile = normalize(aliasProfile);
        if (markerProfile != null && aliasProfile != null
                && !markerProfile.equals(aliasProfile)) {
            throw new IllegalStateException(
                    "Breeding parent projection identity conflicts with canonical alias"
            );
        }
        if (aliasProfile != null) {
            return aliasProfile;
        }
        return markerProfile != null ? markerProfile : "entity:" + entityUuid;
    }

    boolean matchesIdentity(@Nonnull BreedingParentIdentity expected,
                            @Nonnull Ref<EntityStore> ref,
                            @Nonnull NPCEntity npc,
                            @Nonnull Store<EntityStore> store) {
        if (!expected.entityUuid().equals(npc.getUuid())) {
            return false;
        }
        try {
            return expected.profileId().equals(resolveIdentity(ref, npc, store).profileId());
        } catch (RuntimeException | LinkageError conflict) {
            return false;
        }
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

    @Nullable
    String snapshotIssue(@Nonnull TameworkBreedingComponent breeding,
                         @Nonnull NPCEntity npc) {
        Alarm alarm = breedingAlarm(npc);
        if (alarm != null && alarm.isSet() && breeding.getCooldownUntilMs() == 0L) {
            return "cooldown-alarm-set-without-component-deadline";
        }
        return null;
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

    /** Uses the exact currently persisted parent state as a no-op replay fingerprint. */
    @Nonnull
    AppliedCooldownFingerprint fingerprint(@Nonnull ParentBreedingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new AppliedCooldownFingerprint(
                true,
                snapshot.ready(),
                snapshot.cooldownUntilMs(),
                snapshot.cooldownStartedAtMs(),
                snapshot.cooldownDurationMs(),
                snapshot.lastPartnerUuid(),
                snapshot.lastHappinessUpdateMs(),
                snapshot.manualBreedingPlayerUuid(),
                snapshot.manualBreedingUntilMs(),
                snapshot.alarm()
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

    @Nullable
    private static CompanionIdentityResolver runtimeIdentityResolver() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getCompanionIdentityResolver();
    }

}
