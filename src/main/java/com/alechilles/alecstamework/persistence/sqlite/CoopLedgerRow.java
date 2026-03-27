package com.alechilles.alecstamework.persistence.sqlite;

import java.util.UUID;
import javax.annotation.Nullable;

public record CoopLedgerRow(String slotKey,
                            @Nullable String worldName,
                            @Nullable String coopId,
                            int x,
                            int y,
                            int z,
                            int residentSlot,
                            @Nullable UUID housedNpcUuid,
                            @Nullable UUID lastReleasedNpcUuid,
                            @Nullable UUID ownerId,
                            String[] toolIds,
                            @Nullable String roleId,
                            @Nullable String displayName,
                            long housedAtMs,
                            long releasedAtMs,
                            @Nullable String stateSnapshotJson) {
}
