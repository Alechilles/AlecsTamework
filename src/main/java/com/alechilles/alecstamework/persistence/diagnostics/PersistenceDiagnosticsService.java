package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitCatalog;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.recovery.ScopedPersistenceRecoveryCoordinator;
import com.alechilles.alecstamework.persistence.recovery.ScopedRecoveryTrigger;
import com.alechilles.alecstamework.metrics.CrashTelemetryDiagnostics;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.metrics.TameworkPersistenceTelemetry;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds bounded, redacted persistence diagnostics without copying databases or world saves. */
public final class PersistenceDiagnosticsService {
    private static final int DEFAULT_INCIDENT_LIMIT = 100;
    private static final int JOURNAL_TAIL_BYTES = 256 * 1024;
    static final int MAX_UNCOMPRESSED_BUNDLE_BYTES = 4 * 1024 * 1024;
    static final long MAX_EXPORT_MILLIS = 10_000L;
    private static final int MANIFEST_RESERVE_BYTES = 64 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final TameworkPersistenceRuntime runtime;
    private final Path bundleDirectory;
    private final CrashTelemetryService telemetry;

    public PersistenceDiagnosticsService(@Nonnull TameworkPersistenceRuntime runtime,
                                         @Nonnull Path runtimeDataDirectory) {
        this(runtime, runtimeDataDirectory, null);
    }

    public PersistenceDiagnosticsService(@Nonnull TameworkPersistenceRuntime runtime,
                                         @Nonnull Path runtimeDataDirectory,
                                         @Nullable CrashTelemetryService telemetry) {
        this.runtime = runtime;
        this.bundleDirectory = runtimeDataDirectory.toAbsolutePath().normalize()
                .resolve("Diagnostics").resolve("Persistence").resolve("bundles");
        this.telemetry = telemetry;
    }

    @Nonnull
    public HealthSnapshot health() throws Exception {
        var diagnostics = runtime.collectDiagnostics();
        var storage = runtime.getHealthService().getStorageHealthService().getState();
        Map<String, CoverageView> coverage = new java.util.TreeMap<>();
        for (var entry : runtime.getPersistenceCoverageRegistry().snapshot().entrySet()) {
            PersistenceCoverageRegistry.CoverageState state = entry.getValue();
            coverage.put(entry.getKey(), new CoverageView(
                    state.status().name(), state.ready(), state.reason(),
                    state.generation(), state.updatedAtMs(),
                    state.coveredScopeHashes().size(), state.absenceAuthoritative(),
                    state.nextSafeTrigger()));
        }
        Map<String, CircuitView> circuits = new LinkedHashMap<>();
        Map<com.alechilles.alecstamework.persistence.incidents.PersistenceDomain,
                PersistenceFeatureCircuitRegistry.CircuitState> circuitStates =
                runtime.getFeatureCircuitRegistry().snapshot();
        for (String key : PersistenceFeatureCircuitCatalog.keys()) {
            var domain = PersistenceFeatureCircuitCatalog.resolve(key);
            var state = circuitStates.get(domain);
            circuits.put(key, new CircuitView(
                    state == null || state.enabled(),
                    domain != null && runtime.getFeatureCircuitRegistry().isEnabled(domain),
                    state != null ? state.reasonCode() : null,
                    state != null ? state.updatedAtMs() : 0L));
        }
        return new HealthSnapshot(
                storage.status().name(), storage.reason(), shortId(storage.incidentId()),
                storage.changedAtMs(), diagnostics.totalBytes(),
                diagnostics.queueMetrics().queueDepth(),
                diagnostics.queueMetrics().failedBatches(),
                runtime.getQuarantineRegistry().snapshot().size(),
                runtime.getIncidentRepository().listOpen(DEFAULT_INCIDENT_LIMIT).size(),
                Map.copyOf(coverage), Map.copyOf(circuits),
                runtime.getIncidentJournal().droppedRecords());
    }

    @Nonnull
    public List<IncidentView> incidents(boolean openOnly, int limit) throws Exception {
        return runtime.getIncidentRepository().listRecent(openOnly, limit).stream()
                .map(this::incidentView).toList();
    }

