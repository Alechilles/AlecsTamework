package com.alechilles.alecstamework.api;

import java.util.UUID;
import javax.annotation.Nullable;

public record OwnershipPolicyView(String profileId,
                                  @Nullable UUID currentNpcUuid,
                                  @Nullable UUID ownerUuid,
                                  @Nullable String ownerName,
                                  @Nullable String roleId,
                                  boolean tamed,
                                  boolean inCoop,
                                  @Nullable String coopId,
                                  @Nullable Integer coopSlot,
                                  boolean blockOwnerDamage,
                                  boolean blockAllPlayerDamageIfOwned,
                                  boolean invulnerableIfOwned) {
}
