package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.health.PersistenceCoverageRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitCatalog;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.recovery.ScopedPersistenceRecoveryCoordinator;
import com.alechilles.alecstamework.persistence.recovery.ScopedRecoveryTrigger;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds bounded, redacted persistence diagnostics without copying databases or world saves. */
public final class PersistenceDiagnosticsService {
    private static final int DEFAULT_INCIDENT_LIMIT = 100;
    private static final int JOURNAL_TAIL_BYTES = 256 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final TameworkPersistenceRuntime runtime;
    private final Path bundleDirectory;

    public PersistenceDiagnosticsService(@Nonnull TameworkPersistenceRuntime runtime,
                                         @Nonnull Path runtimeDataDirectory) {
        this.runtime = runtime;
        this.bundleDirectory = runtimeDataDirectory.toAbsolutePath().normalize()
                .resolve("Diagnostics").resolve("Persistence").resolve("bundles");
    }

    @Nonnull
    public HealthSnapshot health() throws Exception {
        var diagnostics = runtime.collectDiagnostics();
        var storage = runtime.getHealthService().getStorageHealthService().getState();
        Map<String, CoverageView> coverage = new java.util.TreeMap<>();
        for (var entry : runtime.getPersistenceCoverageRegistry().snapshot().entrySet()) {
            PersistenceCoverageRegistry.CoverageState state = entry.getValue();
            coverage.put(entry.getKey(), new CoverageView(
                    state.ready(), state.reason(), state.generation(), state.updatedAtMs()));
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
    public CompletableFuture<ScopedPersistenceRecoveryCoordinator.RecoveryResult> retry(
            @Nonnull String idOrPrefix) {
        try {
            Optional<PersistenceIncident> found =
                    runtime.getIncidentRepository().findByIdOrUniquePrefix(idOrPrefix);
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new ScopedPersistenceRecoveryCoordinator.RecoveryResult(
                                ScopedPersistenceRecoveryCoordinator.RecoveryStatus.NOT_FOUND,
                                "incident_not_found", null, null));
            }
            return runtime.getScopedRecoveryCoordinator().request(
                    found.orElseThrow().incidentId(), ScopedRecoveryTrigger.OPERATOR_REQUEST);
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Nonnull
    public BundleResult export(@Nullable String incidentIdOrPrefix) throws Exception {
        String supportId = UUID.randomUUID().toString();
        LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        members.put("health.json", jsonBytes(health()));
        if (incidentIdOrPrefix == null) {
            members.put("incidents.json", jsonBytes(incidents(false, DEFAULT_INCIDENT_LIMIT)));
        } else {
            Optional<IncidentDetails> details = incident(incidentIdOrPrefix);
            if (details.isEmpty()) throw new IllegalArgumentException("incident_not_found_or_ambiguous");
            members.put("incident.json", jsonBytes(details.orElseThrow()));
        }
        members.put("circuit-audit.json", jsonBytes(
                runtime.getFeatureCircuitRepository().listRecentAudit(100).stream()
                        .map(record -> new CircuitAuditView(
                                shortId(record.eventId()),
                                PersistenceFeatureCircuitCatalog.key(record.domain()),
                                record.previousEnabled(), record.enabled(), record.reasonCode(),
                                record.changedAtMs())).toList()));
        addJournalTail(members);
        byte[] manifest = manifest(supportId, members);
        Files.createDirectories(bundleDirectory);
        Path bundle = bundleDirectory.resolve("tamework-persistence-" + supportId + ".zip");
        writeZip(bundle, manifest, members);
        return new BundleResult(supportId, bundle, Files.size(bundle), members.size() + 1);
    }

    private void addJournalTail(Map<String, byte[]> members) throws Exception {
        Path directory = runtime.getIncidentJournal().directory();
        if (!Files.isDirectory(directory)) return;
        try (var stream = Files.list(directory)) {
            Optional<Path> latest = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(this::lastModified));
            if (latest.isPresent()) {
                members.put("incident-journal-tail.jsonl", readTail(latest.orElseThrow(), JOURNAL_TAIL_BYTES));
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

    private byte[] manifest(String supportId, Map<String, byte[]> members) throws Exception {
        List<MemberManifest> files = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : members.entrySet()) {
            files.add(new MemberManifest(
                    entry.getKey(), entry.getValue().length, sha256(entry.getValue()), "complete"));
        }
        BundleManifest manifest = new BundleManifest(
                1, supportId, Instant.now().toString(),
                "bounded_redacted_persistence_evidence",
                "No save or SQLite database is included. manifest.json is self-describing and not self-hashed.",
                List.copyOf(files));
        return jsonBytes(manifest);
    }

    private void writeZip(Path destination, byte[] manifest, Map<String, byte[]> members) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
                destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            writeEntry(zip, "manifest.json", manifest);
            for (Map.Entry<String, byte[]> entry : members.entrySet()) {
                writeEntry(zip, entry.getKey(), entry.getValue());
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

    public record CoverageView(boolean ready, String reason, long generation, long updatedAtMs) {
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

    private record MemberManifest(String name, long sizeBytes, String sha256, String status) {
    }

    private record BundleManifest(int formatVersion, String supportId, String createdAt,
                                  String scope, String note, List<MemberManifest> members) {
    }
}
