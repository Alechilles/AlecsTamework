package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Async typed reads for canonical coop slots, residency, and exact conflict diagnostics. */
public final class SqliteCompanionCoopReader {
    private static final PersistenceReadKind SLOT =
            new PersistenceReadKind("coop_slot_by_key");
    private static final PersistenceReadKind RESIDENT =
            new PersistenceReadKind("coop_residency_by_profile");
    private static final PersistenceReadKind ALL =
            new PersistenceReadKind("coop_all_occupancies");
    private static final PersistenceReadKind CAPTURE_DIAGNOSTIC =
            new PersistenceReadKind("coop_capture_diagnostic");
    private static final PersistenceReadKind RELEASE_DIAGNOSTIC =
            new PersistenceReadKind("coop_release_diagnostic");

    private final SqliteReadExecutor reads;

    public SqliteCompanionCoopReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException("Coop read executor is required");
        }
        this.reads = reads;
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopSlot>> findSlot(
            @Nonnull CoopSlotKey slotKey
    ) {
        require(slotKey, "Coop slot key");
        return reads.execute(new SqliteReadCommand<>(
                SLOT,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> new SqliteCompanionCoopStore(connection)
                        .findSlot(slotKey)
                        .<PersistenceReadResult<CoopSlot>>map(slot ->
                                PersistenceReadResult.found(
                                        slot, slot.residencyRevision()
                                ))
                        .orElseGet(PersistenceReadResult::absent)
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopResidency>>
    findResidencyByProfile(@Nonnull ProfileId profileId) {
        require(profileId, "Profile ID");
        return reads.execute(new SqliteReadCommand<>(
                RESIDENT,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqliteCompanionCoopStore store =
                            new SqliteCompanionCoopStore(connection);
                    CoopResidency residency = store
                            .findResidencyByProfile(profileId)
                            .orElse(null);
                    if (residency == null) {
                        return PersistenceReadResult.absent();
                    }
                    CoopSlot slot = store.findSlot(residency.slotKey())
                            .orElseThrow(() -> new IllegalStateException(
                                    "coop_residency_slot_missing"
                            ));
                    return PersistenceReadResult.found(
                            residency, slot.residencyRevision()
                    );
                }
        ));
    }

    /** Lists a consistent snapshot for projection rebuilds on the diagnostic lane. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<List<CoopOccupancy>>>
    findAllOccupancies() {
        return reads.execute(new SqliteReadCommand<>(
                ALL,
                PersistenceReadPriority.DIAGNOSTIC,
                connection -> {
                    List<CoopOccupancy> occupancies =
                            new SqliteCompanionCoopStore(connection)
                                    .findAllOccupancies();
                    long revision = occupancies.stream()
                            .mapToLong(occupancy ->
                                    occupancy.slot().residencyRevision())
                            .max()
                            .orElse(0);
                    return PersistenceReadResult.found(
                            occupancies, revision
                    );
                }
        ));
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopConflictDiagnostic>>
    diagnoseCapture(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    ) {
        return diagnose(
                CAPTURE_DIAGNOSTIC, slotKey, profileId, true
        );
    }

    @Nonnull
    public CompletionStage<PersistenceReadResult<CoopConflictDiagnostic>>
    diagnoseRelease(
            @Nonnull CoopSlotKey slotKey,
            @Nonnull ProfileId profileId
    ) {
        return diagnose(
                RELEASE_DIAGNOSTIC, slotKey, profileId, false
        );
    }

    private CompletionStage<PersistenceReadResult<CoopConflictDiagnostic>>
    diagnose(
            PersistenceReadKind kind,
            CoopSlotKey slotKey,
            ProfileId profileId,
            boolean capture
    ) {
        require(slotKey, "Coop slot key");
        require(profileId, "Profile ID");
        return reads.execute(new SqliteReadCommand<>(
                kind,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> {
                    SqliteCompanionCoopStore store =
                            new SqliteCompanionCoopStore(connection);
                    CoopConflictDiagnostic diagnostic = capture
                            ? store.diagnoseCapture(slotKey, profileId)
                            : store.diagnoseRelease(slotKey, profileId);
                    long revision = diagnostic.slot() == null
                            ? 0
                            : diagnostic.slot().residencyRevision();
                    return PersistenceReadResult.found(
                            diagnostic, revision
                    );
                }
        ));
    }

    private static void require(Object value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
