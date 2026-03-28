package com.alechilles.alecstamework.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface NpcProfilesApi {
    Optional<String> resolveProfileId(UUID npcUuid);

    Optional<NpcProfileView> getByProfileId(String profileId);

    Optional<NpcProfileView> getByNpcUuid(UUID npcUuid);

    Optional<String> getActiveSnapshot(String profileId, String snapshotType);

    Set<String> listActiveSnapshotTypes(String profileId);
}

