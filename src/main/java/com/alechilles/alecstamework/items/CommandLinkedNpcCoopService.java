package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLedgerRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLedgerRow;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility and command-query facade over coop assignment state.
 *
 * <p>Trusted schema-v5 assignments are the only managed-coop view exposed to command panels,
 * movement, relocation, and lost checks. The legacy ledger collaborator remains available solely
 * for unmanaged rollback compatibility and DAT/v4 migration; it never mutates managed state.</p>
 */
public final class CommandLinkedNpcCoopService {
    private final LegacyCommandCoopLedger legacy;
    private final ManagedCoopAssignmentQuery managed;

    public CommandLinkedNpcCoopService() {
        this(null, null, null, null, null, null);
    }

    public CommandLinkedNpcCoopService(@Nullable Path persistencePath) {
        this(persistencePath, null, null, null, null, null);
    }

    public CommandLinkedNpcCoopService(@Nonnull CoopLedgerRepository repository,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nullable NpcProfileRepository profileRepository) {
        this(null, repository, healthService, profileRepository, null, null);
    }

    /** Creates the runtime facade with trusted schema-v5 managed-assignment visibility. */
    public CommandLinkedNpcCoopService(@Nonnull CoopLedgerRepository repository,
                                       @Nonnull PersistenceHealthService healthService,
                                       @Nonnull NpcProfileRepository profileRepository,
                                       @Nonnull ManagedCoopResidentIndex managedResidentIndex,
                                       @Nonnull BooleanSupplier managedTrustGate) {
        this(null, repository, healthService, profileRepository, managedResidentIndex, managedTrustGate);
    }

    private CommandLinkedNpcCoopService(@Nullable Path persistencePath,
                                        @Nullable CoopLedgerRepository repository,
                                        @Nullable PersistenceHealthService healthService,
                                        @Nullable NpcProfileRepository profileRepository,
                                        @Nullable ManagedCoopResidentIndex managedResidentIndex,
                                        @Nullable BooleanSupplier managedTrustGate) {
        legacy = new LegacyCommandCoopLedger(
                new LegacyCoopLedgerPersistence(persistencePath, repository), healthService);
        managed = new ManagedCoopAssignmentQuery(
                managedResidentIndex, managedTrustGate, profileRepository);
    }

