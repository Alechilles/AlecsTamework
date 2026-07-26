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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
        members.put("metrics.json", json(metrics));
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
        byte[] manifest = manifest(
                supportId,
                createdAt,
                members,
                uncompressedBytes
        );
        Files.createDirectories(bundleDirectory);
        Path destination = bundleDirectory.resolve(
                "tamework-persistence-" + supportId + ".zip"
        );
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                )
        )) {
            writeEntry(zip, "manifest.json", manifest);
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                writeEntry(zip, member.getKey(), member.getValue());
            }
        }
        return new ExportResult(
                supportId,
                destination.toAbsolutePath().normalize(),
                Files.size(destination),
                members.size() + 1
        );
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
}
