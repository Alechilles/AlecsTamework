package com.alechilles.alecstamework.persistence.activation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Immutable result of one bounded durable-state probe.
 *
 * <p>The result contains no connection, schema manager, writer, or runtime
 * object. A {@link PersistenceActivationMode#READ_ONLY} result is fail-closed:
 * callers may report it, but must not construct a mutating persistence
 * authority from it.</p>
 */
public final class TameworkPersistenceActivationEvidence {
    private final PersistenceActivationMode mode;
    private final boolean databasePresent;
    private final boolean schemaValid;
    private final Set<String> evidence;
    private final String diagnosticCode;

    private TameworkPersistenceActivationEvidence(
            PersistenceActivationMode mode,
            boolean databasePresent,
            boolean schemaValid,
            Set<String> evidence,
            String diagnosticCode
    ) {
        this.mode = Objects.requireNonNull(mode, "Activation mode is required");
        if (!databasePresent && schemaValid) {
            throw new IllegalArgumentException(
                    "A missing database cannot have a valid schema");
        }
        if (mode == PersistenceActivationMode.DORMANT && !schemaValid
                && databasePresent) {
            throw new IllegalArgumentException(
                    "An existing invalid database cannot be dormant");
        }
        this.databasePresent = databasePresent;
        this.schemaValid = schemaValid;
        LinkedHashSet<String> copiedEvidence = new LinkedHashSet<>();
        if (evidence != null) {
            for (String value : evidence) {
                if (value != null && !value.isBlank()) {
                    copiedEvidence.add(value.trim());
                }
            }
        }
        if (mode == PersistenceActivationMode.ACTIVE
                && copiedEvidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "Active persistence evidence cannot be empty");
        }
        this.evidence = Collections.unmodifiableSet(copiedEvidence);
        this.diagnosticCode = requireText(diagnosticCode, "Diagnostic code");
    }

    /** Creates evidence for an absent or valid empty authority. */
    @Nonnull
    public static TameworkPersistenceActivationEvidence dormant(
            boolean databasePresent,
            boolean schemaValid
    ) {
        return new TameworkPersistenceActivationEvidence(
                PersistenceActivationMode.DORMANT,
                databasePresent,
                schemaValid,
                Set.of(),
                databasePresent ? "persistence-empty" : "persistence-absent"
        );
    }

    /** Creates evidence for an authority with durable state to recover. */
    @Nonnull
    public static TameworkPersistenceActivationEvidence active(
            Set<String> evidence
    ) {
        return new TameworkPersistenceActivationEvidence(
                PersistenceActivationMode.ACTIVE,
                true,
                true,
                evidence,
                "durable-state-present"
        );
    }

    /** Creates fail-closed evidence for an uncertain or malformed authority. */
    @Nonnull
    public static TameworkPersistenceActivationEvidence readOnly(
            boolean databasePresent,
            String diagnosticCode
    ) {
        return new TameworkPersistenceActivationEvidence(
                PersistenceActivationMode.READ_ONLY,
                databasePresent,
                false,
                Set.of(),
                diagnosticCode
        );
    }

    /** Returns the immutable startup disposition. */
    @Nonnull
    public PersistenceActivationMode mode() {
        return mode;
    }

    /** Returns whether the database file existed during the probe. */
    public boolean databasePresent() {
        return databasePresent;
    }

    /** Returns whether the expected schema identity was verified. */
    public boolean schemaValid() {
        return schemaValid;
    }

    /** Returns immutable bounded evidence labels for diagnostics and planning. */
    @Nonnull
    public Set<String> evidence() {
        return evidence;
    }

    /** Returns a stable bounded diagnostic code. */
    @Nonnull
    public String diagnosticCode() {
        return diagnosticCode;
    }

    /** Returns whether the caller may construct the mutating authority. */
    public boolean mutationAllowed() {
        return mode == PersistenceActivationMode.ACTIVE;
    }

    /** Returns whether no durable authority work is required. */
    public boolean hasDurableWork() {
        return !evidence.isEmpty();
    }

    /** Returns whether this authority must be reported without mutation. */
    public boolean readOnly() {
        return mode == PersistenceActivationMode.READ_ONLY;
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }
}
