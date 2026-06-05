package com.alechilles.alecstamework.items;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Holds short-lived respawn traces so immediate damage or death callbacks can be correlated with a replacement NPC.
 */
public final class RecentRespawnTraceService {
    private static final long DEFAULT_TRACE_MAX_AGE_MS = 10_000L;
    private static final RecentRespawnTraceService INSTANCE =
            new RecentRespawnTraceService(DEFAULT_TRACE_MAX_AGE_MS);

    private final ConcurrentHashMap<UUID, Trace> traceByReplacementNpc = new ConcurrentHashMap<>();
    private final long traceMaxAgeMs;

    RecentRespawnTraceService(long traceMaxAgeMs) {
        this.traceMaxAgeMs = Math.max(1L, traceMaxAgeMs);
    }

    @Nonnull
    public static RecentRespawnTraceService getInstance() {
        return INSTANCE;
    }

    @Nonnull
    public Trace startTrace(@Nonnull String branch,
                            @Nullable UUID originalNpcUuid,
                            @Nullable UUID ownerUuid,
                            @Nullable String roleId,
                            @Nullable String toolId,
                            long nowMs) {
        maybeCleanup(nowMs);
        return new Trace(
                UUID.randomUUID().toString(),
                clean(branch),
                originalNpcUuid,
                null,
                ownerUuid,
                clean(roleId),
                clean(toolId),
                nowMs,
                0L,
                null
        );
    }

    @Nullable
    public Trace recordReplacementNpc(@Nullable Trace trace,
                                      @Nullable UUID replacementNpcUuid,
                                      long nowMs) {
        if (trace == null || replacementNpcUuid == null) {
            return trace;
        }
        maybeCleanup(nowMs);
        Trace updated = trace.withReplacement(replacementNpcUuid, nowMs);
        traceByReplacementNpc.put(replacementNpcUuid, updated);
        return updated;
    }

    @Nullable
    public Trace getRecentTrace(@Nullable UUID replacementNpcUuid, long nowMs) {
        if (replacementNpcUuid == null) {
            return null;
        }
        Trace trace = traceByReplacementNpc.get(replacementNpcUuid);
        if (trace == null) {
            return null;
        }
        if (isExpired(trace, nowMs)) {
            traceByReplacementNpc.remove(replacementNpcUuid, trace);
            return null;
        }
        return trace;
    }

    public boolean recordFirstDamage(@Nullable UUID victimNpcUuid,
                                     @Nullable DamageEvent event,
                                     long nowMs) {
        if (victimNpcUuid == null || event == null) {
            return false;
        }
        Trace existing = getRecentTrace(victimNpcUuid, nowMs);
        if (existing == null || existing.firstDamage() != null) {
            return false;
        }
        Trace updated = existing.withFirstDamage(event);
        return traceByReplacementNpc.replace(victimNpcUuid, existing, updated);
    }

    public void clear(@Nullable UUID replacementNpcUuid) {
        if (replacementNpcUuid != null) {
            traceByReplacementNpc.remove(replacementNpcUuid);
        }
    }

    @Nonnull
    public String describe(@Nonnull Trace trace, long nowMs) {
        StringBuilder builder = new StringBuilder(256)
                .append("traceId=").append(trace.traceId())
                .append(" branch=").append(trace.branch())
                .append(" ageMs=").append(nowMs - trace.startedAtMs())
                .append(" original=").append(trace.originalNpcUuid())
                .append(" replacement=").append(trace.replacementNpcUuid())
                .append(" owner=").append(trace.ownerUuid())
                .append(" role=").append(trace.roleId())
                .append(" tool=").append(trace.toolId());
        if (trace.firstDamage() != null) {
            builder.append(" firstDamage=").append(trace.firstDamage().describe());
        }
        return builder.toString();
    }

    private void maybeCleanup(long nowMs) {
        Iterator<Map.Entry<UUID, Trace>> iterator = traceByReplacementNpc.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Trace> entry = iterator.next();
            if (isExpired(entry.getValue(), nowMs)) {
                iterator.remove();
            }
        }
    }

    private boolean isExpired(@Nonnull Trace trace, long nowMs) {
        return nowMs - trace.startedAtMs() > traceMaxAgeMs;
    }

    @Nonnull
    private static String clean(@Nullable String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }

    public record DamageEvent(@Nonnull String attacker,
                              @Nonnull String cause,
                              @Nonnull String amount,
                              @Nonnull String health) {
        @Nonnull
        public String describe() {
            return "attacker=" + attacker
                    + " cause=" + cause
                    + " amount=" + amount
                    + " health=" + health;
        }
    }

    public record Trace(@Nonnull String traceId,
                        @Nonnull String branch,
                        @Nullable UUID originalNpcUuid,
                        @Nullable UUID replacementNpcUuid,
                        @Nullable UUID ownerUuid,
                        @Nonnull String roleId,
                        @Nonnull String toolId,
                        long startedAtMs,
                        long replacementRecordedAtMs,
                        @Nullable DamageEvent firstDamage) {
        @Nonnull
        Trace withReplacement(@Nonnull UUID replacementNpcUuid, long nowMs) {
            return new Trace(
                    traceId,
                    branch,
                    originalNpcUuid,
                    replacementNpcUuid,
                    ownerUuid,
                    roleId,
                    toolId,
                    startedAtMs,
                    nowMs,
                    firstDamage
            );
        }

        @Nonnull
        Trace withFirstDamage(@Nonnull DamageEvent damageEvent) {
            return new Trace(
                    traceId,
                    branch,
                    originalNpcUuid,
                    replacementNpcUuid,
                    ownerUuid,
                    roleId,
                    toolId,
                    startedAtMs,
                    replacementRecordedAtMs,
                    damageEvent
            );
        }
    }
}
