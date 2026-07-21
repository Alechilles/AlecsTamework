package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.UUID;
import javax.annotation.Nullable;

/** Before/after canonical classification facts used to derive atomic group deltas. */
public record PopulationGroupTransition(@Nullable UUID oldOwnerUuid,
                                        @Nullable String oldRoleId,
                                        @Nullable String oldOwnershipWorldName,
                                        @Nullable CompanionLifecycleState oldLifecycle,
                                        @Nullable UUID newOwnerUuid,
                                        @Nullable String newRoleId,
                                        @Nullable String newOwnershipWorldName,
                                        @Nullable CompanionLifecycleState newLifecycle) {
}
