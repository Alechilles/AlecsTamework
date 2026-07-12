package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rollback-only implementation of the pre-schema-v5 command-coop ledger.
 *
 * <p>Managed-coop admission is performed by the schema-v5 lifecycle services. This collaborator
 * exists solely to keep unmanaged/rollback callers and legacy migrations operational.</p>
 */
final class LegacyCommandCoopLedger {
    private final ConcurrentHashMap<String, LegacyCoopLedgerEntry> entries = new ConcurrentHashMap<>();
    private final LegacyCoopSnapshotPool snapshots = new LegacyCoopSnapshotPool();
    private final LegacyCoopLedgerPersistence persistence;
    @Nullable private final PersistenceHealthService healthService;

    LegacyCommandCoopLedger(@Nonnull LegacyCoopLedgerPersistence persistence,
                            @Nullable PersistenceHealthService healthService) {
        this.persistence = persistence;
        this.healthService = healthService;
        for (LegacyCoopLedgerEntry entry : persistence.load()) {
            entries.put(entry.slotKey, entry);
            if (entry.housedNpcUuid != null) {
                snapshots.put(entry.housedNpcUuid, entry.toSnapshot(entry.housedNpcUuid));
            }
        }
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot(@Nullable UUID npcUuid) {
        return snapshots.get(npcUuid);
    }

    void record(@Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        capture(
                snapshot.npcUuid(), snapshot.roleId(),
                CommandLinkedNpcCoopService.CoopSlotContext.legacy(
                        snapshot.coopId(), snapshot.residentSlot(), snapshot.npcUuid()),
                snapshot.ownerId(), snapshot.toolIds(), snapshot.displayName(), null);
    }

    void clearSnapshot(@Nullable UUID npcUuid) {
        if (!canMutate() || npcUuid == null) {
            return;
        }
        boolean changed = snapshots.remove(npcUuid);
        for (LegacyCoopLedgerEntry entry : entries.values()) {
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
            persistence.clearNpcReferences(npcUuid, entries.values());
        }
    }

    void clearEntry(@Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        if (!canMutate() || context == null) {
            return;
        }
        LegacyCoopLedgerEntry removed = entries.remove(LegacyCoopLedgerSupport.slotKey(context));
        if (removed == null) {
            return;
        }
        if (removed.housedNpcUuid != null) {
            snapshots.remove(removed.housedNpcUuid);
        }
        persistence.clearSlot(context, entries.values());
    }

    @Nullable
    CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot(
            @Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        LegacyCoopLedgerEntry entry = validEntry(context);
        return entry == null ? null : entry.stateSnapshot;
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot slotSnapshot(
            @Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        LegacyCoopLedgerEntry entry = validEntry(context);
        if (entry == null) {
            return null;
        }
        return new CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot(
                entry.housedNpcUuid, entry.lastReleasedNpcUuid, entry.ownerId,
                LegacyCoopLedgerSupport.sanitizeToolIds(entry.toolIds), entry.roleId,
                entry.displayName, entry.housedAtMs, entry.releasedAtMs, entry.stateSnapshot);
    }

    @Nonnull
    List<CommandLinkedNpcCoopService.CoopSlotContext> housedSlots(@Nullable String worldName) {
        String world = LegacyCoopLedgerSupport.normalize(worldName);
        if (world == null) {
            return List.of();
        }
        ArrayList<CommandLinkedNpcCoopService.CoopSlotContext> result = new ArrayList<>();
        for (LegacyCoopLedgerEntry entry : entries.values()) {
            if (entry.housedNpcUuid == null || entry.coopId == null || entry.residentSlot < 0
                    || !world.equals(entry.worldName)
                    || !LegacyCoopLedgerSupport.hasKnownCoordinates(entry.x, entry.y, entry.z)) {
                continue;
            }
            result.add(CommandLinkedNpcCoopService.CoopSlotContext.of(
                    entry.worldName, entry.coopId, entry.x, entry.y, entry.z, entry.residentSlot));
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    void clearAll() {
        if (!canMutate() || entries.isEmpty() && snapshots.isEmpty()) {
            return;
        }
        entries.clear();
        snapshots.clear();
        persistence.clearAll(entries.values());
    }

    void capture(@Nullable UUID npcUuid,
                 @Nullable String roleId,
                 @Nullable CommandLinkedNpcCoopService.CoopSlotContext context,
                 @Nullable UUID fallbackOwnerId,
                 @Nullable String[] fallbackToolIds,
                 @Nullable String fallbackDisplayName,
                 @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot) {
        if (!canMutate() || npcUuid == null || context == null) {
            return;
        }
        String coopId = LegacyCoopLedgerSupport.normalize(context.coopId());
        if (coopId == null) {
            return;
        }
        String slotKey = LegacyCoopLedgerSupport.slotKey(context);
        LegacyCoopLedgerEntry previous = entries.get(slotKey);
        UUID ownerId = LegacyCoopLedgerSupport.ownerId(stateSnapshot, fallbackOwnerId);
        String[] toolIds = LegacyCoopLedgerSupport.toolIds(stateSnapshot, fallbackToolIds);
        String displayName = LegacyCoopLedgerSupport.displayName(stateSnapshot, fallbackDisplayName);
        long nowMs = System.currentTimeMillis();
        LegacyCoopLedgerEntry next = new LegacyCoopLedgerEntry(
                slotKey, LegacyCoopLedgerSupport.normalize(context.worldName()), coopId,
                context.x(), context.y(), context.z(), context.residentSlot(), npcUuid, null,
                ownerId, toolIds, LegacyCoopLedgerSupport.normalize(roleId), displayName,
                nowMs, 0L,
                stateSnapshot != null ? stateSnapshot : previous == null ? null : previous.stateSnapshot);
        entries.put(slotKey, next);
        if (previous != null && previous.housedNpcUuid != null
                && !previous.housedNpcUuid.equals(npcUuid)) {
            snapshots.remove(previous.housedNpcUuid);
        }
        snapshots.put(npcUuid, next.toSnapshot(npcUuid));
        persistence.upsert(next, entries.values());
        CoopDebugLogger.log("legacy ledger capture npc=" + npcUuid + " coop=" + coopId
                + " slot=" + context.residentSlot());
    }

    boolean recapture(@Nullable UUID npcUuid,
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
        if (!canMutate() || npcUuid == null || coopId == null || coopId.isBlank()) {
            return false;
        }
        String normalizedCoop = LegacyCoopLedgerSupport.normalize(coopId);
        String normalizedWorld = LegacyCoopLedgerSupport.normalize(worldName);
        LegacyCoopLedgerEntry match = null;
        for (LegacyCoopLedgerEntry entry : entries.values()) {
            if (entry.housedNpcUuid != null || !npcUuid.equals(entry.lastReleasedNpcUuid)
                    || !normalizedCoop.equals(entry.coopId)
                    || entry.x != x || entry.y != y || entry.z != z
                    || !LegacyCoopLedgerSupport.worldMatches(entry.worldName, normalizedWorld)) {
                continue;
            }
            if (match != null) {
                return false;
            }
            match = entry;
        }
        if (match == null) {
            return false;
        }
        capture(npcUuid, roleId, CommandLinkedNpcCoopService.CoopSlotContext.of(
                        worldName != null ? worldName : match.worldName,
                        coopId, match.x, match.y, match.z, match.residentSlot),
                fallbackOwnerId, fallbackToolIds, fallbackDisplayName, stateSnapshot);
        return true;
    }

    @Nonnull
    CommandLinkedNpcCoopService.ReleaseResolution release(
            @Nullable UUID currentNpcUuid,
            @Nullable String roleId,
            @Nullable CommandLinkedNpcCoopService.CoopSlotContext context,
            boolean requireSnapshot) {
        if (!canMutate()) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("persistence_unavailable");
        }
        if (currentNpcUuid == null || context == null) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("invalid_context");
        }
        LegacyCoopLedgerEntry entry = validEntry(context);
        if (entry == null) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("slot_untracked");
        }
        if (entry.housedNpcUuid == null && currentNpcUuid.equals(entry.lastReleasedNpcUuid)) {
            return CommandLinkedNpcCoopService.ReleaseResolution.reconciled();
        }
        if (entry.housedNpcUuid == null) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("release_without_capture");
        }
        String normalizedRole = LegacyCoopLedgerSupport.normalize(roleId);
        if (normalizedRole != null && entry.roleId != null && !normalizedRole.equals(entry.roleId)) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("role_mismatch");
        }
        if (requireSnapshot && entry.stateSnapshot == null) {
            return CommandLinkedNpcCoopService.ReleaseResolution.failure("snapshot_missing");
        }
        UUID previousUuid = entry.housedNpcUuid;
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot linked = snapshots.get(previousUuid);
        snapshots.remove(previousUuid);
        if (linked == null) {
            linked = entry.toSnapshot(previousUuid);
        }
        entry.housedNpcUuid = null;
        entry.lastReleasedNpcUuid = currentNpcUuid;
        entry.releasedAtMs = System.currentTimeMillis();
        if (normalizedRole != null) {
            entry.roleId = normalizedRole;
        }
        persistence.release(entry, previousUuid, currentNpcUuid, entries.values());
        return new CommandLinkedNpcCoopService.ReleaseResolution(
                previousUuid, entry.stateSnapshot, linked, false, null);
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeReplacement(
            @Nullable UUID previousNpcUuid, @Nullable String coopId, int slot, @Nullable String roleId) {
        return consumeAndClear(snapshots.consumeReplacement(previousNpcUuid, coopId, slot, roleId));
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeCoopResident(
            @Nullable UUID currentNpcUuid, @Nullable String coopId, int slot, @Nullable String roleId) {
        return consumeAndClear(snapshots.consumeCoopResident(currentNpcUuid, coopId, slot, roleId));
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeLinks(
            @Nullable UUID currentNpcUuid, @Nullable UUID ownerId, @Nullable String[] toolIds,
            @Nullable String roleId) {
        return consumeAndClear(snapshots.consumeLinks(currentNpcUuid, ownerId, toolIds, roleId));
    }

    @Nullable
    private CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeAndClear(
            @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return null;
        }
        for (LegacyCoopLedgerEntry entry : entries.values()) {
            if (snapshot.npcUuid().equals(entry.housedNpcUuid)) {
                entry.housedNpcUuid = null;
            }
        }
        return snapshot;
    }

    @Nullable
    private LegacyCoopLedgerEntry validEntry(
            @Nullable CommandLinkedNpcCoopService.CoopSlotContext context) {
        if (context == null || context.residentSlot() < 0
                || LegacyCoopLedgerSupport.normalize(context.coopId()) == null) {
            return null;
        }
        LegacyCoopLedgerEntry exact = entries.get(LegacyCoopLedgerSupport.slotKey(context));
        if (exact != null || LegacyCoopLedgerSupport.hasKnownCoordinates(context)) {
            return exact;
        }
        String coopId = LegacyCoopLedgerSupport.normalize(context.coopId());
        LegacyCoopLedgerEntry match = null;
        for (LegacyCoopLedgerEntry entry : entries.values()) {
            if (entry.housedNpcUuid == null || !coopId.equals(entry.coopId)
                    || entry.residentSlot != context.residentSlot()) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = entry;
        }
        return match;
    }

    private boolean canMutate() {
        if (healthService == null || healthService.isHealthy()) {
            return true;
        }
        PersistenceHealthService.HealthState state = healthService.getState();
        CoopDebugLogger.log("legacy coop mutation blocked reason="
                + (state.reason() == null ? "unknown" : state.reason()));
        return false;
    }
}
