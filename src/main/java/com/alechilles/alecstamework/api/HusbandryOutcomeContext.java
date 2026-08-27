package com.alechilles.alecstamework.api;

import java.util.Set;
import java.util.UUID;

/** Immutable action context supplied to a husbandry outcome provider. */
public record HusbandryOutcomeContext(
        HusbandryOutcomeKind kind,
        UUID ownerId,
        UUID companionId,
        String roleId,
        String profileId,
        Set<String> groupIds,
        String productId
) {
}
