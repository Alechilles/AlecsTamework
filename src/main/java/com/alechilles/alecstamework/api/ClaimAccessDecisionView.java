package com.alechilles.alecstamework.api;

import java.util.UUID;
import javax.annotation.Nullable;

public record ClaimAccessDecisionView(boolean available,
                                      boolean allowed,
                                      @Nullable Status status,
                                      @Nullable String reason,
                                      @Nullable UUID claimPartyId,
                                      @Nullable Integer claimChunkCount,
                                      @Nullable String worldName,
                                      @Nullable Vector3View position,
                                      @Nullable String positionSource) {
    public enum Status {
        ALLOWED,
        DENIED,
        ALLOW_FAIL_OPEN,
        SKIPPED,
        UNAVAILABLE
    }
}
