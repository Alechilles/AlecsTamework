package com.alechilles.alecstamework.items;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** In-memory snapshot matching used only by the rollback command-coop ledger. */
final class LegacyCoopSnapshotPool {
    private final ConcurrentHashMap<UUID, CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot> byNpc =
            new ConcurrentHashMap<>();

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot get(@Nullable UUID npcUuid) {
        return npcUuid == null ? null : byNpc.get(npcUuid);
    }

    void put(@Nonnull UUID npcUuid,
             @Nonnull CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot) {
        byNpc.put(npcUuid, snapshot);
    }

    boolean remove(@Nonnull UUID npcUuid) {
        return byNpc.remove(npcUuid) != null;
    }

    boolean isEmpty() {
        return byNpc.isEmpty();
    }

    void clear() {
        byNpc.clear();
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeReplacement(
            @Nullable UUID previousNpcUuid,
            @Nullable String coopId,
            int residentSlot,
            @Nullable String roleId) {
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot candidate = get(previousNpcUuid);
        if (!matchesCoopSlotRole(candidate, coopId, residentSlot, roleId)) {
            return null;
        }
        byNpc.remove(previousNpcUuid, candidate);
        return candidate;
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeCoopResident(
            @Nullable UUID currentNpcUuid,
            @Nullable String coopId,
            int residentSlot,
            @Nullable String roleId) {
        if (currentNpcUuid == null) {
            return null;
        }
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot direct = byNpc.remove(currentNpcUuid);
        if (direct != null) {
            return direct;
        }
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot match = null;
        for (CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot candidate : byNpc.values()) {
            if (!matchesCoopSlotRole(candidate, coopId, residentSlot, roleId)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        return removeMatched(match);
    }

    @Nullable
    CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot consumeLinks(
            @Nullable UUID currentNpcUuid,
            @Nullable UUID ownerId,
            @Nullable String[] toolIds,
            @Nullable String roleId) {
        if (currentNpcUuid == null) {
            return null;
        }
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot direct = byNpc.remove(currentNpcUuid);
        if (direct != null) {
            return direct;
        }
        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot match = null;
        for (CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot candidate : byNpc.values()) {
            if (candidate == null
                    || !LegacyCoopLedgerSupport.ownerMatches(candidate.ownerId(), ownerId)
                    || !LegacyCoopLedgerSupport.sharesAnyToolId(candidate.toolIds(), toolIds)
                    || !LegacyCoopLedgerSupport.roleMatches(candidate.roleId(), roleId)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        return removeMatched(match);
    }

    @Nonnull
    Collection<CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot> snapshots() {
        return byNpc.values();
    }

    private boolean matchesCoopSlotRole(
            @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot candidate,
            @Nullable String coopId,
            int residentSlot,
            @Nullable String roleId) {
        return candidate != null
                && LegacyCoopLedgerSupport.coopMatches(candidate.coopId(), coopId)
                && candidate.residentSlot() == residentSlot
                && LegacyCoopLedgerSupport.roleMatches(candidate.roleId(), roleId);
    }

    @Nullable
    private CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot removeMatched(
            @Nullable CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot match) {
        if (match == null || match.npcUuid() == null) {
            return null;
        }
        return byNpc.remove(match.npcUuid(), match) ? match : null;
    }
}
