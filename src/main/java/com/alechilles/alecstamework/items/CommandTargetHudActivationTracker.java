package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches whether each player may currently be holding a command item for the inspector HUD.
 * Event systems and the HUD tick service may touch this tracker from different runtime paths, so
 * access is synchronized around the shared hand-state map and candidate set.
 */
public final class CommandTargetHudActivationTracker {
    private static final long INACTIVE_SANITY_SCAN_INTERVAL_MS = 1_000L;

    private final Map<UUID, HandState> statesByPlayer = new HashMap<>();
    private final LinkedHashSet<UUID> candidatePlayers = new LinkedHashSet<>();

    synchronized boolean shouldInspectPlayer(@Nullable UUID playerUuid, long nowMs) {
        if (playerUuid == null) {
            return false;
        }
        HandState state = statesByPlayer.get(playerUuid);
        if (state == null) {
            return true;
        }
        return shouldInspectForTests(
                state.dirty(),
                state.commandItem(),
                state.lastResolvedMs(),
                nowMs,
                INACTIVE_SANITY_SCAN_INTERVAL_MS
        );
    }

    @Nullable
    synchronized String cachedCommandItemId(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        HandState state = statesByPlayer.get(playerUuid);
        return state != null && state.commandItem() ? state.activeItemId() : null;
    }

    synchronized boolean isDirty(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        HandState state = statesByPlayer.get(playerUuid);
        return state == null || state.dirty();
    }

    synchronized void markDirty(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        candidatePlayers.add(playerUuid);
        HandState previous = statesByPlayer.get(playerUuid);
        statesByPlayer.put(playerUuid, new HandState(
                previous != null ? previous.activeItemId() : null,
                previous != null && previous.commandItem(),
                true,
                previous != null ? previous.lastResolvedMs() : 0L
        ));
    }

    synchronized void recordResolvedHand(@Nullable UUID playerUuid,
                                         @Nullable String activeItemId,
                                         boolean commandItem,
                                         long nowMs) {
        if (playerUuid == null) {
            return;
        }
        if (commandItem) {
            candidatePlayers.add(playerUuid);
        } else {
            candidatePlayers.remove(playerUuid);
        }
        statesByPlayer.put(playerUuid, new HandState(activeItemId, commandItem, false, nowMs));
    }

    synchronized void remove(@Nullable UUID playerUuid) {
        if (playerUuid != null) {
            candidatePlayers.remove(playerUuid);
            statesByPlayer.remove(playerUuid);
        }
    }

    synchronized List<UUID> candidatePlayerUuids() {
        return List.copyOf(candidatePlayers);
    }

    synchronized CandidateBatch selectCandidateBatch(int maxCandidates,
                                                     @Nullable UUID dirtyCursor,
                                                     @Nullable UUID regularCursor) {
        if (candidatePlayers.isEmpty() || maxCandidates <= 0) {
            return CandidateBatch.EMPTY;
        }
        int limit = Math.min(maxCandidates, candidatePlayers.size());
        ArrayList<UUID> selected = new ArrayList<>(limit);
        UUID nextDirtyCursor = selectCandidates(
                selected,
                limit,
                true,
                dirtyCursor
        );
        UUID nextRegularCursor = selectCandidates(
                selected,
                limit,
                false,
                regularCursor
        );
        return new CandidateBatch(List.copyOf(selected), nextDirtyCursor, nextRegularCursor);
    }

    @Nullable
    private UUID selectCandidates(ArrayList<UUID> selected,
                                  int limit,
                                  boolean dirty,
                                  @Nullable UUID cursor) {
        if (selected.size() >= limit) {
            return cursor;
        }
        boolean cursorPresent = cursor != null && candidatePlayers.contains(cursor);
        boolean pastCursor = !cursorPresent;
        UUID nextCursor = cursor;
        for (UUID playerUuid : candidatePlayers) {
            if (!pastCursor) {
                if (playerUuid.equals(cursor)) {
                    pastCursor = true;
                }
                continue;
            }
            if (isDirtyEntry(playerUuid) == dirty) {
                selected.add(playerUuid);
                nextCursor = playerUuid;
                if (selected.size() >= limit) {
                    return nextCursor;
                }
            }
        }
        if (cursorPresent) {
            for (UUID playerUuid : candidatePlayers) {
                if (isDirtyEntry(playerUuid) == dirty) {
                    selected.add(playerUuid);
                    nextCursor = playerUuid;
                    if (selected.size() >= limit) {
                        return nextCursor;
                    }
                }
                if (playerUuid.equals(cursor)) {
                    break;
                }
            }
        }
        return nextCursor;
    }

    private boolean isDirtyEntry(@Nonnull UUID playerUuid) {
        HandState state = statesByPlayer.get(playerUuid);
        return state == null || state.dirty();
    }

    static boolean shouldInspectForTests(boolean dirty,
                                         boolean commandItem,
                                         long lastResolvedMs,
                                         long nowMs,
                                         long inactiveSanityScanIntervalMs) {
        if (dirty || commandItem) {
            return true;
        }
        return nowMs - lastResolvedMs >= Math.max(0L, inactiveSanityScanIntervalMs);
    }

    private record HandState(@Nullable String activeItemId,
                             boolean commandItem,
                             boolean dirty,
                             long lastResolvedMs) {
        private HandState {
            if (!commandItem) {
                activeItemId = null;
            }
        }
    }

    record CandidateBatch(@Nonnull List<UUID> playerUuids,
                          @Nullable UUID nextDirtyCursor,
                          @Nullable UUID nextRegularCursor) {
        private static final CandidateBatch EMPTY = new CandidateBatch(List.of(), null, null);
    }
}
