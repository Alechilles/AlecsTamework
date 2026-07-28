package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPersistenceReadiness;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStoreDiagnostics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Produces the sole redacted bonded status and diagnostic-bundle entry. */
public final class BondedCompanionDiagnosticContributor {
    private static final Gson JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final Set<String> EXPORT_FIELDS = Set.of(
            "readiness", "schemaVersion", "storedProfiles",
            "activeProfiles", "deadProfiles", "activeLeases",
            "pendingBoundedCleanups", "lastFailureCategory"
    );

    private final Supplier<BondedCompanionPersistenceReadiness> readiness;
    private final Supplier<BondedCompanionStoreDiagnostics> aggregates;
    private final int schemaVersion;
    private final AtomicReference<
            BondedCompanionDiagnosticSnapshot.FailureCategory> lastFailure =
            new AtomicReference<>(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.NONE
            );

    public BondedCompanionDiagnosticContributor(
            @Nonnull Supplier<BondedCompanionPersistenceReadiness> readiness,
            @Nonnull Supplier<BondedCompanionStoreDiagnostics> aggregates,
            int schemaVersion
    ) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        if (schemaVersion < 0) {
            throw new IllegalArgumentException("schemaVersion cannot be negative");
        }
        this.schemaVersion = schemaVersion;
    }

    /** Records one fixed category; free-form failure details are never retained. */
    public void recordFailure(
            @Nonnull BondedCompanionDiagnosticSnapshot.FailureCategory category
    ) {
        lastFailure.set(Objects.requireNonNull(category, "category"));
    }

    /** Returns a safe snapshot even when the isolated diagnostic query fails. */
    @Nonnull
    public BondedCompanionDiagnosticSnapshot snapshot() {
        try {
            BondedCompanionPersistenceReadiness state = readiness.get();
            String status = state.availability().available()
                    ? "READY" : closed(state) ? "CLOSED" : "UNAVAILABLE";
            BondedCompanionDiagnosticSnapshot.FailureCategory category =
                    lastFailure.get();
            if (category == BondedCompanionDiagnosticSnapshot.FailureCategory.NONE
                    && !state.availability().available()) {
                category = category(state.diagnosticCode());
            }
            return snapshot(status, state.availability().available()
                    ? schemaVersion : 0, aggregates.get(), category);
        } catch (RuntimeException failure) {
            lastFailure.set(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.DIAGNOSTIC
            );
            return snapshot(
                    "UNAVAILABLE", 0,
                    BondedCompanionStoreDiagnostics.empty(),
                    BondedCompanionDiagnosticSnapshot.FailureCategory.DIAGNOSTIC
            );
        }
    }

    /** Returns the fixed-name JSON entry consumed by the generic bundle aggregator. */
    @Nonnull
    public ExportEntry exportEntry() {
        byte[] content = (JSON.toJson(snapshot()) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        return new ExportEntry("bonded-companions.json", content);
    }

    /** Exposes the exact allowlist for contract tests and exporter validation. */
    @Nonnull
    public Set<String> exportFieldNames() {
        return EXPORT_FIELDS;
    }

    private BondedCompanionDiagnosticSnapshot snapshot(
            String status,
            int actualSchemaVersion,
            BondedCompanionStoreDiagnostics counts,
            BondedCompanionDiagnosticSnapshot.FailureCategory category
    ) {
        Objects.requireNonNull(counts, "counts");
        return new BondedCompanionDiagnosticSnapshot(
                status, actualSchemaVersion, counts.storedProfiles(),
                counts.activeProfiles(), counts.deadProfiles(),
                counts.activeLeases(), counts.pendingBoundedCleanups(), category
        );
    }

    private BondedCompanionDiagnosticSnapshot.FailureCategory category(
            String code
    ) {
        String normalized = code.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("schema") || normalized.contains("integrity")
                || normalized.contains("history")
                || normalized.contains("stored-record")) {
            return BondedCompanionDiagnosticSnapshot.FailureCategory.SCHEMA;
        }
        if (normalized.contains("closed")) {
            return BondedCompanionDiagnosticSnapshot.FailureCategory.CLOSED;
        }
        if (normalized.contains("storage") || normalized.contains("connection")) {
            return BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE;
        }
        return BondedCompanionDiagnosticSnapshot.FailureCategory.STARTUP;
    }

    private boolean closed(BondedCompanionPersistenceReadiness state) {
        return "bonded-persistence-closed".equals(state.diagnosticCode());
    }

    /** Immutable bounded bundle member. */
    public record ExportEntry(@Nonnull String name, @Nonnull byte[] content) {
        public ExportEntry {
            name = Objects.requireNonNull(name, "name");
            content = Objects.requireNonNull(content, "content").clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
