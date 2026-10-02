package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * What {@link LegacyMapper} made of a 3.x/4.x world: the records and snapshots to write as a
 * normal companion store, the alias map for {@code legacy-aliases.json}, and the data of the
 * import report. Nothing here has been written anywhere yet.
 *
 * @param records   one record per imported companion, state-file profiles first, then bonded
 *                  ones, each in profile id order; all at generation 0 with no domain claims
 * @param snapshots the snapshot of each record that has one, by profile id. Format 1 for a live
 *                  companion with an entity checkpoint, format 0 otherwise. A RELEASED record has
 *                  none, and neither has an ITEM record listed in {@code stateInItem}
 */
public record ImportResult(
        @Nonnull List<CompanionRecord> records,
        @Nonnull Map<UUID, SnapshotEnvelope> snapshots,
        @Nonnull LegacyAliases aliases,
        @Nonnull Report report) {

    /** {@code companion_profile.profile_id} or an owner id is not a UUID. */
    public static final String SKIP_INVALID_ID = "INVALID_ID";
    /** A profile with no {@code companion_lifecycle} row. */
    public static final String SKIP_NO_LIFECYCLE = "NO_LIFECYCLE";
    /** A profile with no role; the new record needs one. */
    public static final String SKIP_NO_ROLE = "NO_ROLE";
    /** A state-file profile that the bonded file also holds; the bonded row is imported. */
    public static final String SKIP_BONDED_PROFILE = "ALSO_BONDED";
    /** Extension data whose namespace contains "/", which the new store refuses (R12). */
    public static final String SKIP_NAMESPACE_SLASH = "NAMESPACE_HAS_SLASH";
    /** Extension data in a namespace Tamework reserves for itself, such as the 4.x coop production watermark. */
    public static final String SKIP_RESERVED_NAMESPACE = "RESERVED_NAMESPACE";
    /**
     * A profile whose mapping threw. The key is the profile id followed by the exception class and
     * message in brackets. The other profiles are still imported.
     */
    public static final String SKIP_MAPPING_FAILED = "MAPPING_FAILED";
    /** A snapshot, checkpoint or bonded payload that holds no readable companion state. */
    public static final String SKIP_UNREADABLE = "UNREADABLE";

    public ImportResult {
        records = List.copyOf(records);
        snapshots = Collections.unmodifiableMap(new LinkedHashMap<>(snapshots));
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(report, "report");
    }

    /** A row that was not imported. {@code table} is the old table name and {@code key} its primary key. */
    public record Skipped(@Nonnull String table, @Nonnull String key, @Nonnull String reason) {
    }

    /**
     * Counts and exceptions for the import report. Every list holds profile ids in import order.
     *
     * @param recordsByLocation       imported records per location kind; kinds with none are absent
     * @param bondedRecords           how many of the records came from the bonded file
     * @param unfinishedOperations    old operations that never finished; counted, not replayed (R11)
     * @param quarantinedProfiles     profiles the old runtime had quarantined; imported from
     *                                their lifecycle row like any other (R11)
     * @param skippedRows             rows that were not imported, with the reason
     * @param liveWithoutCheckpoint   LIVE records with no entity checkpoint: position 0,0,0 until
     *                                the body is seen, and a recover respawns from the role unless
     *                                an older state snapshot existed (R5)
     * @param importedLost            records imported LOST because the old row named no usable
     *                                body or coop slot, or had state UNRESOLVED or unknown. The
     *                                last body of such a record is a REJOIN alias
     * @param npcUuidCollisions       both sides of every current NPC UUID claimed twice: the
     *                                newer record kept it, the other is LOST (R9)
     * @param withoutState            records other than LIVE whose old rows held no readable
     *                                state; they come back from their role at level 1
     * @param checkpointsOfDyingBodies LIVE records whose checkpoint was taken as the body died;
     *                                the death state was removed from their snapshot so that a
     *                                recover can use it if the body is gone
     * @param liveWorldGuessed        LIVE records whose old rows named no world (the old runtime
     *                                cleared it for an unloaded body); they carry the world most
     *                                other rows name until the body is seen
     * @param stateInItem             ITEM records with no snapshot: the old rows hold no state
     *                                for them, because a 2.x capture kept it in the capture item
     * @param liveUsedHistory         LIVE records with no checkpoint whose snapshot is an old,
     *                                non-current row (never a death): the best saved state of a
     *                                body that is out in the world
     */
    public record Report(
            @Nonnull Map<LocationKind, Integer> recordsByLocation,
            int bondedRecords,
            int unfinishedOperations,
            @Nonnull List<UUID> quarantinedProfiles,
            @Nonnull List<Skipped> skippedRows,
            @Nonnull List<UUID> liveWithoutCheckpoint,
            @Nonnull List<UUID> importedLost,
            @Nonnull List<UUID> npcUuidCollisions,
            @Nonnull List<UUID> withoutState,
            @Nonnull List<UUID> checkpointsOfDyingBodies,
            @Nonnull List<UUID> liveWorldGuessed,
            @Nonnull List<UUID> stateInItem,
            @Nonnull List<UUID> liveUsedHistory) {
        public Report {
            liveUsedHistory = List.copyOf(liveUsedHistory);
            liveWorldGuessed = List.copyOf(liveWorldGuessed);
            stateInItem = List.copyOf(stateInItem);
            recordsByLocation = Collections.unmodifiableMap(new LinkedHashMap<>(recordsByLocation));
            quarantinedProfiles = List.copyOf(quarantinedProfiles);
            skippedRows = List.copyOf(skippedRows);
            liveWithoutCheckpoint = List.copyOf(liveWithoutCheckpoint);
            importedLost = List.copyOf(importedLost);
            npcUuidCollisions = List.copyOf(npcUuidCollisions);
            withoutState = List.copyOf(withoutState);
            checkpointsOfDyingBodies = List.copyOf(checkpointsOfDyingBodies);
        }
    }
}
