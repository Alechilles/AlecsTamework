package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDiagnosticsReader;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceDiagnosticsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceMetricsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Writes bounded, redacted replacement-persistence support bundles.
 *
 * <p>The export contains only the public diagnostics seam. It never copies the
 * SQLite database, a save, player names, stable identifiers, coordinates,
 * inventory payloads, or unrestricted logs.</p>
 */
public final class PersistenceDiagnosticExporter {
    static final int BUNDLE_SCHEMA = 1;
    static final int MAX_UNCOMPRESSED_BYTES = 4 * 1024 * 1024;
    private static final long MAX_COLLECTION_SECONDS = 10L;
    private static final Gson JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private final Path bundleDirectory;
    @Nullable private final PersistenceDiagnosticsReader diagnostics;
    private final AtomicReference<BondedCompanionDiagnosticContributor>
            bondedContributor = new AtomicReference<>();

    public PersistenceDiagnosticExporter(
            @Nonnull Path dataDirectory,
            @Nonnull PersistenceDiagnosticsReader diagnostics
    ) {
        this.bundleDirectory = Objects.requireNonNull(
                dataDirectory,
                "dataDirectory"
        ).toAbsolutePath().normalize().resolve("diagnostics");
        this.diagnostics = Objects.requireNonNull(
                diagnostics,
                "diagnostics"
        );
    }

    private PersistenceDiagnosticExporter(@Nonnull Path dataDirectory) {
        this.bundleDirectory = Objects.requireNonNull(
                dataDirectory, "dataDirectory"
        ).toAbsolutePath().normalize().resolve("diagnostics");
        this.diagnostics = null;
    }

    /** Creates an exporter that remains useful without generic persistence. */
    @Nonnull
    public static PersistenceDiagnosticExporter bondedOnly(
            @Nonnull Path dataDirectory,
            @Nonnull BondedCompanionDiagnosticContributor contributor
    ) {
        PersistenceDiagnosticExporter exporter =
                new PersistenceDiagnosticExporter(dataDirectory);
        exporter.bondedContributor.set(Objects.requireNonNull(
                contributor, "contributor"
        ));
        return exporter;
    }

    /**
     * Collects the isolated diagnostic snapshot and writes the bundle away
     * from the caller's thread.
     */
    @Nonnull
    public CompletionStage<ExportResult> export() {
        if (diagnostics == null) {
            return java.util.concurrent.CompletableFuture.supplyAsync(
                    this::exportBondedOnly
            );
        }
        final PublicPersistenceOperationalStatus status;
        final PublicPersistenceMetricsSnapshot metrics;
        try {
            status = diagnostics.status();
            metrics = diagnostics.metrics();
        } catch (RuntimeException unavailable) {
            return java.util.concurrent.CompletableFuture.supplyAsync(
                    this::exportBondedOnly
            );
        }
        return diagnostics.details().toCompletableFuture()
                .orTimeout(MAX_COLLECTION_SECONDS, TimeUnit.SECONDS)
                .handleAsync((read, failure) -> failure == null
                        && read instanceof PersistenceReadResult.Found<?>
                        ? export(status, metrics, read)
                        : exportBondedOnly());
    }

    /**
     * Builds a best-effort in-memory failure package for telemetry submission.
     * The caller owns its execution thread.
     */
    @Nonnull
    public FailurePackage exportFailurePackage(
            @Nonnull PersistenceFailureContext failure,
            int maxBytes
    ) {
        LinkedHashMap<String, byte[]> evidence = new LinkedHashMap<>();
        if (diagnostics != null) {
            try {
                evidence.put("operational-status.json", json(statusJson(diagnostics.status())));
            } catch (RuntimeException ignored) {
                // The failure package remains useful without runtime status.
            }
            try {
                evidence.put("metrics.json", metricsJson(diagnostics.metrics()));
            } catch (RuntimeException ignored) {
                // The failure package remains useful without metrics.
            }
            try {
                PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot> read =
                        diagnostics.details().toCompletableFuture()
                                .get(MAX_COLLECTION_SECONDS, TimeUnit.SECONDS);
                if (read instanceof PersistenceReadResult.Found<
                        PublicPersistenceDiagnosticsSnapshot> found) {
                    evidence.put("diagnostic-detail.json", json(found.value()));
                }
            } catch (Exception ignored) {
                // Detail reads are optional evidence.
            }
        }
        BondedCompanionDiagnosticContributor contributor = bondedContributor.get();
        if (contributor != null) {
            try {
                appendBondedEntry(evidence, contributor);
            } catch (RuntimeException ignored) {
                // Bonded evidence must not prevent a generic failure package.
            }
        }
        return buildFailurePackage(failure, maxBytes, evidence);
    }

