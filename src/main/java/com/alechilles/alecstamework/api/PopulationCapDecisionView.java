package com.alechilles.alecstamework.api;

import java.util.UUID;
import javax.annotation.Nullable;

public record PopulationCapDecisionView(@Nullable UUID ownerUuid,
                                        boolean allowed,
                                        boolean capEnabled,
                                        int limit,
                                        int currentCount,
                                        int remainingHeadroom,
                                        @Nullable String scope,
                                        @Nullable String reason) {
}
