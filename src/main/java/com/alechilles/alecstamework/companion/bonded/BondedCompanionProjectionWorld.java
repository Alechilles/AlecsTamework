package com.alechilles.alecstamework.companion.bonded;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** World-thread boundary for exact bonded projection spawn and lookup. */
public interface BondedCompanionProjectionWorld {
    @Nonnull
    BondedCompanionProjectionService.SpawnResult spawn(
            @Nonnull BondedCompanionProjectionService.SpawnPlan plan);

    @Nullable
    BondedCompanionProjectionValidator.Projection readExact(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease);
}
