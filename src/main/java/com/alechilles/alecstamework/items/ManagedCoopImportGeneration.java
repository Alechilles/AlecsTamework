package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionEnvelope;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Resolves the pending import occurrence without requiring a schema column on session rows. */
final class ManagedCoopImportGeneration {
    static final String ENVELOPE_FIELD = "importGeneration";

    private ManagedCoopImportGeneration() {
    }

    static int next(int currentImportVersion) {
        if (currentImportVersion < 0) {
            throw new IllegalArgumentException("current import version must not be negative");
        }
        if (currentImportVersion == Integer.MAX_VALUE) {
            throw new IllegalStateException("managed coop import generation exhausted");
        }
        return currentImportVersion + 1;
    }

    /**
     * Reads the generation persisted inside a new envelope. Legacy active sessions did not carry
     * the field, so their still-pending generation is derived from the unchanged authority version.
     */
    static int persistedOrLegacy(@Nonnull SessionEnvelope envelope,
                                 int legacyPendingGeneration) {
        Objects.requireNonNull(envelope, "envelope");
        if (legacyPendingGeneration < 1) {
            throw new IllegalArgumentException("legacy pending generation must be positive");
        }
        JsonObject audit = JsonParser.parseString(envelope.auditEnvelopeJson()).getAsJsonObject();
        JsonElement value = audit.get(ENVELOPE_FIELD);
        if (value == null || value.isJsonNull()) {
            return legacyPendingGeneration;
        }
        int generation;
        try {
            generation = value.getAsInt();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("persisted import generation is invalid", exception);
        }
        if (generation < 1) {
            throw new IllegalArgumentException("persisted import generation must be positive");
        }
        return generation;
    }
}
