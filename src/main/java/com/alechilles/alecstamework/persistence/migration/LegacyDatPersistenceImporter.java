package com.alechilles.alecstamework.persistence.migration;

import java.nio.file.Path;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Imports the released five-file DAT bundle into one fresh canonical replacement database.
 *
 * <p>The source directory is immutable evidence. This importer never moves, rewrites, or deletes
 * a DAT file; exact replay is recognized through the canonical import manifest and byte-framed
 * bundle fingerprint.</p>
 */
public final class LegacyDatPersistenceImporter {
    private static final int LEGACY_DAT_SCHEMA_VERSION = 2;

    private final LongSupplier clock;
    private final LegacyDatLineDecoder decoder = new LegacyDatLineDecoder();
    private final LegacyDatCanonicalSourceBuilder sourceBuilder =
            new LegacyDatCanonicalSourceBuilder();
    private final PublicImportPlanner planner = new PublicImportPlanner();
    private final PublicImportManifestFactory manifests = new PublicImportManifestFactory();
    private final PublicPersistenceImporter publisher;

    public LegacyDatPersistenceImporter() {
        this(System::currentTimeMillis);
    }

    LegacyDatPersistenceImporter(@Nonnull LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Legacy DAT import clock is required");
        }
        this.clock = clock;
        this.publisher = new PublicPersistenceImporter(clock);
    }

    /** Imports all released DAT files found directly in {@code sourceDirectory}. */
    @Nonnull
    public PublicImportResult importDirectory(
            @Nonnull Path sourceDirectory,
            @Nonnull Path targetPath
    ) {
        if (sourceDirectory == null || targetPath == null) {
            throw new IllegalArgumentException("Legacy DAT source and target paths are required");
        }
        try {
            LegacyDatBundleSnapshot snapshot =
                    LegacyDatBundleSnapshot.capture(sourceDirectory);
            if (!snapshot.hasSourceFiles()) {
                return refused("NO_LEGACY_DAT_SOURCE");
            }
            if (snapshot.ownsPath(targetPath)) {
                return failed(
                        "SOURCE_TARGET_PATH_COLLISION",
                        new IllegalArgumentException("Target cannot replace a legacy DAT source")
                );
            }
            long importedAtMs = clock.getAsLong();
            LegacyPublicData source = sourceBuilder.build(
                    decoder.decode(snapshot), snapshot.fingerprint(), importedAtMs);
            PublicImportPlan plan = planner.plan(
                    source, snapshot.fingerprint(), importedAtMs);
            PublicImportManifest manifest = manifests.create(
                    plan,
                    snapshot.fingerprint(),
                    LEGACY_DAT_SCHEMA_VERSION,
                    snapshot.sourceName(),
                    importedAtMs
            );
            return publisher.publishPrepared(targetPath, plan, manifest);
        } catch (PublicImportException refusal) {
            return refused(refusal.code());
        } catch (Exception failure) {
            return failed("LEGACY_DAT_IMPORT_FAILED", failure);
        }
    }

    private PublicImportResult.Refused refused(String code) {
        return new PublicImportResult.Refused(
                LegacySourceKind.LEGACY_DAT, LEGACY_DAT_SCHEMA_VERSION, code);
    }

    private PublicImportResult.Failed failed(String code, Throwable cause) {
        return new PublicImportResult.Failed(code, cause);
    }
}