    @Nonnull
    public Optional<IncidentDetails> incident(@Nonnull String idOrPrefix) throws Exception {
        Optional<PersistenceIncident> found =
                runtime.getIncidentRepository().findByIdOrUniquePrefix(idOrPrefix);
        if (found.isEmpty()) return Optional.empty();
        PersistenceIncident incident = found.orElseThrow();
        List<SafeScopeView> scopes = runtime.getIncidentRepository()
                .listScopes(incident.incidentId()).stream().map(this::safeScope).toList();
        List<QuarantineView> quarantines = runtime.getQuarantineRepository()
                .listActiveForIncident(incident.incidentId()).stream().map(this::quarantineView).toList();
        return Optional.of(new IncidentDetails(incidentView(incident), scopes, quarantines));
    }

    @Nonnull
    public CompletableFuture<RetryResult> retry(
            @Nonnull String idOrPrefix) {
        String normalized = idOrPrefix.trim();
        var storage = runtime.getHealthService().getStorageHealthService().getState();
        if (storage.incidentId() != null && storage.incidentId().startsWith(normalized)) {
            return runtime.getStorageRecoveryCoordinator().requestNow().thenApply(result ->
                    new RetryResult(result.status().name(), result.reason(),
                            runtime.getStorageRecoveryCoordinator().attempts()));
        }
        try {
            Optional<PersistenceIncident> found =
                    runtime.getIncidentRepository().findByIdOrUniquePrefix(normalized);
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new RetryResult("NOT_FOUND", "incident_not_found", null));
            }
            return runtime.getScopedRecoveryCoordinator().request(
                    found.orElseThrow().incidentId(), ScopedRecoveryTrigger.OPERATOR_REQUEST)
                    .thenApply(result -> new RetryResult(
                            result.status().name(), result.reason(), result.attempts()));
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Nonnull
    public BundleResult export(@Nullable String incidentIdOrPrefix) throws Exception {
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_EXPORT_MILLIS);
        String supportId = UUID.randomUUID().toString();
        LinkedHashMap<String, BundleMember> members = new LinkedHashMap<>();
        members.put("health.json", complete(jsonBytes(health())));
        PersistenceDiagnosticDatabaseSnapshotReader.Snapshot database;
        try {
            database = new PersistenceDiagnosticDatabaseSnapshotReader(
                    new SqliteConnectionManager(runtime.getSqlitePath()),
                    runtime.getPersistenceScopeFactory()).read();
        } catch (Exception failure) {
            database = PersistenceDiagnosticDatabaseSnapshotReader.unavailableSnapshot(
                    "database_snapshot_failed:" + failure.getClass().getSimpleName());
        }
        members.put("incidents.json", section(database.incidents()));
        members.put("quarantines.json", section(database.quarantines()));
        members.put("operations.json", section(database.operations()));
        members.put("integrity.json", section(database.integrity()));
        members.put("reconciliation.json", section(database.reconciliation()));
        if (incidentIdOrPrefix != null) {
            Optional<IncidentDetails> details = incident(incidentIdOrPrefix);
            if (details.isEmpty()) throw new IllegalArgumentException("incident_not_found_or_ambiguous");
            members.put("selected-incident.json", complete(jsonBytes(details.orElseThrow())));
        }
        members.put("circuit-audit.json", complete(jsonBytes(
                runtime.getFeatureCircuitRepository().listRecentAudit(100).stream()
                        .map(record -> new CircuitAuditView(
                                shortId(record.eventId()),
                                PersistenceFeatureCircuitCatalog.key(record.domain()),
                                record.previousEnabled(), record.enabled(), record.reasonCode(),
                                record.changedAtMs())).toList())));
        members.put("settings.json", complete(jsonBytes(settings())));
        members.put("telemetry.json", complete(jsonBytes(telemetry())));
        members.put("logs.txt", new BundleMember(
                "unavailable", "runtime_log_provider_unavailable",
                "Server log discovery is not performed by Tamework. Use the sanitized incident journal included in this bundle.\n"
                        .getBytes(StandardCharsets.UTF_8)));
        if (withinDeadline(deadlineNs)) {
            addJournalTail(members);
        } else {
            members.put("breadcrumbs.jsonl", partial("bundle_time_limit"));
        }
        enforceLimits(members, deadlineNs);
        byte[] manifest = manifest(supportId, members);
        Files.createDirectories(bundleDirectory);
        Path bundle = bundleDirectory.resolve("tamework-persistence-" + supportId + ".zip");
        writeZip(bundle, manifest, members);
        return new BundleResult(supportId, bundle, Files.size(bundle), members.size() + 1);
    }

    private void enforceLimits(Map<String, BundleMember> members, long deadlineNs) {
        int remaining = MAX_UNCOMPRESSED_BUNDLE_BYTES - MANIFEST_RESERVE_BYTES;
        boolean timeExpired = !withinDeadline(deadlineNs);
        for (Map.Entry<String, BundleMember> entry : members.entrySet()) {
            BundleMember member = entry.getValue();
            if (timeExpired || member.content().length > remaining) {
                entry.setValue(partial(timeExpired ? "bundle_time_limit" : "bundle_size_limit"));
                continue;
            }
            remaining -= member.content().length;
            timeExpired = !withinDeadline(deadlineNs);
        }
    }

    private BundleMember partial(String reason) {
        return new BundleMember("partial", reason, new byte[0]);
    }

    private boolean withinDeadline(long deadlineNs) {
        return System.nanoTime() <= deadlineNs;
    }

    private void addJournalTail(Map<String, BundleMember> members) throws Exception {
        Path directory = runtime.getIncidentJournal().directory();
        if (!Files.isDirectory(directory)) {
            members.put("breadcrumbs.jsonl", new BundleMember(
                    "unavailable", "diagnostics_directory_missing", new byte[0]));
            return;
        }
        try (var stream = Files.list(directory)) {
            Optional<Path> latest = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(this::lastModified));
            if (latest.isPresent()) {
                members.put("breadcrumbs.jsonl", complete(
                        readTail(latest.orElseThrow(), JOURNAL_TAIL_BYTES)));
            } else {
                members.put("breadcrumbs.jsonl", new BundleMember(
                        "unavailable", "no_local_incident_records", new byte[0]));
            }
        }
    }

    private byte[] readTail(Path path, int maxBytes) throws Exception {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, maxBytes);
            channel.position(Math.max(0L, size - length));
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Continue until the bounded tail is consumed.
            }
            return buffer.array();
        }
    }

    private byte[] manifest(String supportId, Map<String, BundleMember> members) throws Exception {
        List<MemberManifest> files = new ArrayList<>();
        for (Map.Entry<String, BundleMember> entry : members.entrySet()) {
            BundleMember member = entry.getValue();
            files.add(new MemberManifest(
                    entry.getKey(), member.content().length, sha256(member.content()),
                    member.status(), member.error()));
        }
        BundleManifest manifest = new BundleManifest(
                3, supportId, Instant.now().toString(),
                "bounded_redacted_persistence_evidence",
                TameworkPersistenceTelemetry.PERSISTENCE_SUBSYSTEM_VERSION,
                implementationVersion(),
                MAX_UNCOMPRESSED_BUNDLE_BYTES,
                MAX_EXPORT_MILLIS,
                "No save or SQLite database is included. Tamework never creates whole-save backups. "
                        + "manifest.json is self-describing and not self-hashed.",
                List.copyOf(files));
        return jsonBytes(manifest);
    }

    private void writeZip(Path destination, byte[] manifest,
                          Map<String, BundleMember> members) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            writeEntry(zip, "manifest.json", manifest);
            for (Map.Entry<String, BundleMember> entry : members.entrySet()) {
                writeEntry(zip, entry.getKey(), entry.getValue().content());
            }
        }
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private IncidentView incidentView(PersistenceIncident incident) {
        return new IncidentView(
                incident.incidentId(), shortId(incident.incidentId()), incident.status().name(),
                incident.severity().name(), incident.domain().name(), incident.phase().name(),
                incident.reasonCode(), incident.failureClass().name(), incident.disposition().name(),
                incident.openedAtMs(), incident.lastSeenAtMs(), incident.occurrenceCount(),
                incident.recoveryAttempts(), incident.lastErrorType(), incident.resolutionCode());
    }

    private SafeScopeView safeScope(PersistenceScope scope) {
        return new SafeScopeView(scope.type().name(), scope.scopeHash(), scope.authorityDimension());
    }

    private QuarantineView quarantineView(PersistenceQuarantineRecord record) {
        return new QuarantineView(
                shortId(record.quarantineId()), record.domain().name(), record.reasonCode(),
                record.state().name(), safeScope(record.scope()), record.generation(), record.updatedAtMs());
    }

    private byte[] jsonBytes(Object value) {
        return (JSON.toJson(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private BundleMember section(PersistenceDiagnosticDatabaseSnapshotReader.Section<?> section) {
        return new BundleMember(section.status(), section.error(), jsonBytes(section));
    }

    private BundleMember complete(byte[] content) {
        return new BundleMember("complete", null, content);
    }

    private SettingsView settings() {
        Map<String, CircuitView> circuits = new LinkedHashMap<>();
        Map<com.alechilles.alecstamework.persistence.incidents.PersistenceDomain,
                PersistenceFeatureCircuitRegistry.CircuitState> states =
                runtime.getFeatureCircuitRegistry().snapshot();
        for (String key : PersistenceFeatureCircuitCatalog.keys()) {
            var domain = PersistenceFeatureCircuitCatalog.resolve(key);
            var state = states.get(domain);
            circuits.put(key, new CircuitView(
                    state == null || state.enabled(),
                    domain != null && runtime.getFeatureCircuitRegistry().isEnabled(domain),
                    state == null ? null : state.reasonCode(),
                    state == null ? 0L : state.updatedAtMs()));
        }
        return new SettingsView("local_config_plus_durable_operator_override", Map.copyOf(circuits));
    }

    private TelemetryView telemetry() {
        if (telemetry == null) {
            return new TelemetryView(false, false, 0, false, "unavailable", 0L);
        }
        try {
            CrashTelemetryDiagnostics diagnostics = telemetry.diagnostics();
            boolean endpointAvailable = diagnostics.endpoint() != null
                    && !diagnostics.endpoint().isBlank()
                    && !"<disabled>".equals(diagnostics.endpoint());
            return new TelemetryView(diagnostics.enabled(), endpointAvailable,
                    diagnostics.pendingReports(), diagnostics.flushInProgress(),
                    flushResultClass(diagnostics.lastFlushResult()), diagnostics.lastFlushEpochMs());
        } catch (Throwable ignored) {
            return new TelemetryView(false, false, 0, false, "diagnostics_failed", 0L);
        }
    }

    private String flushResultClass(String result) {
        if (result == null || result.isBlank() || result.contains("No flush")) return "not_attempted";
        String lower = result.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("success") || lower.contains("complete")) return "success";
        if (lower.contains("fail") || lower.contains("reject") || lower.contains("error")) return "failure";
        return "other";
    }

    private String implementationVersion() {
        String version = PersistenceDiagnosticsService.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @Nullable
    private static String shortId(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        return value.substring(0, Math.min(8, value.length()));
    }

    public record HealthSnapshot(String storageStatus, String storageReason, String storageIncidentId,
                                 long storageChangedAtMs, long sqliteTotalBytes, int writeQueueDepth,
                                 long failedWriteBatches, int activeQuarantines, int openIncidents,
                                 Map<String, CoverageView> coverage, Map<String, CircuitView> circuits,
                                 long droppedDiagnosticRecords) {
    }

    public record CoverageView(String status, boolean ready, String reason,
                               long generation, long updatedAtMs,
                               int coveredScopeCount, boolean absenceAuthoritative,
                               String nextSafeTrigger) {
    }

    public record CircuitView(boolean configuredEnabled, boolean effectiveEnabled,
                              String reason, long updatedAtMs) {
    }

    public record IncidentView(String incidentId, String shortId, String status, String severity,
                               String domain, String phase, String reasonCode, String failureClass,
                               String disposition, long openedAtMs, long lastSeenAtMs,
                               long occurrenceCount, long recoveryAttempts, String lastErrorType,
                               String resolutionCode) {
    }

    public record SafeScopeView(String type, String scopeHash, String authorityDimension) {
    }

    public record QuarantineView(String quarantineId, String domain, String reasonCode,
                                 String state, SafeScopeView scope, long generation, long updatedAtMs) {
    }

    public record IncidentDetails(IncidentView incident, List<SafeScopeView> scopes,
                                  List<QuarantineView> quarantines) {
    }

    public record CircuitAuditView(String eventId, String domain, Boolean previousEnabled,
                                   boolean enabled, String reasonCode, long changedAtMs) {
    }

    public record BundleResult(String supportId, Path path, long sizeBytes, int memberCount) {
    }

    public record RetryResult(String status, String reason, Long attempts) {
    }

    private record BundleMember(String status, String error, byte[] content) {
    }

    private record MemberManifest(String name, long sizeBytes, String sha256,
                                  String status, String error) {
    }

    private record BundleManifest(int formatVersion, String supportId, String createdAt,
                                  String scope, int schemaVersion, String pluginVersion,
                                  int maxUncompressedBundleBytes, long maxExportMillis,
                                  String note, List<MemberManifest> members) {
    }

    private record SettingsView(String precedence, Map<String, CircuitView> circuits) {
    }

    private record TelemetryView(boolean enabled, boolean endpointAvailable, int pendingReports,
                                 boolean flushInProgress, String lastFlushResult,
                                 long lastFlushAtMs) {
    }
}
