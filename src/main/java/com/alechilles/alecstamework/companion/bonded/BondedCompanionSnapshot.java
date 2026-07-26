package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

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
