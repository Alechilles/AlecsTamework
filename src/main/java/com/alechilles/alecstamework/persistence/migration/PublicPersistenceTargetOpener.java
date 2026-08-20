package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceSchemaStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Selects an existing target, imports a released public source, or creates a
 * fresh replacement target.
 *
 * <p>The caller must hold the replacement engine lease before invoking this
 * boundary. Unsupported development sources are refused before target creation.</p>
 */
public final class PublicPersistenceTargetOpener {
    private final LongSupplier clock;
    private final PublicPersistenceImporter importer;
    private final LegacyDatPersistenceImporter datImporter;
    private final FreshReplacementTargetCreator fresh;
    private final ExistingPublicImportQuarantineRepair quarantineRepair;
    private final PublicPersistenceSourceDiscovery sources =
            new PublicPersistenceSourceDiscovery();

    public PublicPersistenceTargetOpener() {
        this(System::currentTimeMillis);
    }

    public PublicPersistenceTargetOpener(@Nonnull LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Target open clock is required");
        }
        this.clock = clock;
        importer = new PublicPersistenceImporter(clock);
        datImporter = new LegacyDatPersistenceImporter(clock);
        fresh = new FreshReplacementTargetCreator(clock);
        quarantineRepair = new ExistingPublicImportQuarantineRepair(clock);
    }

    /**
     * Opens from a single-directory layout retained for focused callers.
     */
    @Nonnull
    public PublicPersistenceTarget open(@Nonnull Path dataDirectory) {
        return open(dataDirectory, List.of(dataDirectory));
    }

    /** Opens using the canonical target and immutable path-service candidates. */
    @Nonnull
    public PublicPersistenceTarget open(
            @Nonnull TameworkDataPathLayout layout
    ) {
        if (layout == null) {
            throw new IllegalArgumentException(
                    "Tamework persistence path layout is required"
            );
        }
        return open(
                layout.targetDirectory(),
                layout.persistenceSourceDirectories()
        );
    }

    /**
     * Opens or constructs exactly one canonical replacement database path after
     * selecting at most one immutable source across the supplied directories.
     */
    @Nonnull
    public PublicPersistenceTarget open(
            @Nonnull Path targetDirectory,
            @Nonnull List<Path> sourceDirectories
    ) {
        if (targetDirectory == null || sourceDirectories == null) {
            throw new IllegalArgumentException(
                    "Target and source directories are required"
            );
        }
        Path target = PersistenceFiles.replacementDatabase(targetDirectory);
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target)) {
                throw new IllegalStateException(
                        "replacement_target_not_regular_file"
                );
            }
            ArrayList<Path> candidates = new ArrayList<>();
            candidates.add(targetDirectory);
            candidates.addAll(sourceDirectories);
            quarantineRepair.repair(target, candidates);
            initializeExistingTarget(target);
            return new PublicPersistenceTarget(
                    target,
                    PublicPersistenceTarget.Origin.EXISTING
            );
        }
        ArrayList<Path> candidates = new ArrayList<>();
        candidates.add(targetDirectory);
        candidates.addAll(sourceDirectories);
        PublicPersistenceSourceDiscovery.Result discovered =
                sources.discover(candidates);
        if (discovered instanceof
                PublicPersistenceSourceDiscovery.Refused refused) {
            throw new IllegalStateException(
                    "public_persistence_source_refused:" + refused.code()
            );
        }
        if (discovered instanceof
                PublicPersistenceSourceDiscovery.Selected source) {
            PublicImportResult result =
                    source.format()
                            == PublicPersistenceSourceDiscovery.Format.SQLITE
                            ? importer.importSource(source.source(), target)
                            : datImporter.importDirectory(
                                    source.directory(), target);
            return imported(result, target);
        }
        try {
            fresh.create(target);
            return new PublicPersistenceTarget(
                    target,
                    PublicPersistenceTarget.Origin.FRESH
            );
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "fresh_replacement_target_failed",
                    failure
            );
        }
    }

    private PublicPersistenceTarget imported(
            PublicImportResult result,
            Path target
    ) {
        if (result instanceof PublicImportResult.Imported
                || result instanceof PublicImportResult.AlreadyImported) {
            return new PublicPersistenceTarget(
                    target,
                    PublicPersistenceTarget.Origin.IMPORTED_PUBLIC
            );
        }
        if (result instanceof PublicImportResult.Refused refused) {
            throw new IllegalStateException(
                    "public_persistence_import_refused:" + refused.code()
            );
        }
        PublicImportResult.Failed failed =
                (PublicImportResult.Failed) result;
        throw new IllegalStateException(
                "public_persistence_import_failed:" + failed.code(),
                failed.cause()
        );
    }

    private void initializeExistingTarget(Path target) {
        SqliteSchemaV2Manager schemas = new SqliteSchemaV2Manager(
                new SqliteConnectionFactory(target), clock
        );
        PersistenceTransactionResult<PersistenceSchemaStatus> initialized =
                schemas.initialize();
        if (!(initialized instanceof
                PersistenceTransactionResult.Committed<PersistenceSchemaStatus>
                committed)
                || committed.value() == null
                || committed.value().version() != SqliteSchemaV2Manager.VERSION
                || !committed.value().integrityVerified()) {
            throw new IllegalStateException(
                    "existing_replacement_schema_initialization_failed"
            );
        }
        PersistenceReadResult<PersistenceSchemaStatus> verified =
                schemas.verify();
        if (!(verified instanceof PersistenceReadResult.Found<
                PersistenceSchemaStatus> found)
                || found.value().version() != SqliteSchemaV2Manager.VERSION
                || !found.value().integrityVerified()) {
            throw new IllegalStateException(
                    "existing_replacement_schema_verification_failed"
            );
        }
    }
}
