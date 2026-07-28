package com.alechilles.alecstamework.companion.snapshot;

/**
 * Single-use recovery evidence retained for an ambiguous public released-coop row.
 *
 * <p>This kind is never normal lifecycle authority. It can be consumed only
 * after an explicit recall of the imported current alias exhausts relocation,
 * at which point the exact payload is converted to ordinary Lost v2 evidence.</p>
 */
public final class PublicImportRecoveryProjection {
    public static final SnapshotKind KIND =
            new SnapshotKind("public_import_recovery");
    public static final int VERSION = 1;

    private PublicImportRecoveryProjection() {
    }
}
