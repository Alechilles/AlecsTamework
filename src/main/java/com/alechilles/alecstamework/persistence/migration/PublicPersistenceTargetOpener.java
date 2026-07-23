package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final PublicPersistenceImporter importer;
    private final FreshReplacementTargetCreator fresh;

    public PublicPersistenceTargetOpener() {
        this(System::currentTimeMillis);
    }

    public PublicPersistenceTargetOpener(@Nonnull LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Target open clock is required");
        }
        importer = new PublicPersistenceImporter(clock);
        fresh = new FreshReplacementTargetCreator(clock);
    }

    /** Opens or constructs exactly one canonical replacement database path. */
    @Nonnull
    public PublicPersistenceTarget open(@Nonnull Path dataDirectory) {
        Path target = PersistenceFiles.replacementDatabase(dataDirectory);
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target)) {
                throw new IllegalStateException(
                        "replacement_target_not_regular_file"
                );
            }
            return new PublicPersistenceTarget(
                    target,
                    PublicPersistenceTarget.Origin.EXISTING
            );
        }
        Path source = PersistenceFiles.legacyDatabase(dataDirectory);
        if (Files.exists(source)) {
            return imported(source, target);
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

    private PublicPersistenceTarget imported(Path source, Path target) {
        PublicImportResult result = importer.importSource(source, target);
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
}