    @Nonnull
    public static List<CoopLedgerRow> loadLegacyLedgerRows(@Nullable Path legacyPath) {
        return LegacyCoopLedgerPersistence.loadDatRows(legacyPath);
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshot(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        ManagedCoopAssignmentQuery.Lookup lookup = managed.byUuid(npcUuid);
        if (lookup.managedAssignment() || managed.configured() && !managed.trusted()) {
            return lookup.snapshot();
        }
        return legacy.snapshot(npcUuid);
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForTool(@Nullable UUID npcUuid,
                                                        @Nullable String toolId,
                                                        @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        return snapshot != null && snapshot.containsToolId(toolId) && ownerCompatible(snapshot, ownerUuid)
                ? snapshot
                : null;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForOwner(@Nullable UUID npcUuid,
                                                         @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        return snapshot != null && ownerCompatible(snapshot, ownerUuid) ? snapshot : null;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForToolOrOwner(@Nullable UUID npcUuid,
                                                               @Nullable String toolId,
                                                               @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot byTool = getCoopSnapshotForTool(npcUuid, toolId, ownerUuid);
        return byTool != null ? byTool : getCoopSnapshotForOwner(npcUuid, ownerUuid);
    }

    /** Legacy rollback shim; managed assignments are rejected rather than rewritten. */
    public void recordCoopSnapshot(@Nullable CoopLinkedNpcSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        CoopSlotContext context = CoopSlotContext.legacy(
                snapshot.coopId(), snapshot.residentSlot(), snapshot.npcUuid());
        if (managed.permitsLegacyMutation(snapshot.npcUuid(), context)) {
            legacy.record(snapshot);
        }
    }

    public void clearCoopSnapshot(@Nullable UUID npcUuid) {
        legacy.clearSnapshot(npcUuid);
    }

    public void clearLedgerEntry(@Nullable CoopSlotContext context) {
        if (managed.permitsLegacyMutation(null, context)) {
            legacy.clearEntry(context);
        }
    }

    @Nullable
    public CoopResidentStateSnapshotService.CoopResidentStateSnapshot getStateSnapshotForSlot(
            @Nullable CoopSlotContext context) {
        ManagedCoopAssignmentQuery.SlotLookup lookup = managed.bySlot(context);
        if (lookup.managedAuthority() || managed.configured() && !managed.trusted()) {
            return lookup.snapshot() == null ? null : managed.stateSnapshot(lookup.resident());
        }
        return legacy.stateSnapshot(context);
    }

    @Nullable
    public CoopLedgerSlotSnapshot getLedgerSlotSnapshot(@Nullable CoopSlotContext context) {
        ManagedCoopAssignmentQuery.SlotLookup lookup = managed.bySlot(context);
        if (lookup.managedAuthority() || managed.configured() && !managed.trusted()) {
            return managedSlotSnapshot(lookup);
        }
        return legacy.slotSnapshot(context);
    }

    @Nonnull
    public List<CoopSlotContext> listHousedSlotsForWorld(@Nullable String worldName) {
        if (managed.configured() && !managed.trusted()) {
            return List.of();
        }
        List<CoopSlotContext> managedSlots = managed.housedSlots(worldName);
        LinkedHashMap<String, CoopSlotContext> combined = new LinkedHashMap<>();
        for (CoopSlotContext slot : managedSlots) {
            combined.put(LegacyCoopLedgerSupport.slotKey(slot), slot);
        }
        for (CoopSlotContext slot : legacy.housedSlots(worldName)) {
            combined.putIfAbsent(LegacyCoopLedgerSupport.slotKey(slot), slot);
        }
        return combined.isEmpty() ? List.of() : List.copyOf(combined.values());
    }

    public void clearAllLedgerEntries() {
        legacy.clearAll();
    }

    /** Legacy rollback mutation; exact schema-v5 assignments fail closed. */
    public void captureResident(@Nullable UUID npcUuid,
                                @Nullable String roleId,
                                @Nullable CoopSlotContext context,
                                @Nullable UUID fallbackOwnerId,
                                @Nullable String[] fallbackToolIds,
                                @Nullable String fallbackDisplayName,
                                @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
        if (managed.permitsLegacyMutation(npcUuid, context)) {
            legacy.capture(npcUuid, roleId, context, fallbackOwnerId, fallbackToolIds,
                    fallbackDisplayName, stateSnapshot);
        }
    }

    public boolean recaptureResidentFromReleasedUuid(
            @Nullable UUID npcUuid,
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
        CoopSlotContext context = CoopSlotContext.of(worldName, coopId, x, y, z, 0);
        return managed.permitsLegacyMutation(npcUuid, context)
                && legacy.recapture(npcUuid, roleId, worldName, coopId, x, y, z,
                fallbackOwnerId, fallbackToolIds, fallbackDisplayName, stateSnapshot);
    }

    @Nonnull
    public ReleaseResolution resolveRelease(@Nullable UUID currentNpcUuid,
                                            @Nullable String roleId,
                                            @Nullable CoopSlotContext context,
                                            boolean requireSnapshotOnRelease) {
        if (!managed.permitsLegacyMutation(currentNpcUuid, context)) {
            return ReleaseResolution.failure("managed_assignment_owned_by_schema_v5");
        }
        return legacy.release(currentNpcUuid, roleId, context, requireSnapshotOnRelease);
    }

    @Nullable
    public CoopLinkedNpcSnapshot consumeSnapshotForReplacement(@Nullable UUID previousNpcUuid,
                                                               @Nullable String coopId,
                                                               int residentSlot,
                                                               @Nullable String roleId) {
        return managed.permitsLegacyMutation(previousNpcUuid, null)
                ? legacy.consumeReplacement(previousNpcUuid, coopId, residentSlot, roleId)
                : null;
    }

    @Nullable
    public CoopLinkedNpcSnapshot consumeRespawnSnapshotForCoopResident(@Nullable UUID currentNpcUuid,
                                                                       @Nullable String coopId,
                                                                       int residentSlot,
                                                                       @Nullable String roleId) {
        return managed.permitsLegacyMutation(currentNpcUuid, null)
                ? legacy.consumeCoopResident(currentNpcUuid, coopId, residentSlot, roleId)
                : null;
    }

    @Nullable
    public CoopLinkedNpcSnapshot consumeRespawnSnapshotForLinks(@Nullable UUID currentNpcUuid,
                                                                @Nullable UUID ownerId,
                                                                @Nullable String[] toolIds,
                                                                @Nullable String roleId) {
        return managed.permitsLegacyMutation(currentNpcUuid, null)
                ? legacy.consumeLinks(currentNpcUuid, ownerId, toolIds, roleId)
                : null;
    }

    @Nullable
    private CoopLedgerSlotSnapshot managedSlotSnapshot(ManagedCoopAssignmentQuery.SlotLookup lookup) {
        CoopLinkedNpcSnapshot snapshot = lookup.snapshot();
        ResidentRecord resident = lookup.resident();
        if (snapshot == null || resident == null) {
            return null;
        }
        return new CoopLedgerSlotSnapshot(
                snapshot.npcUuid(), null, snapshot.ownerId(), snapshot.toolIds(), snapshot.roleId(),
                snapshot.displayName(), snapshot.housedAtMs(), 0L, managed.stateSnapshot(resident));
    }

    private boolean ownerCompatible(@Nonnull CoopLinkedNpcSnapshot snapshot, @Nullable UUID ownerUuid) {
        return snapshot.ownerId() == null || ownerUuid == null || snapshot.ownerId().equals(ownerUuid);
    }

    /** World + exact block + resident slot identity used by compatibility callers. */
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
            return new CoopSlotContext(null, coopId, LegacyCoopLedgerSupport.UNKNOWN_COORDINATE,
                    LegacyCoopLedgerSupport.UNKNOWN_COORDINATE,
                    LegacyCoopLedgerSupport.UNKNOWN_COORDINATE, residentSlot);
        }

        public static CoopSlotContext legacy(@Nullable String coopId,
                                             int residentSlot,
                                             @Nullable UUID npcUuid) {
            if (npcUuid == null) {
                return legacy(coopId, residentSlot);
            }
            return new CoopSlotContext(null, coopId, LegacyCoopLedgerSupport.UNKNOWN_COORDINATE,
                    npcUuid.hashCode(), LegacyCoopLedgerSupport.UNKNOWN_COORDINATE, residentSlot);
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
        public CoopLedgerSlotSnapshot {
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
        }
    }

    /** Snapshot of a command-linked companion currently assigned to a coop. */
    public record CoopLinkedNpcSnapshot(@Nonnull UUID npcUuid,
                                        @Nullable UUID ownerId,
                                        @Nullable String[] toolIds,
                                        @Nullable String roleId,
                                        @Nullable String displayName,
                                        @Nullable String coopId,
                                        int residentSlot,
                                        long housedAtMs) {
        public CoopLinkedNpcSnapshot {
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
        }

        public boolean containsToolId(@Nullable String toolId) {
            if (toolId == null || toolId.isBlank()) {
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
