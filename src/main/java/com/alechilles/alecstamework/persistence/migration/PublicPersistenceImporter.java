package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Builds and atomically publishes the fresh replacement database from public v2-v4 sources.
 *
 * <p>Classification and planning occur before any target-side write. Unreleased development
 * sources are refused before the admission lock or replacement target is created.</p>
 */
public final class PublicPersistenceImporter {
    private final LongSupplier clock;
    private final SqliteReadOnlySnapshotter snapshotter = new SqliteReadOnlySnapshotter();
    private final LegacySourceClassifier classifier = new LegacySourceClassifier();
    private final LegacyPublicDataReader reader = new LegacyPublicDataReader();
    private final PublicImportPlanner planner = new PublicImportPlanner();
    private final PublicImportManifestFactory manifests = new PublicImportManifestFactory();
    private final PublicImportSqlWriter writer = new PublicImportSqlWriter();
    private final PublicImportVerifier verifier = new PublicImportVerifier();
    private final ImportTargetPublisher publisher = new ImportTargetPublisher();

    public PublicPersistenceImporter() {
        this(System::currentTimeMillis);
    }

    PublicPersistenceImporter(@Nonnull LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Import clock is required");
        }
        this.clock = clock;
    }

    /** Imports {@code sourcePath} to a different, fresh {@code targetPath}. */
    @Nonnull
    public PublicImportResult importSource(
            @Nonnull Path sourcePath,
            @Nonnull Path targetPath
    ) {
        if (sourcePath == null || targetPath == null) {
            throw new IllegalArgumentException("Source and target paths are required");
        }
        Path source = sourcePath.toAbsolutePath().normalize();
        Path target = targetPath.toAbsolutePath().normalize();
        if (source.equals(target)) {
            return failed("SOURCE_TARGET_PATH_COLLISION",
                    new IllegalArgumentException("Source and target paths must differ"));
        }
        if (!Files.isRegularFile(source)) {
            return refused(LegacySourceKind.NO_SOURCE, 0, "NO_SOURCE");
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("tamework-public-import-");
            try (SqliteReadOnlySnapshotter.Snapshot snapshot =
                         snapshotter.create(source, workspace)) {
                return importSnapshot(source, target, snapshot);
            }
        } catch (Exception failure) {
            return failed("PUBLIC_IMPORT_INFRASTRUCTURE_FAILED", failure);
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private PublicImportResult importSnapshot(
            Path source,
            Path target,
            SqliteReadOnlySnapshotter.Snapshot snapshot
    ) throws Exception {
        LegacySourceClassification classification = classifier.classifySnapshot(snapshot);
        if (!classification.importablePublicSource()) {
            return refused(
                    classification.kind(),
                    classification.schemaVersion(),
                    classification.diagnosticCode()
            );
        }
        long importedAtMs = clock.getAsLong();
        LegacyPublicData data = readSource(snapshot.path(), classification.schemaVersion());
        PublicImportPlan plan;
        try {
            plan = planner.plan(data, snapshot.fingerprint(), importedAtMs);
        } catch (PublicImportException refusal) {
            return refused(
                    LegacySourceKind.AMBIGUOUS,
                    classification.schemaVersion(),
                    refusal.code()
            );
        }
        PublicImportManifest manifest = manifests.create(
                plan,
                snapshot.fingerprint(),
                classification.schemaVersion(),
                source.getFileName().toString(),
                importedAtMs
        );
        return publishPlan(target, plan, manifest);
    }

    private LegacyPublicData readSource(Path snapshot, int schemaVersion) throws Exception {
        try (Connection connection =
                     new SqliteConnectionFactory(snapshot).openReadConnection()) {
            return reader.read(connection, schemaVersion);
        }
    }

    private PublicImportResult publishPlan(
            Path target,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) {
        Path parent = target.getParent();
        if (parent == null) {
            return failed("TARGET_DIRECTORY_MISSING",
                    new IllegalArgumentException("Target requires a parent directory"));
        }
        Path temporary = target.resolveSibling(
                target.getFileName() + ".importing." + UUID.randomUUID()
        );
        try (ImportAdmissionLock ignored = ImportAdmissionLock.acquire(parent)) {
            PublicImportResult existing = verifyExisting(target, plan, manifest);
            if (existing != null) {
                return existing;
            }
            PublicImportResult recovered = recoverTemporaryTarget(target, plan, manifest);
            if (recovered != null) {
                return recovered;
            }
            buildTemporaryTarget(temporary, plan, manifest);
            publisher.publish(temporary, target);
            verifyPublishedTarget(target, plan, manifest);
            Optional<Path> report = publisher.writeReport(target, manifest);
            return new PublicImportResult.Imported(target, manifest.importId(), report);
        } catch (PublicImportException refusal) {
            return refused(LegacySourceKind.AMBIGUOUS,
                    manifest.sourceSchemaVersion(), refusal.code());
        } catch (Exception failure) {
            return failed("PUBLIC_IMPORT_PUBLICATION_FAILED", failure);
        } finally {
            deleteTargetFiles(temporary);
        }
    }

    /** Publishes an already decoded and canonicalized source through the same atomic target gate. */
    PublicImportResult publishPrepared(
            Path targetPath,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) {
        if (targetPath == null || plan == null || manifest == null) {
            throw new IllegalArgumentException("Prepared import target, plan, and manifest required");
        }
        return publishPlan(targetPath.toAbsolutePath().normalize(), plan, manifest);
    }

    private PublicImportResult verifyExisting(
            Path target,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) throws Exception {
        if (!Files.exists(target)) {
            return null;
        }
        if (!Files.isRegularFile(target)) {
            return refused(LegacySourceKind.AMBIGUOUS,
                    manifest.sourceSchemaVersion(), "TARGET_PATH_NOT_REGULAR_FILE");
        }
        try {
            verifyPublishedTarget(target, plan, manifest);
            publisher.writeReport(target, manifest);
            return new PublicImportResult.AlreadyImported(target, manifest.importId());
        } catch (Exception mismatch) {
            return refused(LegacySourceKind.AMBIGUOUS,
                    manifest.sourceSchemaVersion(), "EXISTING_TARGET_MISMATCH");
        }
    }

    private PublicImportResult recoverTemporaryTarget(
            Path target,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) throws Exception {
        for (Path candidate : temporaryTargets(target)) {
            try {
                checkpointAfterUnknownCommit(candidate);
                verifyPublishedTarget(candidate, plan, manifest);
            } catch (Exception incomplete) {
                deleteTargetFiles(candidate);
                continue;
            }
            publisher.publish(candidate, target);
            deleteTargetFiles(candidate);
            verifyPublishedTarget(target, plan, manifest);
            Optional<Path> report = publisher.writeReport(target, manifest);
            deleteOtherTemporaryTargets(target);
            return new PublicImportResult.Imported(target, manifest.importId(), report);
        }
        return null;
    }

    private java.util.List<Path> temporaryTargets(Path target) throws Exception {
        Path parent = target.getParent();
        String prefix = target.getFileName() + ".importing.";
        try (var entries = Files.list(parent)) {
            return entries
                    .filter(path -> Files.isRegularFile(
                            path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isAttemptName(path.getFileName().toString(), prefix))
                    .sorted()
                    .toList();
        }
    }

    private boolean isAttemptName(String fileName, String prefix) {
        if (!fileName.startsWith(prefix)) {
            return false;
        }
        try {
            UUID.fromString(fileName.substring(prefix.length()));
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private void deleteOtherTemporaryTargets(Path target) {
        try {
            for (Path candidate : temporaryTargets(target)) {
                deleteTargetFiles(candidate);
            }
        } catch (Exception ignored) {
            // Extra importer-owned attempts are non-canonical and never opened by bootstrap.
        }
    }

    private void buildTemporaryTarget(
            Path temporary,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(temporary);
        initializeSchema(connections);
        boolean commitAttempted = false;
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            try {
                writer.write(connection, plan, manifest);
                verifier.verify(connection, plan, manifest);
                commitAttempted = true;
                connection.commit();
            } catch (Exception failure) {
                if (!commitAttempted) {
                    rollback(connection, failure);
                }
                throw failure;
            }
            checkpoint(connection);
        } catch (Exception failure) {
            if (commitAttempted && committedReadback(temporary, plan, manifest)) {
                checkpointAfterUnknownCommit(temporary);
                return;
            }
            throw failure;
        }
        verifyPublishedTarget(temporary, plan, manifest);
    }

    private void initializeSchema(SqliteConnectionFactory connections) throws Exception {
        SqliteSchemaV1Manager schema = new SqliteSchemaV1Manager(connections, clock);
        PersistenceTransactionResult<?> result = schema.initialize();
        if (result instanceof PersistenceTransactionResult.Committed<?>) {
            return;
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?>
                && schema.verify() instanceof PersistenceReadResult.Found<?>) {
            return;
        }
        throw new IllegalStateException("replacement_schema_initialization_failed");
    }

    private void verifyPublishedTarget(
            Path target,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(target);
        if (!(new SqliteSchemaV1Manager(connections).verify()
                instanceof PersistenceReadResult.Found<?>)) {
            throw new IllegalStateException("published_schema_verification_failed");
        }
        try (Connection connection = connections.openReadConnection()) {
            verifier.verify(connection, plan, manifest);
        }
    }

    private boolean committedReadback(
            Path target,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) {
        try {
            verifyPublishedTarget(target, plan, manifest);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private void checkpointAfterUnknownCommit(Path target) throws Exception {
        try (Connection connection =
                     new SqliteConnectionFactory(target).openWriterConnection()) {
            checkpoint(connection);
        }
    }

    private void checkpoint(Connection connection) throws Exception {
        if (!connection.getAutoCommit()) {
            connection.setAutoCommit(true);
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!row.next() || row.getInt(1) != 0) {
                throw new IllegalStateException("import_target_checkpoint_busy");
            }
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private PublicImportResult.Refused refused(
            LegacySourceKind kind,
            int version,
            String code
    ) {
        return new PublicImportResult.Refused(kind, version, code);
    }

    private PublicImportResult.Failed failed(String code, Throwable failure) {
        return new PublicImportResult.Failed(code, failure);
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null) {
            return;
        }
        try {
            Files.deleteIfExists(workspace);
        } catch (Exception ignored) {
            // Snapshot close owns all children; a leftover empty temp directory is non-canonical.
        }
    }

    private void deleteTargetFiles(Path target) {
        for (Path owned : new Path[]{
                target,
                target.resolveSibling(target.getFileName() + "-wal"),
                target.resolveSibling(target.getFileName() + "-shm")
        }) {
            try {
                Files.deleteIfExists(owned);
            } catch (Exception ignored) {
                // Owned temporary targets are ignored by bootstrap and can be diagnosed manually.
            }
        }
    }
}
