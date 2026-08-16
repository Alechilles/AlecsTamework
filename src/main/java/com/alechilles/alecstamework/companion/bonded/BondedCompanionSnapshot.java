package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Complete state that follows a stable bonded profile.
 *
 * <p>The source UUID in the captured state is evidence only. A live NPC is a
 * disposable projection and never owns this snapshot or the profile identity.</p>
 */
public final class BondedCompanionSnapshot {
    private static final CoopResidentStateSnapshotCodec FULL_STATE_CODEC =
            new CoopResidentStateSnapshotCodec();

    private final CoopResidentStateSnapshot fullState;
    private final Map<String, String> extensionData;

    private BondedCompanionSnapshot(
            CoopResidentStateSnapshot fullState,
            Map<String, String> extensionData
    ) {
        this.fullState = FULL_STATE_CODEC.copy(Objects.requireNonNull(
                fullState, "fullState"
        ));
        this.extensionData = copyExtensions(extensionData);
    }

    /** Creates a defensive profile-owned snapshot. */
    @Nonnull
    public static BondedCompanionSnapshot of(
            @Nonnull CoopResidentStateSnapshot fullState,
            @Nonnull Map<String, String> extensionData
    ) {
        return new BondedCompanionSnapshot(fullState, extensionData);
    }

    /** Returns a deep copy so component mutation cannot alter durable state. */
    @Nonnull
    public CoopResidentStateSnapshot fullState() {
        return FULL_STATE_CODEC.copy(fullState);
    }

    /** Returns immutable namespaced JSON captured alongside the full state. */
    @Nonnull
    public Map<String, String> extensionData() {
        return extensionData;
    }

    /**
     * Merges a later live capture without treating unavailable optional
     * components or namespaces as deletion instructions.
     */
    @Nonnull
    public BondedCompanionSnapshot mergeForStore(
            @Nonnull BondedCompanionSnapshot newer
    ) {
        Objects.requireNonNull(newer, "newer");
        CoopResidentStateSnapshot merged =
                FULL_STATE_CODEC.mergePreservingExisting(
                        fullState, newer.fullState
                );
        LinkedHashMap<String, String> extensions =
                new LinkedHashMap<>(extensionData);
        extensions.putAll(newer.extensionData);
        return new BondedCompanionSnapshot(merged, extensions);
    }

    /**
     * Returns a summon-safe snapshot after a paid revive without creating a
     * live projection. Exact health is restored to its saved maximum when
     * known; older percentage-only snapshots retain their 100% fallback.
     */
    @Nonnull
    public BondedCompanionSnapshot restoredAfterRevive() {
        return restoredToFullHealth();
    }

    /** Returns a full-health snapshot for a new live summon. */
    @Nonnull
    public BondedCompanionSnapshot restoredForSummon() {
        return restoredToFullHealth();
    }

    private BondedCompanionSnapshot restoredToFullHealth() {
        Double maximum = fullState.maximumHealth();
        boolean exactMaximum = maximum != null && Double.isFinite(maximum)
                && maximum > 0.0D;
        Double restoredPercent = exactMaximum || fullState.healthPercent() != null
                ? 100.0D : null;
        CoopResidentStateSnapshot restored = new CoopResidentStateSnapshot(
                fullState.npcUuid(), fullState.coopId(),
                fullState.residentSlot(), fullState.roleId(),
                fullState.commandLinks(), fullState.owner(), fullState.tamed(),
                fullState.npcName(), fullState.happiness(), fullState.needs(),
                fullState.breeding(), fullState.leveling(), fullState.traits(),
                fullState.talents(), fullState.lifeStage(),
                fullState.attachments(), exactMaximum ? maximum : null,
                exactMaximum ? maximum : null, restoredPercent,
                fullState.capturedAtMs());
        return new BondedCompanionSnapshot(restored, extensionData);
    }

    /** Returns this snapshot with only its persisted talent component replaced. */
    @Nonnull
    public BondedCompanionSnapshot withTalents(
            @Nullable TameworkTalentsComponent talents
    ) {
        CoopResidentStateSnapshot state = fullState;
        CoopResidentStateSnapshot updated = new CoopResidentStateSnapshot(
                state.npcUuid(), state.coopId(), state.residentSlot(),
                state.roleId(), state.commandLinks(), state.owner(),
                state.tamed(), state.npcName(), state.happiness(),
                state.needs(), state.breeding(), state.leveling(),
                state.traits(), talents, state.lifeStage(),
                state.attachments(), state.currentHealth(),
                state.maximumHealth(), state.healthPercent(),
                state.capturedAtMs());
        return new BondedCompanionSnapshot(updated, extensionData);
    }

    /** Returns this snapshot with only its persisted NPC role replaced. */
    @Nonnull
    public BondedCompanionSnapshot withRoleId(@Nonnull String roleId) {
        CoopResidentStateSnapshot state = fullState;
        CoopResidentStateSnapshot updated = new CoopResidentStateSnapshot(
                state.npcUuid(), state.coopId(), state.residentSlot(),
                Objects.requireNonNull(roleId, "roleId"),
                state.commandLinks(), state.owner(), state.tamed(),
                state.npcName(), state.happiness(), state.needs(),
                state.breeding(), state.leveling(), state.traits(),
                state.talents(), state.lifeStage(), state.attachments(),
                state.currentHealth(), state.maximumHealth(),
                state.healthPercent(), state.capturedAtMs()
        );
        return new BondedCompanionSnapshot(updated, extensionData);
    }

    CoopResidentStateSnapshot fullStateInternal() {
        return fullState;
    }

    private static Map<String, String> copyExtensions(
            Map<String, String> source
    ) {
        Objects.requireNonNull(source, "extensionData");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        source.forEach((namespace, payload) -> {
            String key = text(namespace, "extension namespace");
            String json = Objects.requireNonNull(
                    payload, "extension payload"
            );
            JsonParser.parseString(json);
            copied.put(key, json);
        });
        return Map.copyOf(copied);
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
