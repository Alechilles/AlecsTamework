package com.alechilles.alecstamework.persistence.migration;

import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Exact terminal result of one offline public persistence import attempt. */
public sealed interface PublicImportResult
        permits PublicImportResult.Imported,
        PublicImportResult.AlreadyImported,
        PublicImportResult.Refused,
        PublicImportResult.Failed {

    /** A newly built and verified replacement target was atomically published. */
    record Imported(
            @Nonnull Path targetPath,
            @Nonnull String importId,
            @Nonnull Optional<Path> reportPath
    ) implements PublicImportResult {
        public Imported {
            if (targetPath == null || importId == null || reportPath == null) {
                throw new IllegalArgumentException("Imported result fields are required");
            }
        }
    }

    /** The existing target exactly matches this source and importer version. */
    record AlreadyImported(
            @Nonnull Path targetPath,
            @Nonnull String importId
    ) implements PublicImportResult {
        public AlreadyImported {
            if (targetPath == null || importId == null) {
                throw new IllegalArgumentException("Existing import result fields are required");
            }
        }
    }

    /** Source or target admission was rejected without modifying either database. */
    record Refused(
            @Nonnull LegacySourceKind sourceKind,
            int sourceSchemaVersion,
            @Nonnull String code
    ) implements PublicImportResult {
        public Refused {
            if (sourceKind == null || sourceSchemaVersion < 0 || code == null || code.isBlank()) {
                throw new IllegalArgumentException("Import refusal fields are required");
            }
        }
    }

    /** Infrastructure failed before a verified target could be reported as ready. */
    record Failed(
            @Nonnull String code,
            @Nonnull Throwable cause
    ) implements PublicImportResult {
        public Failed {
            if (code == null || code.isBlank() || cause == null) {
                throw new IllegalArgumentException("Import failure details are required");
            }
        }
    }
}
