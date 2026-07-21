package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Post-commit notification for a canonical bonded-vessel state transition. */
public record BondedVesselStateChangedEvent(@Nonnull UUID operationId,
                                            @Nonnull UUID bindingId,
                                            @Nonnull String profileId,
                                            @Nonnull UUID ownerUuid,
                                            @Nonnull String vesselConfigId,
                                            long oldGeneration,
                                            long newGeneration,
                                            @Nonnull BondedVesselState oldState,
                                            @Nonnull BondedVesselState newState,
                                            long profileRevision,
                                            long cooldownUntilMs,
                                            @Nonnull String reason,
                                            boolean recovered,
                                            long changedAtMs,
                                            long emittedAtMs) implements TameworkEvent {
    public BondedVesselStateChangedEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        profileId = Objects.requireNonNull(profileId, "profileId").trim();
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        vesselConfigId = Objects.requireNonNull(vesselConfigId, "vesselConfigId").trim();
        oldState = Objects.requireNonNull(oldState, "oldState");
        newState = Objects.requireNonNull(newState, "newState");
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (profileId.isEmpty()) throw new IllegalArgumentException("profileId is required.");
        if (vesselConfigId.isEmpty()) throw new IllegalArgumentException("vesselConfigId is required.");
        if (reason.isEmpty()) throw new IllegalArgumentException("reason is required.");
        if (oldGeneration < 0L || newGeneration <= oldGeneration || profileRevision < 0L) {
            throw new IllegalArgumentException("Bonded-vessel transition revisions are invalid.");
        }
    }
}
