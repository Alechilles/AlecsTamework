package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Process-local projection tombstones hydrated from durable roster snapshots at startup. */
public final class CommandTimedProjectionRetirementIndex {
    private static final ConcurrentHashMap<UUID, Retirement> RETIRED = new ConcurrentHashMap<>();

    private CommandTimedProjectionRetirementIndex() {
    }

    public static void retire(@Nonnull UUID npcUuid, @Nonnull String profileId,
                              @Nonnull String commandFamilyId, @Nonnull String operationId) {
        RETIRED.put(npcUuid, new Retirement(profileId, commandFamilyId, operationId));
    }

    @Nullable
    public static Retirement find(@Nullable UUID npcUuid) {
        return npcUuid == null ? null : RETIRED.get(npcUuid);
    }

    public static void observedRetired(@Nullable UUID npcUuid) {
        if (npcUuid != null) RETIRED.remove(npcUuid);
    }

    public static void clear() {
        RETIRED.clear();
    }

    public record Retirement(@Nonnull String profileId,
                             @Nonnull String commandFamilyId,
                             @Nonnull String operationId) {
    }
}
