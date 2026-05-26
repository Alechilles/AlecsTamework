package com.alechilles.alecstamework.selftest;

import org.joml.Vector3d;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ApiSelfTestFixtureRecord(@Nonnull String fixtureKey,
                                       @Nonnull UUID npcUuid,
                                       @Nullable UUID ownerUuid,
                                       @Nullable String ownerName,
                                       @Nonnull String roleId,
                                       @Nullable String displayName,
                                       @Nullable Vector3d homePosition,
                                       @Nullable Vector3d lastKnownPosition) {
}
