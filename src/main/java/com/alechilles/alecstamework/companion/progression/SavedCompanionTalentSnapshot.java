package com.alechilles.alecstamework.companion.progression;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Codec;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Decodes the complete state preserved for an offline generic companion.
 *
 * <p>Only current modern DeathV2 and LOST full-state snapshots are supported. Returned full
 * state and component values are fresh copies, so callers cannot mutate the retained envelope.</p>
 */
public final class SavedCompanionTalentSnapshot {
    private static final CoopResidentStateSnapshotCodec FULL_STATE_CODEC =
            new CoopResidentStateSnapshotCodec();
    private static final DeathSnapshotV2Codec DEATH_CODEC =
            new DeathSnapshotV2Codec();

    private final CompanionSnapshot snapshot;
    @Nullable
    private final DeathSnapshotV2Payload death;
    private final String fullStateJson;

    private SavedCompanionTalentSnapshot(
            @Nonnull CompanionSnapshot snapshot,
            @Nullable DeathSnapshotV2Payload death,
            @Nonnull String fullStateJson
    ) {
        this.snapshot = snapshot;
        this.death = death;
        this.fullStateJson = fullStateJson;
    }

    /** Returns the supported current offline snapshot for this profile, or {@code null}. */
    @Nullable
    public static SavedCompanionTalentSnapshot find(
            @Nullable CompanionProfileReadModel profile
    ) {
        if (profile == null) {
            return null;
        }
        CompanionLifecycle lifecycle = profile.lifecycle();
        if (lifecycle.state() != LifecycleState.DEAD_REVIVABLE
                && lifecycle.state() != LifecycleState.LOST) {
            return null;
        }
        return profile.currentSnapshots().stream()
                .filter(snapshot -> snapshot.kind().equals(
                        lifecycle.state() == LifecycleState.DEAD_REVIVABLE
                                ? TameworkSnapshotCodecs.DEATH
                                : TameworkSnapshotCodecs.LOST))
                .map(SavedCompanionTalentSnapshot::decode)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** Decodes one supported modern offline snapshot, returning {@code null} for other formats. */
    @Nullable
    public static SavedCompanionTalentSnapshot decode(
            @Nullable CompanionSnapshot snapshot
    ) {
        if (snapshot == null || !snapshot.current()) {
            return null;
        }
        try {
            if (TameworkSnapshotCodecs.DEATH.equals(snapshot.kind())
                    && snapshot.payloadVersion() == 2) {
                DeathSnapshotV2Payload death = DEATH_CODEC.decode(
                        snapshot.payloadJson());
                return new SavedCompanionTalentSnapshot(
                        snapshot, death, death.fullStateJson());
            }
            if (TameworkSnapshotCodecs.LOST.equals(snapshot.kind())
                    && snapshot.payloadVersion() == 2) {
                CoopResidentStateSnapshotCodec.DecodeResult decoded =
                        FULL_STATE_CODEC.decode(snapshot.payloadJson());
                if (decoded.status()
                        != CoopResidentStateSnapshotCodec.Status.FOUND
                        || decoded.snapshot() == null) {
                    return null;
                }
                return new SavedCompanionTalentSnapshot(
                        snapshot, null, FULL_STATE_CODEC.encode(
                                decoded.snapshot()));
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    @Nonnull
    public CompanionSnapshot snapshot() {
        return snapshot;
    }

    /** Returns a fresh complete state value. */
    @Nonnull
    public CoopResidentStateSnapshot fullState() {
        if (death != null) {
            return death.fullState();
        }
        CoopResidentStateSnapshotCodec.DecodeResult decoded =
                FULL_STATE_CODEC.decode(fullStateJson);
        if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || decoded.snapshot() == null) {
            throw new IllegalStateException("Saved companion full state is unreadable");
        }
        return decoded.snapshot();
    }

    @Nullable
    public TameworkTalentsComponent talents() {
        TameworkTalentsComponent talents = fullState().talents();
        return talents == null ? null : talents.clone();
    }

    @Nullable
    public TameworkLevelingComponent leveling() {
        TameworkLevelingComponent leveling = fullState().leveling();
        return leveling == null ? null : leveling.clone();
    }

    /**
     * Returns a new current snapshot with only its saved talent component replaced.
     * The caller supplies the current lifecycle revision required by snapshot storage.
     */
    @Nonnull
    public CompanionSnapshot replaceTalents(
            @Nonnull TameworkTalentsComponent talents,
            @Nonnull LifecycleRevision lifecycleRevision,
            long changedAtMs
    ) {
        if (talents == null || lifecycleRevision == null) {
            throw new IllegalArgumentException(
                    "Saved talent replacement evidence is required"
            );
        }
        CoopResidentStateSnapshot state = fullState();
        CoopResidentStateSnapshot changed = withTalents(state, talents.clone());
        String payload;
        if (death != null) {
            DeathSnapshotV2Payload changedDeath = new DeathSnapshotV2Payload(
                    FULL_STATE_CODEC.encode(changed),
                    death.diedAtMs(),
                    death.respawnAvailableAtMs(),
                    death.deathCauseKind(),
                    death.deathSourceName()
            );
            payload = DEATH_CODEC.encode(changedDeath);
        } else {
            payload = FULL_STATE_CODEC.encode(changed);
        }
        return new CompanionSnapshot(
                SnapshotId.create(),
                snapshot.profileId(),
                snapshot.kind(),
                snapshot.payloadVersion(),
                payload,
                Sha256Hash.ofUtf8(payload),
                lifecycleRevision,
                true,
                changedAtMs
        );
    }

    private static CoopResidentStateSnapshot withTalents(
            CoopResidentStateSnapshot state,
            TameworkTalentsComponent talents
    ) {
        return new CoopResidentStateSnapshot(
                state.npcUuid(), state.coopId(), state.residentSlot(),
                state.roleId(), state.commandLinks(), state.owner(),
                state.tamed(), state.npcName(), state.happiness(),
                state.needs(), state.breeding(), state.leveling(),
                state.traits(), talents, state.lifeStage(), state.attachments(),
                state.currentHealth(), state.maximumHealth(),
                state.healthPercent(), state.capturedAtMs(), state.alarms()
        );
    }
}
