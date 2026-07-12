package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLedgerRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLedgerRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persistence adapter for the pre-v5 command-coop ledger.
 *
 * <p>The SQLite {@code coop_slots} table and version-2 DAT format remain readable for migration,
 * rollback, and unmanaged compatibility only. This adapter is never a managed-coop authority.</p>
 */
final class LegacyCoopLedgerPersistence {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String ARRAY_SEPARATOR = ";";
    private static final String LEDGER_VERSION = "2";
    private static final CoopResidentStateSnapshotCodec SNAPSHOT_CODEC =
            new CoopResidentStateSnapshotCodec();

    @Nullable private final Path datPath;
    @Nullable private final CoopLedgerRepository repository;
    private final Object datLock = new Object();

    LegacyCoopLedgerPersistence(@Nullable Path datPath, @Nullable CoopLedgerRepository repository) {
        this.datPath = datPath == null ? null : datPath.toAbsolutePath().normalize();
        this.repository = repository;
    }

    @Nonnull
    List<LegacyCoopLedgerEntry> load() {
        if (repository != null) {
            ArrayList<LegacyCoopLedgerEntry> entries = new ArrayList<>();
            for (CoopLedgerRow row : repository.loadAll()) {
                LegacyCoopLedgerEntry entry = fromRow(row);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries;
        }
        return loadDatEntries(datPath);
    }

    @Nonnull
    static List<CoopLedgerRow> loadDatRows(@Nullable Path datPath) {
        ArrayList<CoopLedgerRow> rows = new ArrayList<>();
        for (LegacyCoopLedgerEntry entry : loadDatEntries(datPath)) {
            rows.add(toRow(entry));
        }
        return List.copyOf(rows);
    }

    void upsert(@Nonnull LegacyCoopLedgerEntry entry,
                @Nonnull Collection<LegacyCoopLedgerEntry> allEntries) {
        if (repository != null) {
            repository.upsertSlotAsync(toRow(entry));
        } else {
            persistDat(allEntries);
        }
    }

    void release(@Nonnull LegacyCoopLedgerEntry entry,
                 @Nullable UUID previousNpcUuid,
                 @Nullable UUID currentNpcUuid,
                 @Nonnull Collection<LegacyCoopLedgerEntry> allEntries) {
        if (repository != null) {
            repository.releaseAndRemapAsync(toRow(entry), previousNpcUuid, currentNpcUuid);
        } else {
            persistDat(allEntries);
        }
    }

    void clearSlot(@Nonnull CommandLinkedNpcCoopService.CoopSlotContext context,
                   @Nonnull Collection<LegacyCoopLedgerEntry> allEntries) {
        if (repository != null) {
            repository.clearSlotAsync(
                    context.worldName(), context.coopId(), context.x(), context.y(), context.z(),
                    context.residentSlot());
        } else {
            persistDat(allEntries);
        }
    }

    void clearNpcReferences(@Nonnull UUID npcUuid,
                            @Nonnull Collection<LegacyCoopLedgerEntry> allEntries) {
        if (repository != null) {
            repository.clearNpcReferencesAsync(npcUuid);
        } else {
            persistDat(allEntries);
        }
    }

    void clearAll(@Nonnull Collection<LegacyCoopLedgerEntry> allEntries) {
        if (repository != null) {
            repository.clearAllAsync();
        } else {
            persistDat(allEntries);
        }
    }

    private void persistDat(@Nonnull Collection<LegacyCoopLedgerEntry> entries) {
        if (datPath == null) {
            return;
        }
        synchronized (datLock) {
            try {
                if (entries.isEmpty()) {
                    Files.deleteIfExists(datPath);
                    return;
                }
                Path parent = datPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder output = new StringBuilder();
                for (LegacyCoopLedgerEntry entry : entries) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(encode(entry));
                }
                Files.writeString(datPath, output.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Rollback compatibility is best effort; live managed state never depends on it.
            }
        }
    }

    @Nonnull
    private static List<LegacyCoopLedgerEntry> loadDatEntries(@Nullable Path path) {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        ArrayList<LegacyCoopLedgerEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                LegacyCoopLedgerEntry entry = decode(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(entries);
    }

    @Nonnull
    private static String encode(@Nonnull LegacyCoopLedgerEntry entry) {
        return LEDGER_VERSION
                + FIELD_SEPARATOR + encodeString(entry.worldName)
                + FIELD_SEPARATOR + entry.x
                + FIELD_SEPARATOR + entry.y
                + FIELD_SEPARATOR + entry.z
                + FIELD_SEPARATOR + entry.residentSlot
                + FIELD_SEPARATOR + encodeString(entry.coopId)
                + FIELD_SEPARATOR + encodeUuid(entry.housedNpcUuid)
                + FIELD_SEPARATOR + encodeUuid(entry.lastReleasedNpcUuid)
                + FIELD_SEPARATOR + encodeUuid(entry.ownerId)
                + FIELD_SEPARATOR + encodeArray(entry.toolIds)
                + FIELD_SEPARATOR + encodeString(entry.roleId)
                + FIELD_SEPARATOR + encodeString(entry.displayName)
                + FIELD_SEPARATOR + entry.housedAtMs
                + FIELD_SEPARATOR + entry.releasedAtMs;
    }

    @Nullable
    private static LegacyCoopLedgerEntry decode(@Nullable String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] fields = line.split(FIELD_SEPARATOR, -1);
        if (fields.length < 15 || !LEDGER_VERSION.equals(fields[0])) {
            return null;
        }
        String worldName = decodeString(fields[1]);
        int x = parseInt(fields[2], LegacyCoopLedgerSupport.UNKNOWN_COORDINATE);
        int y = parseInt(fields[3], LegacyCoopLedgerSupport.UNKNOWN_COORDINATE);
        int z = parseInt(fields[4], LegacyCoopLedgerSupport.UNKNOWN_COORDINATE);
        int slot = parseInt(fields[5], -1);
        String coopId = LegacyCoopLedgerSupport.normalize(decodeString(fields[6]));
        if (coopId == null) {
            return null;
        }
        CommandLinkedNpcCoopService.CoopSlotContext context =
                CommandLinkedNpcCoopService.CoopSlotContext.of(worldName, coopId, x, y, z, slot);
        return new LegacyCoopLedgerEntry(
                LegacyCoopLedgerSupport.slotKey(context),
                LegacyCoopLedgerSupport.normalize(worldName),
                coopId,
                x, y, z, slot,
                decodeUuid(fields[7]),
                decodeUuid(fields[8]),
                decodeUuid(fields[9]),
                decodeArray(fields[10]),
                LegacyCoopLedgerSupport.normalize(decodeString(fields[11])),
                decodeString(fields[12]),
                parseLong(fields[13], System.currentTimeMillis()),
                parseLong(fields[14], 0L),
                null
        );
    }

    @Nonnull
    private static CoopLedgerRow toRow(@Nonnull LegacyCoopLedgerEntry entry) {
        return new CoopLedgerRow(
                entry.slotKey, entry.worldName, entry.coopId, entry.x, entry.y, entry.z,
                entry.residentSlot, entry.housedNpcUuid, entry.lastReleasedNpcUuid, entry.ownerId,
                entry.toolIds.clone(), entry.roleId, entry.displayName, entry.housedAtMs,
                entry.releasedAtMs, encodeSnapshot(entry.stateSnapshot));
    }

    @Nullable
    private static LegacyCoopLedgerEntry fromRow(@Nullable CoopLedgerRow row) {
        if (row == null || row.slotKey() == null || row.slotKey().isBlank()) {
            return null;
        }
        String coopId = LegacyCoopLedgerSupport.normalize(row.coopId());
        if (coopId == null) {
            return null;
        }
        return new LegacyCoopLedgerEntry(
                row.slotKey(), LegacyCoopLedgerSupport.normalize(row.worldName()), coopId,
                row.x(), row.y(), row.z(), row.residentSlot(), row.housedNpcUuid(),
                row.lastReleasedNpcUuid(), row.ownerId(),
                LegacyCoopLedgerSupport.sanitizeToolIds(row.toolIds()),
                LegacyCoopLedgerSupport.normalize(row.roleId()), row.displayName(), row.housedAtMs(),
                row.releasedAtMs(), decodeSnapshot(row.stateSnapshotJson()));
    }

    @Nullable
    private static String encodeSnapshot(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        return snapshot == null ? null : SNAPSHOT_CODEC.encode(snapshot);
    }

    @Nullable
    private static CoopResidentStateSnapshotService.CoopResidentStateSnapshot decodeSnapshot(
            @Nullable String payload) {
        CoopResidentStateSnapshotCodec.DecodeResult result = SNAPSHOT_CODEC.decode(payload);
        return result.status() == CoopResidentStateSnapshotCodec.Status.FAILED
                ? null
                : result.snapshotOrNull();
    }

    @Nonnull
    private static String encodeUuid(@Nullable UUID value) {
        return value == null ? "" : value.toString();
    }

    @Nullable
    private static UUID decodeUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String encodeString(@Nullable String value) {
        return value == null || value.isBlank()
                ? ""
                : Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static String decodeString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String encodeArray(@Nullable String[] values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : LegacyCoopLedgerSupport.sanitizeToolIds(values)) {
            if (encoded.length() > 0) {
                encoded.append(ARRAY_SEPARATOR);
            }
            encoded.append(encodeString(value));
        }
        return encoded.toString();
    }

    @Nonnull
    private static String[] decodeArray(@Nullable String payload) {
        if (payload == null || payload.isBlank()) {
            return new String[0];
        }
        ArrayList<String> values = new ArrayList<>();
        for (String field : payload.split(ARRAY_SEPARATOR)) {
            String value = decodeString(field);
            if (value != null) {
                values.add(value);
            }
        }
        return LegacyCoopLedgerSupport.sanitizeToolIds(values.toArray(new String[0]));
    }

    private static int parseInt(@Nullable String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(@Nullable String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