    private ExportResult exportBondedOnly() {
        LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        BondedCompanionDiagnosticContributor contributor =
                bondedContributor.get();
        if (contributor == null) {
            throw new IllegalStateException("No diagnostic source is available");
        }
        appendBondedEntry(members, contributor);
        String supportId = UUID.randomUUID().toString().replace("-", "");
        try {
            return writeBundle(bundleDirectory, supportId, Instant.now(), members);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not write persistence diagnostic bundle", failure
            );
        }
    }

    /** Registers the isolated bonded diagnostic source at this aggregation boundary. */
    @Nonnull
    public AutoCloseable registerBondedContributor(
            @Nonnull BondedCompanionDiagnosticContributor contributor
    ) {
        Objects.requireNonNull(contributor, "contributor");
        if (!bondedContributor.compareAndSet(null, contributor)) {
            throw new IllegalStateException(
                    "A bonded diagnostic contributor is already registered"
            );
        }
        return () -> bondedContributor.compareAndSet(contributor, null);
    }

    /** Returns the optional aggregate-only bonded status used by debugdb. */
    @Nonnull
    public java.util.Optional<BondedCompanionDiagnosticSnapshot>
    bondedSnapshot() {
        BondedCompanionDiagnosticContributor contributor =
                bondedContributor.get();
        return contributor == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(contributor.snapshot());
    }

    private ExportResult export(
            PublicPersistenceOperationalStatus status,
            PublicPersistenceMetricsSnapshot metrics,
            PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot> read
    ) {
        if (!(read instanceof PersistenceReadResult.Found<
                PublicPersistenceDiagnosticsSnapshot> found)) {
            throw new IllegalStateException(
                    "Replacement persistence detail is unavailable"
            );
        }
        String supportId = UUID.randomUUID().toString()
                .replace("-", "");
        LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        members.put("operational-status.json", json(statusJson(status)));
        members.put("metrics.json", metricsJson(metrics));
        members.put("diagnostic-detail.json", json(found.value()));
        BondedCompanionDiagnosticContributor contributor =
                bondedContributor.get();
        if (contributor != null) {
            appendBondedEntry(members, contributor);
        }
        try {
            return writeBundle(
                    bundleDirectory,
                    supportId,
                    Instant.now(),
                    members
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not write persistence diagnostic bundle",
                    failure
            );
        }
    }

    static void appendBondedEntry(
            Map<String, byte[]> members,
            BondedCompanionDiagnosticContributor contributor
    ) {
        Objects.requireNonNull(members, "members");
        BondedCompanionDiagnosticContributor.ExportEntry entry =
                Objects.requireNonNull(contributor, "contributor")
                        .exportEntry();
        if (!"bonded-companions.json".equals(entry.name())) {
            throw new IllegalArgumentException(
                    "Unexpected bonded diagnostic member name"
            );
        }
        members.put(entry.name(), entry.content());
    }

    /** Serializes only the bounded public metrics model for support bundles. */
    static byte[] metricsJson(
            @Nonnull PublicPersistenceMetricsSnapshot metrics
    ) {
        return json(Objects.requireNonNull(metrics, "metrics"));
    }

    static ExportResult writeBundle(
            Path bundleDirectory,
            String supportId,
            Instant createdAt,
            Map<String, byte[]> members
    ) throws IOException {
        Objects.requireNonNull(bundleDirectory, "bundleDirectory");
        if (supportId == null || supportId.isBlank()
                || createdAt == null || members == null
                || members.isEmpty()) {
            throw new IllegalArgumentException(
                    "Complete diagnostic bundle input is required"
            );
        }
        long uncompressedBytes = members.values().stream()
                .mapToLong(value -> value == null ? 0L : value.length)
                .sum();
        if (members.values().stream().anyMatch(Objects::isNull)
                || uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
            throw new IllegalArgumentException(
                    "Diagnostic bundle exceeds its bounded evidence budget"
            );
        }
        byte[] bundle = bundleBytes(supportId, createdAt, members, uncompressedBytes);
        Files.createDirectories(bundleDirectory);
        Path destination = bundleDirectory.resolve(
                "tamework-persistence-" + supportId + ".zip"
        );
        Files.write(
                destination,
                bundle,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        return new ExportResult(
                supportId,
                destination.toAbsolutePath().normalize(),
                Files.size(destination),
                members.size() + 1
        );
    }

    static FailurePackage buildFailurePackage(
            @Nonnull PersistenceFailureContext failure,
            int maxBytes,
            @Nonnull Map<String, byte[]> evidence
    ) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(evidence, "evidence");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("A positive failure package limit is required");
        }
        String supportId = UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.now();
        LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        members.put("failure.json", failureJson(failure));
        evidence.forEach((name, content) -> {
            if (content != null && !"failure.json".equals(name)) members.put(name, content);
        });

        byte[] bundle = boundedFailureBundle(supportId, createdAt, members, maxBytes);
        return new FailurePackage(supportId, bundle, countZipMembers(members));
    }

    @Nonnull
    private static byte[] boundedFailureBundle(
            @Nonnull String supportId,
            @Nonnull Instant createdAt,
            @Nonnull LinkedHashMap<String, byte[]> members,
            int maxBytes
    ) {
        byte[] bundle = bundleBytes(supportId, createdAt, members, evidenceBytes(members));
        if (bundle.length <= maxBytes) return bundle;

        members.remove("diagnostic-detail.json");
        bundle = bundleBytes(supportId, createdAt, members, evidenceBytes(members));
        if (bundle.length <= maxBytes) return bundle;

        members.remove("bonded-companions.json");
        bundle = bundleBytes(supportId, createdAt, members, evidenceBytes(members));
        if (bundle.length <= maxBytes) return bundle;

        members.keySet().removeIf(name -> !Set.of(
                "failure.json", "operational-status.json", "metrics.json"
        ).contains(name));
        bundle = bundleBytes(supportId, createdAt, members, evidenceBytes(members));
        if (bundle.length <= maxBytes) return bundle;

        throw new IllegalArgumentException("Minimal diagnostic failure package exceeds its limit");
    }

    private static long evidenceBytes(@Nonnull Map<String, byte[]> members) {
        long bytes = members.values().stream().mapToLong(value -> value.length).sum();
        if (bytes > MAX_UNCOMPRESSED_BYTES) {
            throw new IllegalArgumentException("Diagnostic bundle exceeds its bounded evidence budget");
        }
        return bytes;
    }

    @Nonnull
    private static byte[] bundleBytes(
            @Nonnull String supportId,
            @Nonnull Instant createdAt,
            @Nonnull Map<String, byte[]> members,
            long uncompressedBytes
    ) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                writeEntry(zip, "manifest.json", manifest(
                        supportId, createdAt, members, uncompressedBytes
                ));
                for (Map.Entry<String, byte[]> member : members.entrySet()) {
                    writeEntry(zip, member.getKey(), member.getValue());
                }
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not build persistence diagnostic bundle", failure);
        }
    }

    private static int countZipMembers(@Nonnull Map<String, byte[]> members) {
        return members.size() + 1;
    }

    @Nonnull
    private static byte[] failureJson(@Nonnull PersistenceFailureContext failure) {
        JsonObject json = new JsonObject();
        json.addProperty("eventName", failure.eventName());
        json.addProperty("incidentHash", sha256(
                failure.incidentKey().getBytes(StandardCharsets.UTF_8)
        ).substring(0, 16));
        json.addProperty("operation", failure.operation());
        json.addProperty("phase", failure.phase());
        json.addProperty("reason", failure.reason());
        Throwable cause = failure.cause();
        if (cause != null) {
            json.addProperty("exceptionClass", cause.getClass().getName());
            JsonArray frames = new JsonArray();
            StackTraceElement[] trace = cause.getStackTrace();
            for (int index = 0; index < Math.min(trace.length, 20); index++) {
                StackTraceElement frame = trace[index];
                frames.add(frame.getClassName() + "#" + frame.getMethodName());
            }
            json.add("stackFrames", frames);
        }
        return json(json);
    }

    private static JsonObject statusJson(
            PublicPersistenceOperationalStatus status
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("engine", status.engine().name());
        json.addProperty("storageMode", status.storageMode().name());
        json.addProperty(
                "targetOrigin",
                status.targetOrigin().map(Enum::name).orElse(null)
        );
        if (status.schemaVersion().isPresent()) {
            json.addProperty(
                    "schemaVersion",
                    status.schemaVersion().getAsInt()
            );
        }
        json.addProperty(
                "startupReadiness",
                status.startup().readiness().name()
        );
        json.addProperty(
                "startupDetail",
                status.startup().detail()
        );
        json.add("startupNodes", JSON.toJsonTree(status.startupNodes()));
        json.add("checkpoint", JSON.toJsonTree(status.lastCheckpoint()));
        json.add("guidance", JSON.toJsonTree(status.guidance()));
        return json;
    }

    private static byte[] manifest(
            String supportId,
            Instant createdAt,
            Map<String, byte[]> members,
            long uncompressedBytes
    ) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("bundleSchema", BUNDLE_SCHEMA);
        manifest.addProperty("supportId", supportId);
        manifest.addProperty("createdAt", createdAt.toString());
        manifest.addProperty(
                "scope",
                "bounded_redacted_replacement_persistence_evidence"
        );
        manifest.addProperty(
                "uncompressedEvidenceBytes",
                uncompressedBytes
        );
        manifest.addProperty(
                "excluded",
                "SQLite database, save data, player identity, coordinates, "
                        + "inventory payloads, secrets, and unrestricted logs"
        );
        JsonArray files = new JsonArray();
        members.forEach((name, content) -> {
            JsonObject file = new JsonObject();
            file.addProperty("name", name);
            file.addProperty("bytes", content.length);
            file.addProperty("sha256", sha256(content));
            files.add(file);
        });
        manifest.add("members", files);
        return json(manifest);
    }

    private static void writeEntry(
            ZipOutputStream zip,
            String name,
            byte[] content
    ) throws IOException {
        if (name == null || name.isBlank()
                || name.contains("..") || name.startsWith("/")
                || name.startsWith("\\")) {
            throw new IllegalArgumentException(
                    "Diagnostic bundle member name is unsafe"
            );
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static byte[] json(Object value) {
        return (JSON.toJson(value) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Location and bounded size of one completed support bundle. */
    public record ExportResult(
            @Nonnull String supportId,
            @Nonnull Path path,
            long sizeBytes,
            int memberCount
    ) {
        public ExportResult {
            if (supportId == null || supportId.isBlank() || path == null
                    || sizeBytes < 1 || memberCount < 2) {
                throw new IllegalArgumentException(
                        "Complete diagnostic export result is required"
                );
            }
        }
    }

    /** In-memory redacted package for one automatic diagnostic bundle. */
    public record FailurePackage(@Nonnull String supportId,
                                 @Nonnull byte[] content,
                                 int memberCount) {
        public FailurePackage {
            if (supportId == null || supportId.isBlank() || content == null
                    || content.length < 1 || memberCount < 2) {
                throw new IllegalArgumentException("Complete failure package is required");
            }
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
