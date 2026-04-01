package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLedgerRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLedgerRow;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authoritative coop resident ledger and facade for command-item coop status queries.
 */
public final class CommandLinkedNpcCoopService {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String ARRAY_SEPARATOR = ";";
    private static final String LEDGER_VERSION = "2";
    private static final String SNAPSHOT_VERSION = "1";
    private static final Gson SNAPSHOT_JSON = new Gson();
    private static final int UNKNOWN_COORDINATE = Integer.MIN_VALUE;

    private final ConcurrentHashMap<String, CoopLedgerEntry> ledgerBySlot = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> slotKeyByNpc = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CoopLinkedNpcSnapshot> coopedByNpc = new ConcurrentHashMap<>();
    @Nullable
    private final CoopLedgerRepository repository;
    @Nullable
    private final PersistenceHealthService healthService;
    @Nullable
    private final NpcProfileRepository profileRepository;
    private final Path persistencePath;
    private final Object persistenceLock = new Object();

    public CommandLinkedNpcCoopService() {
        this(null, null, null, null);
    }

    public CommandLinkedNpcCoopService(@Nullable Path persistencePath) {
        this(persistencePath, null, null, null);
    }

    public CommandLinkedNpcCoopService(@Nonnull CoopLedgerRepository repository,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nullable NpcProfileRepository profileRepository) {
        this(null, repository, healthService, profileRepository);
    }

    @Nonnull
    public static List<CoopLedgerRow> loadLegacyLedgerRows(@Nullable Path legacyPath) {
        if (legacyPath == null || !Files.exists(legacyPath)) {
            return List.of();
        }
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService(legacyPath, null, null, null);
        ArrayList<CoopLedgerRow> rows = new ArrayList<>();
        for (CoopLedgerEntry entry : service.ledgerBySlot.values()) {
            if (entry == null || entry.slotKey == null) {
                continue;
            }
            rows.add(service.toLedgerRow(entry));
        }
        return rows;
    }

