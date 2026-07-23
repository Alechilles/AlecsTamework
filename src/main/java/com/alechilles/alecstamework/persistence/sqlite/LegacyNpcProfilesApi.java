package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Temporary unsupported-development adapter for the pre-cutover profile API. */
public final class LegacyNpcProfilesApi implements NpcProfilesApi {
    private final NpcProfileRepository profiles;

    public LegacyNpcProfilesApi(@Nonnull NpcProfileRepository profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException(
                    "Legacy profile repository is required"
            );
        }
        this.profiles = profiles;
    }

    @Override
    public Optional<String> resolveProfileId(UUID npcUuid) {
        return npcUuid == null
                ? Optional.empty()
                : Optional.ofNullable(profiles.resolveProfileId(npcUuid));
    }

    @Override
    public Optional<NpcProfileView> getByProfileId(String profileId) {
        return profileId == null || profileId.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(
                profiles.loadProfileById(profileId.trim())
        ).map(LegacyNpcProfilesApi::mapProfile);
    }

    @Override
    public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
        return npcUuid == null
                ? Optional.empty()
                : Optional.ofNullable(
                profiles.loadProfileByNpcUuid(npcUuid)
        ).map(LegacyNpcProfilesApi::mapProfile);
    }

    @Override
    public Optional<String> getActiveSnapshot(
            String profileId,
            String snapshotType
    ) {
        return profileId == null || profileId.isBlank()
                || snapshotType == null || snapshotType.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(profiles.loadActiveSnapshotPayload(
                profileId.trim(), snapshotType.trim()
        ));
    }

    @Override
    public Set<String> listActiveSnapshotTypes(String profileId) {
        return profileId == null || profileId.isBlank()
                ? Set.of()
                : Set.copyOf(profiles.listActiveSnapshotTypes(
                profileId.trim()
        ));
    }

    public static NpcProfileView mapProfile(
            NpcProfileRepository.ProfileRecord profile
    ) {
        return new NpcProfileView(
                profile.profileId(),
                profile.currentNpcUuid(),
                profile.ownerUuid(),
                profile.ownerName(),
                profile.roleId(),
                profile.displayName(),
                profile.customName(),
                Boolean.TRUE.equals(profile.tamed()),
                profile.coopId(),
                profile.coopSlot(),
                ordered(profile.toolIds()),
                ordered(profile.activeSnapshotTypes()),
                profile.updatedAtMs()
        );
    }

    private static Set<String> ordered(String[] values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
