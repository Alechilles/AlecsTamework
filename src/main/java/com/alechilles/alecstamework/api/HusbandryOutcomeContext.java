package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;

/** Immutable action context supplied to a husbandry outcome provider. */
public record HusbandryOutcomeContext(
        HusbandryOutcomeKind kind,
        UUID ownerId,
        UUID actorId,
        UUID companionId,
        String roleId,
        String profileId,
        Set<String> groupIds,
        String productId,
        HusbandryToolContext tool
) {
    /** Compatibility constructor for providers compiled before tool context existed. */
    public HusbandryOutcomeContext(HusbandryOutcomeKind kind,
                                  UUID ownerId,
                                  UUID companionId,
                                  String roleId,
                                  String profileId,
                                  Set<String> groupIds,
                                  String productId) {
        this(kind, ownerId, null, companionId, roleId, profileId, groupIds, productId, null);
    }
}