    private CommandLinkedNpcCoopService(@Nullable Path persistencePath,
                                        @Nullable CoopLedgerRepository repository,
                                        @Nullable PersistenceHealthService healthService,
                                        @Nullable NpcProfileRepository profileRepository) {
        this.persistencePath = persistencePath != null
                ? persistencePath.toAbsolutePath().normalize()
                : null;
        this.repository = repository;
        this.healthService = healthService;
        this.profileRepository = profileRepository;
        loadPersistedLedger();
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshot(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return coopedByNpc.get(npcUuid);
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForTool(@Nullable UUID npcUuid,
                                                        @Nullable String toolId,
                                                        @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!snapshot.containsToolId(toolId)) {
            return null;
        }
        if (!isOwnerCompatible(snapshot, ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForOwner(@Nullable UUID npcUuid, @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!isOwnerCompatible(snapshot, ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForToolOrOwner(@Nullable UUID npcUuid,
                                                               @Nullable String toolId,
                                                               @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot byTool = getCoopSnapshotForTool(npcUuid, toolId, ownerUuid);
        if (byTool != null) {
            return byTool;
        }
        return getCoopSnapshotForOwner(npcUuid, ownerUuid);
    }

    /**
     * Compatibility shim for older capture callsites that cannot provide world/block context.
     */
    public void recordCoopSnapshot(@Nullable CoopLinkedNpcSnapshot snapshot) {
        if (!canMutate()) {
            return;
        }
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        captureResident(
                snapshot.npcUuid(),
                snapshot.roleId(),
                CoopSlotContext.legacy(snapshot.coopId(), snapshot.residentSlot(), snapshot.npcUuid()),
                snapshot.ownerId(),
                snapshot.toolIds(),
                snapshot.displayName(),
                null
        );
    }

    public void clearCoopSnapshot(@Nullable UUID npcUuid) {
        if (!canMutate()) {
            return;
        }
        if (npcUuid == null) {
            return;
        }
        boolean changed = removeCoopedIndex(npcUuid);

        for (CoopLedgerEntry entry : ledgerBySlot.values()) {
            if (entry == null) {
                continue;
            }
            if (npcUuid.equals(entry.housedNpcUuid)) {
                entry.housedNpcUuid = null;
                changed = true;
            }
            if (npcUuid.equals(entry.lastReleasedNpcUuid)) {
                entry.lastReleasedNpcUuid = null;
                changed = true;
            }
        }

        if (changed) {
            clearLedgerNpcReferences(npcUuid);
        }
    }

    public void clearLedgerEntry(@Nullable CoopSlotContext context) {
        if (!canMutate()) {
            return;
        }
        if (context == null) {
            return;
        }
        String slotKey = buildSlotKey(context);
        CoopLedgerEntry removed = ledgerBySlot.remove(slotKey);
        if (removed == null) {
            return;
        }
        if (removed.housedNpcUuid != null) {
            removeCoopedIndex(removed.housedNpcUuid);
        }
        clearLedgerSlot(context);
    }

    @Nullable
    public CoopResidentStateSnapshotService.CoopResidentStateSnapshot getStateSnapshotForSlot(
            @Nullable CoopSlotContext context) {
        if (context == null || context.residentSlot() < 0 || normalizeIdentifier(context.coopId()) == null) {
            return null;
        }
        CoopLedgerEntry entry = resolveLedgerEntry(context);
        return entry != null ? entry.stateSnapshot : null;
    }

    @Nullable
    public CoopLedgerSlotSnapshot getLedgerSlotSnapshot(@Nullable CoopSlotContext context) {
        if (context == null || context.residentSlot() < 0 || normalizeIdentifier(context.coopId()) == null) {
            return null;
        }
        CoopLedgerEntry entry = resolveLedgerEntry(context);
        if (entry == null) {
            return null;
        }
        return new CoopLedgerSlotSnapshot(
                entry.housedNpcUuid,
                entry.lastReleasedNpcUuid,
                entry.ownerId,
                sanitizeToolIds(entry.toolIds),
                entry.roleId,
                entry.displayName,
                entry.housedAtMs,
                entry.releasedAtMs,
                entry.stateSnapshot
        );
    }

    public void clearAllLedgerEntries() {
        if (!canMutate()) {
            return;
        }
        if (ledgerBySlot.isEmpty() && coopedByNpc.isEmpty()) {
            return;
        }
        ledgerBySlot.clear();
        coopedByNpc.clear();
        slotKeyByNpc.clear();
        clearLedgerAll();
    }

    /**
     * Captures a housed resident into the authoritative slot ledger.
     */
    public void captureResident(@Nullable UUID npcUuid,
                                @Nullable String roleId,
                                @Nullable CoopSlotContext context,
                                @Nullable UUID fallbackOwnerId,
                                @Nullable String[] fallbackToolIds,
                                @Nullable String fallbackDisplayName,
                                @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
        if (!canMutate()) {
            return;
        }
        if (npcUuid == null || context == null) {
            return;
        }
        String coopId = normalizeIdentifier(context.coopId());
        if (coopId == null) {
            return;
        }

        String slotKey = buildSlotKey(context);
        String normalizedRoleId = normalizeRoleId(roleId);
        UUID ownerId = resolveOwnerId(stateSnapshot, fallbackOwnerId);
        String[] toolIds = resolveToolIds(stateSnapshot, fallbackToolIds);
        String displayName = resolveDisplayName(stateSnapshot, fallbackDisplayName);
        long nowMs = System.currentTimeMillis();

        CoopLedgerEntry previous = ledgerBySlot.get(slotKey);
        boolean occupantChanged = previous == null
                || previous.housedNpcUuid == null
                || !previous.housedNpcUuid.equals(npcUuid);
        boolean snapshotChanged = stateSnapshot != null && (previous == null || previous.stateSnapshot == null);

        CoopLedgerEntry next = new CoopLedgerEntry(
                slotKey,
                normalizeIdentifier(context.worldName()),
                coopId,
                context.x(),
                context.y(),
                context.z(),
                context.residentSlot(),
                npcUuid,
                null,
                ownerId,
                toolIds,
                normalizedRoleId,
                displayName,
                nowMs,
                0L,
                stateSnapshot != null ? stateSnapshot : previous != null ? previous.stateSnapshot : null
        );
        ledgerBySlot.put(slotKey, next);

        if (previous != null && previous.housedNpcUuid != null && !previous.housedNpcUuid.equals(npcUuid)) {
            removeCoopedIndex(previous.housedNpcUuid);
        }
        putCoopedIndex(npcUuid, slotKey, next.toSnapshot());

        if (occupantChanged || snapshotChanged || previous == null) {
            enqueueProfileUpdate(
                    npcUuid,
                    ownerId,
                    normalizedRoleId,
                    displayName,
                    toolIds,
                    coopId,
                    context.residentSlot()
            );
            upsertLedgerEntry(next);
            debugCoop(
                    "ledger capture npc=" + npcUuid
                            + " coop=" + coopId
                            + " slot=" + context.residentSlot()
                            + " world=" + safeWorldLabel(context.worldName())
            );
        }
    }

    /**
     * Recaptures a resident when vanilla removal no longer exposes a stable slot index.
     *
     * <p>Matches the ledger entry using previous released UUID and exact coop coordinates.
     */
    public boolean recaptureResidentFromReleasedUuid(@Nullable UUID npcUuid,
                                                     @Nullable String roleId,
                                                     @Nullable String worldName,
                                                     @Nullable String coopId,
                                                     int x,
                                                     int y,
                                                     int z,
                                                     @Nullable UUID fallbackOwnerId,
                                                     @Nullable String[] fallbackToolIds,
                                                     @Nullable String fallbackDisplayName,
                                                     @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
        if (!canMutate()) {
            return false;
        }
        if (npcUuid == null || coopId == null || coopId.isBlank()) {
            return false;
        }
        CoopLedgerEntry matched = null;
        String normalizedCoopId = normalizeIdentifier(coopId);
        String normalizedWorldName = normalizeIdentifier(worldName);
        for (CoopLedgerEntry entry : ledgerBySlot.values()) {
            if (entry == null || entry.housedNpcUuid != null || entry.lastReleasedNpcUuid == null) {
                continue;
            }
            if (!npcUuid.equals(entry.lastReleasedNpcUuid)) {
                continue;
            }
            if (entry.coopId == null || !entry.coopId.equals(normalizedCoopId)) {
                continue;
            }
            if (!coordinatesMatch(entry, x, y, z)) {
                continue;
            }
            if (!worldMatches(entry.worldName, normalizedWorldName)) {
                continue;
            }
            if (matched != null) {
                debugCoop(
                        "ledger recapture ambiguous npc=" + npcUuid
                                + " coop=" + coopId
                                + " coords=" + x + "," + y + "," + z
                );
                return false;
            }
            matched = entry;
        }
        if (matched == null) {
            return false;
        }
        captureResident(
                npcUuid,
                roleId,
                CoopSlotContext.of(
                        worldName != null ? worldName : matched.worldName,
                        coopId,
                        matched.x,
                        matched.y,
                        matched.z,
                        matched.residentSlot
                ),
                fallbackOwnerId,
                fallbackToolIds,
                fallbackDisplayName,
                stateSnapshot
        );
        debugCoop(
                "ledger recapture npc=" + npcUuid
                        + " coop=" + coopId
                        + " slot=" + matched.residentSlot
                        + " coords=" + matched.x + "," + matched.y + "," + matched.z
        );
        return true;
    }

    @Nonnull
    public ReleaseResolution resolveRelease(@Nullable UUID currentNpcUuid,
                                            @Nullable String roleId,
                                            @Nullable CoopSlotContext context,
                                            boolean requireSnapshotOnRelease) {
        if (!canMutate()) {
            return ReleaseResolution.failure("persistence_unavailable");
        }
        if (currentNpcUuid == null || context == null) {
            return ReleaseResolution.failure("invalid_context");
        }
        if (context.residentSlot() < 0 || normalizeIdentifier(context.coopId()) == null) {
            return ReleaseResolution.failure("invalid_slot");
        }

        CoopLedgerEntry entry = resolveLedgerEntry(context);
        if (entry == null) {
            debugCoop(
                    "release miss current=" + currentNpcUuid
                            + " coop=" + context.coopId()
                            + " slot=" + context.residentSlot()
            );
            return ReleaseResolution.failure("slot_untracked");
        }

        if (entry.housedNpcUuid == null && currentNpcUuid.equals(entry.lastReleasedNpcUuid)) {
            return ReleaseResolution.reconciled();
        }

        if (entry.housedNpcUuid == null) {
            debugCoop(
                    "release blocked missing capture current=" + currentNpcUuid
                            + " coop=" + context.coopId()
                            + " slot=" + context.residentSlot()
            );
            return ReleaseResolution.failure("release_without_capture");
        }

        String normalizedRoleId = normalizeRoleId(roleId);
        if (normalizedRoleId != null && entry.roleId != null && !normalizedRoleId.equals(entry.roleId)) {
            return ReleaseResolution.failure("role_mismatch");
        }

        if (requireSnapshotOnRelease && entry.stateSnapshot == null) {
            debugCoop(
                    "release blocked missing snapshot current=" + currentNpcUuid
                            + " coop=" + context.coopId()
                            + " slot=" + context.residentSlot()
            );
            return ReleaseResolution.failure("snapshot_missing");
        }

        UUID previousNpcUuid = entry.housedNpcUuid;
        CoopLinkedNpcSnapshot linkedSnapshot = previousNpcUuid != null ? coopedByNpc.remove(previousNpcUuid) : null;
        if (previousNpcUuid != null) {
            slotKeyByNpc.remove(previousNpcUuid);
        }
        if (linkedSnapshot == null && previousNpcUuid != null) {
            linkedSnapshot = entry.toSnapshot(previousNpcUuid);
        }

        entry.housedNpcUuid = null;
        entry.lastReleasedNpcUuid = currentNpcUuid;
        entry.releasedAtMs = System.currentTimeMillis();
        if (normalizedRoleId != null) {
            entry.roleId = normalizedRoleId;
        }
        ledgerBySlot.put(entry.slotKey, entry);
        enqueueProfileUpdate(
                currentNpcUuid,
                entry.ownerId,
                entry.roleId,
                entry.displayName,
                entry.toolIds,
                entry.coopId,
                entry.residentSlot
        );
        releaseLedgerEntry(entry, previousNpcUuid, currentNpcUuid);

        debugCoop(
                "release resolved current=" + currentNpcUuid
                        + " previous=" + previousNpcUuid
                        + " coop=" + context.coopId()
                        + " slot=" + context.residentSlot()
                        + " snapshot=" + (entry.stateSnapshot != null)
        );

        return new ReleaseResolution(
                previousNpcUuid,
                entry.stateSnapshot,
                linkedSnapshot,
                false,
                null
        );
    }

    @Nullable
    public CoopLinkedNpcSnapshot consumeSnapshotForReplacement(@Nullable UUID previousNpcUuid,
                                                               @Nullable String coopId,
                                                               int residentSlot,
                                                               @Nullable String roleId) {
        if (previousNpcUuid == null) {
            return null;
        }
        CoopLinkedNpcSnapshot candidate = coopedByNpc.get(previousNpcUuid);
        if (candidate == null) {
            return null;
        }
        if (!isCoopMatchForRespawn(candidate.coopId(), normalizeIdentifier(coopId))) {
            return null;
        }
        if (!isResidentSlotMatchForRespawn(candidate.residentSlot(), residentSlot)) {
            return null;
        }
        if (!isRoleMatchForRespawn(candidate.roleId(), normalizeRoleId(roleId))) {
            return null;
        }
        removeCoopedIndex(previousNpcUuid);
        return candidate;
    }

    @Nullable
    public CoopLinkedNpcSnapshot consumeRespawnSnapshotForCoopResident(@Nullable UUID currentNpcUuid,
                                                                       @Nullable String coopId,
                                                                       int residentSlot,
                                                                       @Nullable String roleId) {
        if (currentNpcUuid == null) {
            return null;
        }

        CoopLinkedNpcSnapshot direct = coopedByNpc.get(currentNpcUuid);
        if (direct != null) {
            removeCoopedIndex(currentNpcUuid);
            return direct;
        }

        String normalizedCoopId = normalizeIdentifier(coopId);
        String normalizedRoleId = normalizeRoleId(roleId);
        CoopLinkedNpcSnapshot match = null;
        for (CoopLinkedNpcSnapshot candidate : coopedByNpc.values()) {
            if (candidate == null || candidate.npcUuid() == null) {
                continue;
            }
            if (!isCoopMatchForRespawn(candidate.coopId(), normalizedCoopId)) {
                continue;
            }
            if (!isResidentSlotMatchForRespawn(candidate.residentSlot(), residentSlot)) {
                continue;
            }
            if (!isRoleMatchForRespawn(candidate.roleId(), normalizedRoleId)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        if (match == null || match.npcUuid() == null) {
            return null;
        }
        removeCoopedIndex(match.npcUuid());
        return match;
    }

    @Nullable
    public CoopLinkedNpcSnapshot consumeRespawnSnapshotForLinks(@Nullable UUID currentNpcUuid,
                                                                @Nullable UUID ownerId,
                                                                @Nullable String[] toolIds,
                                                                @Nullable String roleId) {
        if (currentNpcUuid == null) {
            return null;
        }

        CoopLinkedNpcSnapshot direct = coopedByNpc.get(currentNpcUuid);
        if (direct != null) {
            removeCoopedIndex(currentNpcUuid);
            return direct;
        }

        String[] sanitizedToolIds = sanitizeToolIds(toolIds);
        String normalizedRoleId = normalizeRoleId(roleId);
        CoopLinkedNpcSnapshot match = null;

        for (CoopLinkedNpcSnapshot candidate : coopedByNpc.values()) {
            if (candidate == null || candidate.npcUuid() == null) {
                continue;
            }
            if (!isOwnerMatchForRespawn(candidate.ownerId(), ownerId)) {
                continue;
            }
            if (!sharesAnyToolId(candidate.toolIds(), sanitizedToolIds)) {
                continue;
            }
            if (!isRoleMatchForRespawn(candidate.roleId(), normalizedRoleId)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }

        if (match == null || match.npcUuid() == null) {
            return null;
        }
        removeCoopedIndex(match.npcUuid());
        return match;
    }

    private void loadPersistedLedger() {
        if (repository != null) {
            for (CoopLedgerRow row : repository.loadAll()) {
                CoopLedgerEntry entry = fromLedgerRow(row);
                if (entry == null || entry.slotKey == null) {
                    continue;
                }
                ledgerBySlot.put(entry.slotKey, entry);
                if (entry.housedNpcUuid != null) {
                    putCoopedIndex(entry.housedNpcUuid, entry.slotKey, entry.toSnapshot());
                }
            }
            return;
        }
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            if (!Files.exists(persistencePath)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(persistencePath, StandardCharsets.UTF_8)) {
                    CoopLedgerEntry entry = parseLedgerEntry(line);
                    if (entry == null || entry.slotKey == null) {
                        continue;
                    }
                    ledgerBySlot.put(entry.slotKey, entry);
                    if (entry.housedNpcUuid != null) {
                        putCoopedIndex(entry.housedNpcUuid, entry.slotKey, entry.toSnapshot());
                    }
                }
            } catch (Exception ignored) {
                // Ignore persistence read failures; runtime can rehydrate from live observations.
            }
        }
    }

    private void persistLedger() {
        if (repository != null) {
            return;
        }
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            try {
                if (ledgerBySlot.isEmpty()) {
                    Files.deleteIfExists(persistencePath);
                    return;
                }
                Path parent = persistencePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder builder = new StringBuilder();
                for (CoopLedgerEntry entry : ledgerBySlot.values()) {
                    if (entry == null || entry.slotKey == null) {
                        continue;
                    }
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(encodeLedgerEntry(entry));
                }
                Files.writeString(persistencePath, builder.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Ignore persistence write failures; runtime tracking still works in-memory.
            }
        }
    }

    private String encodeLedgerEntry(@Nonnull CoopLedgerEntry entry) {
        return LEDGER_VERSION
                + FIELD_SEPARATOR + encodeNullableString(entry.worldName)
                + FIELD_SEPARATOR + entry.x
                + FIELD_SEPARATOR + entry.y
                + FIELD_SEPARATOR + entry.z
                + FIELD_SEPARATOR + entry.residentSlot
                + FIELD_SEPARATOR + encodeNullableString(entry.coopId)
                + FIELD_SEPARATOR + encodeNullableUuid(entry.housedNpcUuid)
                + FIELD_SEPARATOR + encodeNullableUuid(entry.lastReleasedNpcUuid)
                + FIELD_SEPARATOR + encodeNullableUuid(entry.ownerId)
                + FIELD_SEPARATOR + encodeStringArray(entry.toolIds)
                + FIELD_SEPARATOR + encodeNullableString(entry.roleId)
                + FIELD_SEPARATOR + encodeNullableString(entry.displayName)
                + FIELD_SEPARATOR + entry.housedAtMs
                + FIELD_SEPARATOR + entry.releasedAtMs;
    }

    @Nullable
    private CoopLedgerEntry parseLedgerEntry(@Nullable String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(FIELD_SEPARATOR, -1);
        if (parts.length < 15 || !LEDGER_VERSION.equals(parts[0])) {
            return null;
        }

        String worldName = decodeNullableString(parts[1]);
        int x = parseInt(parts[2], UNKNOWN_COORDINATE);
        int y = parseInt(parts[3], UNKNOWN_COORDINATE);
        int z = parseInt(parts[4], UNKNOWN_COORDINATE);
        int residentSlot = parseInt(parts[5], -1);
        String coopId = normalizeIdentifier(decodeNullableString(parts[6]));
        if (coopId == null) {
            return null;
        }

        UUID housedNpcUuid = decodeNullableUuid(parts[7]);
        UUID lastReleasedNpcUuid = decodeNullableUuid(parts[8]);
        UUID ownerId = decodeNullableUuid(parts[9]);
        String[] toolIds = sanitizeToolIds(decodeStringArray(parts[10]));
        String roleId = normalizeRoleId(decodeNullableString(parts[11]));
        String displayName = decodeNullableString(parts[12]);
        long housedAtMs = parseLong(parts[13], System.currentTimeMillis());
        long releasedAtMs = parseLong(parts[14], 0L);

        CoopSlotContext context = new CoopSlotContext(worldName, coopId, x, y, z, residentSlot);
        return new CoopLedgerEntry(
                buildSlotKey(context),
                normalizeIdentifier(worldName),
                coopId,
                x,
                y,
                z,
                residentSlot,
                housedNpcUuid,
                lastReleasedNpcUuid,
                ownerId,
                toolIds,
                roleId,
                displayName,
                housedAtMs,
                releasedAtMs,
                null
        );
    }

    @Nonnull
    private CoopLedgerRow toLedgerRow(@Nonnull CoopLedgerEntry entry) {
        return new CoopLedgerRow(
                entry.slotKey,
                entry.worldName,
                entry.coopId,
                entry.x,
                entry.y,
                entry.z,
                entry.residentSlot,
                entry.housedNpcUuid,
                entry.lastReleasedNpcUuid,
                entry.ownerId,
                entry.toolIds,
                entry.roleId,
                entry.displayName,
                entry.housedAtMs,
                entry.releasedAtMs,
                serializeStateSnapshot(entry.stateSnapshot)
        );
    }

    @Nullable
    private CoopLedgerEntry fromLedgerRow(@Nullable CoopLedgerRow row) {
        if (row == null || row.slotKey() == null || row.slotKey().isBlank()) {
            return null;
        }
        String coopId = normalizeIdentifier(row.coopId());
        if (coopId == null) {
            return null;
        }
        return new CoopLedgerEntry(
                row.slotKey(),
                normalizeIdentifier(row.worldName()),
                coopId,
                row.x(),
                row.y(),
                row.z(),
                row.residentSlot(),
                row.housedNpcUuid(),
                row.lastReleasedNpcUuid(),
                row.ownerId(),
                sanitizeToolIds(row.toolIds()),
                normalizeRoleId(row.roleId()),
                row.displayName(),
                row.housedAtMs(),
                row.releasedAtMs(),
                deserializeStateSnapshot(row.stateSnapshotJson())
        );
    }

    private void upsertLedgerEntry(@Nullable CoopLedgerEntry entry) {
        if (entry == null) {
            return;
        }
        if (repository != null) {
            repository.upsertSlotAsync(toLedgerRow(entry));
            return;
        }
        persistLedger();
    }

    private void releaseLedgerEntry(@Nullable CoopLedgerEntry entry,
                                    @Nullable UUID previousNpcUuid,
                                    @Nullable UUID currentNpcUuid) {
        if (entry == null) {
            return;
        }
        if (repository != null) {
            repository.releaseAndRemapAsync(toLedgerRow(entry), previousNpcUuid, currentNpcUuid);
            return;
        }
        persistLedger();
    }

    private void clearLedgerSlot(@Nonnull CoopSlotContext context) {
        if (repository != null) {
            repository.clearSlotAsync(
                    context.worldName(),
                    context.coopId(),
                    context.x(),
                    context.y(),
                    context.z(),
                    context.residentSlot()
            );
            return;
        }
        persistLedger();
    }

    private void clearLedgerNpcReferences(@Nonnull UUID npcUuid) {
        if (repository != null) {
            repository.clearNpcReferencesAsync(npcUuid);
            return;
        }
        persistLedger();
    }

    private void clearLedgerAll() {
        if (repository != null) {
            repository.clearAllAsync();
            return;
        }
        persistLedger();
    }

    private boolean canMutate() {
        if (healthService == null || healthService.isHealthy()) {
            return true;
        }
        PersistenceHealthService.HealthState state = healthService.getState();
        debugCoop(
                "persistence blocked mutation service=coop reason="
                        + (state.reason() != null ? state.reason() : "unknown")
        );
        return false;
    }

    private void enqueueProfileUpdate(@Nullable UUID npcUuid,
                                      @Nullable UUID ownerId,
                                      @Nullable String roleId,
                                      @Nullable String displayName,
                                      @Nullable String[] toolIds,
                                      @Nullable String coopId,
                                      @Nullable Integer coopSlot) {
        if (profileRepository == null || repository != null || npcUuid == null) {
            return;
        }
        profileRepository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                npcUuid,
                ownerId,
                null,
                roleId,
                displayName,
                null,
                null,
                coopId,
                coopSlot,
                null,
                toolIds
        ));
    }

    @Nullable
    private CoopLedgerEntry resolveLedgerEntry(@Nonnull CoopSlotContext context) {
        String exactSlotKey = buildSlotKey(context);
        CoopLedgerEntry exact = ledgerBySlot.get(exactSlotKey);
        if (exact != null) {
            return exact;
        }

        if (hasKnownCoordinates(context)) {
            return null;
        }

        String normalizedCoopId = normalizeIdentifier(context.coopId());
        if (normalizedCoopId == null || context.residentSlot() < 0) {
            return null;
        }

        CoopLedgerEntry candidate = null;
        for (CoopLedgerEntry entry : ledgerBySlot.values()) {
            if (entry == null || entry.housedNpcUuid == null) {
                continue;
            }
            if (!normalizedCoopId.equals(entry.coopId)) {
                continue;
            }
            if (entry.residentSlot != context.residentSlot()) {
                continue;
            }
            if (candidate != null) {
                debugCoop(
                        "release ambiguous fallback coop=" + context.coopId()
                                + " slot=" + context.residentSlot()
                );
                return null;
            }
            candidate = entry;
        }
        return candidate;
    }

    private void putCoopedIndex(@Nonnull UUID npcUuid,
                                @Nonnull String slotKey,
                                @Nonnull CoopLinkedNpcSnapshot snapshot) {
        slotKeyByNpc.put(npcUuid, slotKey);
        coopedByNpc.put(npcUuid, snapshot);
    }

    private boolean coordinatesMatch(@Nonnull CoopLedgerEntry entry, int x, int y, int z) {
        return entry.x == x && entry.y == y && entry.z == z;
    }

    private boolean worldMatches(@Nullable String left, @Nullable String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return true;
        }
        return left.equals(right);
    }

    private boolean removeCoopedIndex(@Nonnull UUID npcUuid) {
        String slotKey = slotKeyByNpc.remove(npcUuid);
        boolean removed = coopedByNpc.remove(npcUuid) != null;
        if (slotKey == null) {
            return removed;
        }
        CoopLedgerEntry entry = ledgerBySlot.get(slotKey);
        if (entry != null && npcUuid.equals(entry.housedNpcUuid)) {
            entry.housedNpcUuid = null;
            ledgerBySlot.put(slotKey, entry);
            removed = true;
        }
        return removed;
    }

    @Nullable
    private UUID resolveOwnerId(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                                @Nullable UUID fallbackOwnerId) {
        UUID ownerId = fallbackOwnerId;
        if (snapshot != null) {
            TameworkCommandLinksComponent links = snapshot.commandLinks();
            if (links != null && links.getOwnerId() != null) {
                ownerId = links.getOwnerId();
            }
            TameworkOwnerComponent owner = snapshot.owner();
            if (owner != null && owner.getOwnerId() != null) {
                ownerId = owner.getOwnerId();
            }
        }
        return ownerId;
    }

    @Nonnull
    private String[] resolveToolIds(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                                    @Nullable String[] fallbackToolIds) {
        if (snapshot != null) {
            TameworkCommandLinksComponent links = snapshot.commandLinks();
            if (links != null && links.getToolIds() != null && links.getToolIds().length > 0) {
                return sanitizeToolIds(links.getToolIds());
            }
        }
        return sanitizeToolIds(fallbackToolIds);
    }

    @Nullable
    private String resolveDisplayName(@Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                                      @Nullable String fallbackDisplayName) {
        if (snapshot != null) {
            TameworkNpcNameComponent npcName = snapshot.npcName();
            if (npcName != null && npcName.getName() != null && !npcName.getName().isBlank()) {
                return npcName.getName();
            }
        }
        if (fallbackDisplayName == null || fallbackDisplayName.isBlank()) {
            return null;
        }
        return fallbackDisplayName;
    }

    private static String safeWorldLabel(@Nullable String worldName) {
        return worldName != null && !worldName.isBlank() ? worldName : "<unknown>";
    }

    @Nullable
    private static String normalizeRoleId(@Nullable String roleId) {
        return normalizeIdentifier(roleId);
    }

    @Nullable
    private static String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String buildSlotKey(@Nonnull CoopSlotContext context) {
        String world = normalizeIdentifier(context.worldName());
        if (world == null) {
            world = "<unknown>";
        }
        return world
                + "|" + context.x()
                + "," + context.y()
                + "," + context.z()
                + "|" + context.residentSlot();
    }

    private static boolean hasKnownCoordinates(@Nonnull CoopSlotContext context) {
        return context.x() != UNKNOWN_COORDINATE
                && context.y() != UNKNOWN_COORDINATE
                && context.z() != UNKNOWN_COORDINATE;
    }

    private String encodeNullableUuid(@Nullable UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    @Nullable
    private UUID decodeNullableUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeNullableString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private String decodeNullableString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String out = new String(decoded, StandardCharsets.UTF_8);
            return out.isBlank() ? null : out;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeStringArray(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(ARRAY_SEPARATOR);
            }
            builder.append(encodeNullableString(value));
        }
        return builder.toString();
    }

    @Nonnull
    private String[] decodeStringArray(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        String[] parts = raw.split(ARRAY_SEPARATOR);
        ArrayList<String> out = new ArrayList<>(parts.length);
        for (String value : parts) {
            String decoded = decodeNullableString(value);
            if (decoded == null || decoded.isBlank()) {
                continue;
            }
            out.add(decoded);
        }
        return out.toArray(new String[0]);
    }

    @Nonnull
    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    private boolean isOwnerCompatible(@Nullable CoopLinkedNpcSnapshot snapshot, @Nullable UUID ownerUuid) {
        if (snapshot == null) {
            return false;
        }
        return snapshot.ownerId() == null || ownerUuid == null || snapshot.ownerId().equals(ownerUuid);
    }

    private boolean isOwnerMatchForRespawn(@Nullable UUID snapshotOwnerId, @Nullable UUID ownerId) {
        if (snapshotOwnerId == null || ownerId == null) {
            return true;
        }
        return snapshotOwnerId.equals(ownerId);
    }

    private boolean sharesAnyToolId(@Nullable String[] left, @Nullable String[] right) {
        String[] leftSanitized = sanitizeToolIds(left);
        String[] rightSanitized = sanitizeToolIds(right);
        if (leftSanitized.length == 0 || rightSanitized.length == 0) {
            return false;
        }
        for (String leftValue : leftSanitized) {
            for (String rightValue : rightSanitized) {
                if (leftValue.equals(rightValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRoleMatchForRespawn(@Nullable String snapshotRoleId, @Nullable String normalizedCurrentRoleId) {
        String normalizedSnapshotRoleId = normalizeRoleId(snapshotRoleId);
        if (normalizedSnapshotRoleId == null || normalizedCurrentRoleId == null) {
            return true;
        }
        return normalizedSnapshotRoleId.equals(normalizedCurrentRoleId);
    }

    private boolean isCoopMatchForRespawn(@Nullable String snapshotCoopId, @Nullable String normalizedCurrentCoopId) {
        String normalizedSnapshotCoopId = normalizeIdentifier(snapshotCoopId);
        if (normalizedSnapshotCoopId == null || normalizedCurrentCoopId == null) {
            return false;
        }
        return normalizedSnapshotCoopId.equals(normalizedCurrentCoopId);
    }

    private boolean isResidentSlotMatchForRespawn(int snapshotResidentSlot, int currentResidentSlot) {
        if (snapshotResidentSlot < 0 || currentResidentSlot < 0) {
            return false;
        }
        return snapshotResidentSlot == currentResidentSlot;
    }

    private int parseInt(@Nullable String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(@Nullable String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nullable
    private String serializeStateSnapshot(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return null;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("version", SNAPSHOT_VERSION);
            payload.addProperty("npcUuid", snapshot.npcUuid().toString());
            payload.addProperty("coopId", snapshot.coopId());
            payload.addProperty("residentSlot", snapshot.residentSlot());
            payload.addProperty("roleId", snapshot.roleId());
            payload.addProperty("capturedAtMs", snapshot.capturedAtMs());
            putComponentJson(payload, "commandLinks", snapshot.commandLinks(), TameworkCommandLinksComponent.class);
            putComponentJson(payload, "owner", snapshot.owner(), TameworkOwnerComponent.class);
            putComponentJson(payload, "tamed", snapshot.tamed(), TameworkTamedComponent.class);
            putComponentJson(payload, "npcName", snapshot.npcName(), TameworkNpcNameComponent.class);
            putComponentJson(payload, "happiness", snapshot.happiness(),
                    com.alechilles.alecstamework.npc.components.TameworkHappinessComponent.class);
            putComponentJson(payload, "needs", snapshot.needs(),
                    com.alechilles.alecstamework.npc.components.TameworkNeedsComponent.class);
            putComponentJson(payload, "breeding", snapshot.breeding(),
                    com.alechilles.alecstamework.npc.components.TameworkBreedingComponent.class);
            putComponentJson(payload, "traits", snapshot.traits(),
                    com.alechilles.alecstamework.npc.components.TameworkTraitsComponent.class);
            putComponentJson(payload, "lifeStage", snapshot.lifeStage(),
                    com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent.class);
            putComponentJson(payload, "attachments", snapshot.attachments(),
                    com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent.class);
            return payload.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot deserializeStateSnapshot(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject payload = parsed.getAsJsonObject();
            String version = getJsonString(payload, "version");
            if (version != null && !SNAPSHOT_VERSION.equals(version)) {
                return null;
            }
            UUID npcUuid = parseUuid(getJsonString(payload, "npcUuid"));
            if (npcUuid == null) {
                return null;
            }
            String coopId = normalizeIdentifier(getJsonString(payload, "coopId"));
            int residentSlot = getJsonInt(payload, "residentSlot", -1);
            String roleId = normalizeRoleId(getJsonString(payload, "roleId"));
            long capturedAtMs = Math.max(0L, getJsonLong(payload, "capturedAtMs", 0L));
            TameworkCommandLinksComponent commandLinks =
                    parseComponent(payload, "commandLinks", TameworkCommandLinksComponent.class);
            TameworkOwnerComponent owner = parseComponent(payload, "owner", TameworkOwnerComponent.class);
            TameworkTamedComponent tamed = parseComponent(payload, "tamed", TameworkTamedComponent.class);
            TameworkNpcNameComponent npcName = parseComponent(payload, "npcName", TameworkNpcNameComponent.class);
            com.alechilles.alecstamework.npc.components.TameworkHappinessComponent happiness =
                    parseComponent(payload, "happiness",
                            com.alechilles.alecstamework.npc.components.TameworkHappinessComponent.class);
            com.alechilles.alecstamework.npc.components.TameworkNeedsComponent needs =
                    parseComponent(payload, "needs",
                            com.alechilles.alecstamework.npc.components.TameworkNeedsComponent.class);
            com.alechilles.alecstamework.npc.components.TameworkBreedingComponent breeding =
                    parseComponent(payload, "breeding",
                            com.alechilles.alecstamework.npc.components.TameworkBreedingComponent.class);
            com.alechilles.alecstamework.npc.components.TameworkTraitsComponent traits =
                    parseComponent(payload, "traits",
                            com.alechilles.alecstamework.npc.components.TameworkTraitsComponent.class);
            com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent lifeStage =
                    parseComponent(payload, "lifeStage",
                            com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent.class);
            com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent attachments =
                    parseComponent(payload, "attachments",
                            com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent.class);
            return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                    npcUuid,
                    coopId,
                    residentSlot,
                    roleId,
                    commandLinks,
                    owner,
                    tamed,
                    npcName,
                    happiness,
                    needs,
                    breeding,
                    traits,
                    lifeStage,
                    attachments,
                    capturedAtMs
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T> void putComponentJson(@Nonnull JsonObject payload,
                                      @Nonnull String field,
                                      @Nullable T component,
                                      @Nonnull Class<T> componentClass) {
        if (component == null) {
            return;
        }
        JsonElement value = SNAPSHOT_JSON.toJsonTree(component, componentClass);
        if (value != null && value.isJsonObject()) {
            payload.add(field, value.getAsJsonObject());
        }
    }

    @Nullable
    private <T> T parseComponent(@Nonnull JsonObject payload, @Nonnull String field, @Nonnull Class<T> componentClass) {
        if (!payload.has(field) || !payload.get(field).isJsonObject()) {
            return null;
        }
        try {
            return SNAPSHOT_JSON.fromJson(payload.getAsJsonObject(field), componentClass);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String getJsonString(@Nonnull JsonObject payload, @Nonnull String key) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = payload.get(key).getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int getJsonInt(@Nonnull JsonObject payload, @Nonnull String key, int fallback) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return payload.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long getJsonLong(@Nonnull JsonObject payload, @Nonnull String key, long fallback) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return payload.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void debugCoop(@Nonnull String message) {
        CoopDebugLogger.log(message);
    }

    /**
     * World + coop slot identity used by the authoritative ledger.
     */
    public record CoopSlotContext(@Nullable String worldName,
                                  @Nullable String coopId,
                                  int x,
                                  int y,
                                  int z,
                                  int residentSlot) {
        public static CoopSlotContext of(@Nullable String worldName,
                                         @Nullable String coopId,
                                         int x,
                                         int y,
                                         int z,
                                         int residentSlot) {
            return new CoopSlotContext(worldName, coopId, x, y, z, residentSlot);
        }

        public static CoopSlotContext legacy(@Nullable String coopId, int residentSlot) {
            return new CoopSlotContext(null, coopId, UNKNOWN_COORDINATE, UNKNOWN_COORDINATE, UNKNOWN_COORDINATE, residentSlot);
        }

        public static CoopSlotContext legacy(@Nullable String coopId, int residentSlot, @Nullable UUID npcUuid) {
            if (npcUuid == null) {
                return legacy(coopId, residentSlot);
            }
            int legacyMarker = npcUuid.hashCode();
            return new CoopSlotContext(null, coopId, UNKNOWN_COORDINATE, legacyMarker, UNKNOWN_COORDINATE, residentSlot);
        }
    }

    public record ReleaseResolution(@Nullable UUID previousNpcUuid,
                                    @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot,
                                    @Nullable CoopLinkedNpcSnapshot linkedSnapshot,
                                    boolean alreadyReconciled,
                                    @Nullable String failureReason) {
        public static ReleaseResolution failure(@Nonnull String reason) {
            return new ReleaseResolution(null, null, null, false, reason);
        }

        public static ReleaseResolution reconciled() {
            return new ReleaseResolution(null, null, null, true, null);
        }

        public boolean isFailure() {
            return failureReason != null && !failureReason.isBlank();
        }
    }

    public record CoopLedgerSlotSnapshot(@Nullable UUID housedNpcUuid,
                                         @Nullable UUID lastReleasedNpcUuid,
                                         @Nullable UUID ownerId,
                                         @Nullable String[] toolIds,
                                         @Nullable String roleId,
                                         @Nullable String displayName,
                                         long housedAtMs,
                                         long releasedAtMs,
                                         @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
    }

    private static final class CoopLedgerEntry {
        private final String slotKey;
        @Nullable
        private final String worldName;
        @Nullable
        private final String coopId;
        private final int x;
        private final int y;
        private final int z;
        private final int residentSlot;
        @Nullable
        private UUID housedNpcUuid;
        @Nullable
        private UUID lastReleasedNpcUuid;
        @Nullable
        private UUID ownerId;
        @Nonnull
        private String[] toolIds;
        @Nullable
        private String roleId;
        @Nullable
        private String displayName;
        private long housedAtMs;
        private long releasedAtMs;
        @Nullable
        private CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot;

        private CoopLedgerEntry(@Nonnull String slotKey,
                                @Nullable String worldName,
                                @Nullable String coopId,
                                int x,
                                int y,
                                int z,
                                int residentSlot,
                                @Nullable UUID housedNpcUuid,
                                @Nullable UUID lastReleasedNpcUuid,
                                @Nullable UUID ownerId,
                                @Nonnull String[] toolIds,
                                @Nullable String roleId,
                                @Nullable String displayName,
                                long housedAtMs,
                                long releasedAtMs,
                                @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
            this.slotKey = slotKey;
            this.worldName = worldName;
            this.coopId = coopId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.residentSlot = residentSlot;
            this.housedNpcUuid = housedNpcUuid;
            this.lastReleasedNpcUuid = lastReleasedNpcUuid;
            this.ownerId = ownerId;
            this.toolIds = toolIds;
            this.roleId = roleId;
            this.displayName = displayName;
            this.housedAtMs = housedAtMs;
            this.releasedAtMs = releasedAtMs;
            this.stateSnapshot = stateSnapshot;
        }

        @Nonnull
        private CoopLinkedNpcSnapshot toSnapshot() {
            UUID snapshotUuid = housedNpcUuid != null ? housedNpcUuid : UUID.randomUUID();
            return toSnapshot(snapshotUuid);
        }

        @Nonnull
        private CoopLinkedNpcSnapshot toSnapshot(@Nonnull UUID npcUuid) {
            return new CoopLinkedNpcSnapshot(
                    npcUuid,
                    ownerId,
                    toolIds,
                    roleId,
                    displayName,
                    coopId,
                    residentSlot,
                    housedAtMs
            );
        }
    }

    /** Snapshot of a linked companion currently housed in a coop. */
    public record CoopLinkedNpcSnapshot(UUID npcUuid,
                                        @Nullable UUID ownerId,
                                        @Nullable String[] toolIds,
                                        @Nullable String roleId,
                                        @Nullable String displayName,
                                        @Nullable String coopId,
                                        int residentSlot,
                                        long housedAtMs) {
        public boolean containsToolId(@Nullable String toolId) {
            if (toolId == null || toolId.isBlank() || toolIds == null || toolIds.length == 0) {
                return false;
            }
            for (String value : toolIds) {
                if (toolId.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
