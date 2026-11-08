package com.alechilles.alecstamework.companioncard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Card button requests waiting for the companion's next role tick.
 *
 * <p>A card button queues a request here and then registers an interaction on the companion, as F does. The role
 * tick's {@code TameworkInteract} takes the request for that companion and viewer, checks it against the current
 * config and requirements, and runs it. Requests carry only stable IDs and indexes. Each companion and viewer pair
 * keeps at most one request, a request expires after {@link #TTL_MS}, the map is bounded, and plugin shutdown clears
 * it.
 */
public final class CompanionCardRequests {
    /** How long a request waits for the role tick before it is dropped. */
    static final long TTL_MS = 2_000L;
    private static final int MAX_PENDING = 1_024;
    private static final Map<Key, Pending> PENDING = new ConcurrentHashMap<>();

    private CompanionCardRequests() {
    }

    /** What a card button asks the role tick to do. */
    public sealed interface Request permits RunEntry, SetMode, Refresh {
    }

    /**
     * Runs one interaction entry by its index in the config's {@code Interactions} list. Dropped when the companion
     * now resolves a different config (a role change or reload), so an index never lands on another entry.
     */
    public record RunEntry(@Nullable String configId, int entryIndex) implements Request {
    }

    /** Applies one {@code ModeCycle} step by its index in the {@code Cycle} list of config {@code configId}. */
    public record SetMode(@Nullable String configId, int stepIndex) implements Request {
    }

    /** Recomputes the card's interaction snapshot without running anything. */
    public record Refresh() implements Request {
    }

    /** Queues a request, replacing an older one for the same companion and viewer. */
    public static void queue(@Nonnull UUID npcUuid, @Nonnull UUID playerUuid, @Nonnull Request request) {
        long nowMs = System.currentTimeMillis();
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.values().removeIf(pending -> pending.expiresAtMs <= nowMs);
            if (PENDING.size() >= MAX_PENDING) {
                return;
            }
        }
        PENDING.put(new Key(npcUuid, playerUuid), new Pending(request, nowMs + TTL_MS));
    }

    /** Removes and returns the pending request for this companion and viewer, or {@code null} if none is live. */
    @Nullable
    public static Request take(@Nullable UUID npcUuid, @Nullable UUID playerUuid) {
        if (npcUuid == null || playerUuid == null || PENDING.isEmpty()) {
            return null;
        }
        Pending pending = PENDING.remove(new Key(npcUuid, playerUuid));
        return pending == null || pending.expiresAtMs <= System.currentTimeMillis() ? null : pending.request;
    }

    /** Drops every pending request. Called on plugin shutdown. */
    public static void clear() {
        PENDING.clear();
    }

    private record Key(UUID npcUuid, UUID playerUuid) {
    }

    private record Pending(Request request, long expiresAtMs) {
    }
}
