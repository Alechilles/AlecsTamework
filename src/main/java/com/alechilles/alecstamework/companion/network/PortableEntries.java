package com.alechilles.alecstamework.companion.network;

import com.alechilles.alecstamework.api.PortableCompanionEntry;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.store.CompanionRecordBson;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * Converts between index records and store entries. Records and snapshots travel as extended JSON,
 * which keeps every BSON type, so a copy decodes exactly as the owner and snapshot files would.
 */
final class PortableEntries {
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

    private PortableEntries() {
    }

    /** The entry for {@code record}; {@code holder} names this server for a world-bound record. */
    @Nonnull
    static PortableCompanionEntry entry(@Nonnull CompanionRecord record, @Nullable SnapshotEnvelope snapshot,
                                        @Nonnull String holder) {
        boolean portable = record.scope() == RecordScope.PORTABLE;
        return new PortableCompanionEntry(record.profileId(), record.ownerUuid(), record.generation(),
                record.updatedAtMs(), portable, record.location().kind().name(), record.roleId(),
                portable ? null : holder, CompanionRecordBson.encode(record).toJson(JSON),
                portable && snapshot != null ? snapshot.toBson().toJson(JSON) : null);
    }

    /** True when the entry replaces the local record: a higher generation, or the same and a later version. */
    static boolean supersedes(@Nonnull PortableCompanionEntry entry, @Nonnull CompanionRecord record) {
        return PortableCompanionEntry.supersedes(entry.generation(), entry.version(), record.generation(),
                record.updatedAtMs());
    }

    /** True when the local record replaces the entry. */
    static boolean supersedes(@Nonnull CompanionRecord record, @Nonnull PortableCompanionEntry entry) {
        return PortableCompanionEntry.supersedes(record.generation(), record.updatedAtMs(), entry.generation(),
                entry.version());
    }

    /** @throws IllegalArgumentException when the record does not decode */
    @Nonnull
    static CompanionRecord record(@Nonnull PortableCompanionEntry entry) {
        return CompanionRecordBson.decode(BsonDocument.parse(entry.recordJson()));
    }

    /** The entry's snapshot, or null when it has none. @throws RuntimeException when it does not decode */
    @Nullable
    static SnapshotEnvelope snapshot(@Nonnull PortableCompanionEntry entry) {
        return entry.snapshotJson() == null ? null : SnapshotEnvelope.fromBson(BsonDocument.parse(entry.snapshotJson()));
    }

    /** Whether the entry's location counts toward its owner's limits (everything but a released tombstone). */
    static boolean countsAsOwned(@Nonnull PortableCompanionEntry entry) {
        try {
            return LocationKind.valueOf(entry.locationKind()).countsAsOwned();
        } catch (IllegalArgumentException unknownKind) {
            return false;
        }
    }
}
