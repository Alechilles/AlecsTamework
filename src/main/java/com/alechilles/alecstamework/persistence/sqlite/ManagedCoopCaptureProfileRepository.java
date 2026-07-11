package com.alechilles.alecstamework.persistence.sqlite;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Ensures the canonical NPC profile required by a durable managed-coop capture claim. */
public final class ManagedCoopCaptureProfileRepository {
    public record ProfileSeed(@Nonnull UUID sourceNpcUuid,
                              @Nullable UUID ownerUuid,
                              @Nullable String roleId,
                              @Nullable String displayName,
                              @Nullable String[] toolIds) {
        public ProfileSeed {
            if (sourceNpcUuid == null) {
                throw new IllegalArgumentException("sourceNpcUuid is required");
            }
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }
    }

    public record ProfileIdentity(@Nonnull String profileId, @Nonnull UUID sourceNpcUuid) {
    }

    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profiles;

    public ManagedCoopCaptureProfileRepository(@Nonnull PersistenceWriteQueue writeQueue,
                                               @Nonnull NpcProfileRepository profiles) {
        this.writeQueue = writeQueue;
        this.profiles = profiles;
    }

    /** Upserts capture metadata and returns the committed stable profile identity. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProfileIdentity> ensureProfile(
            @Nonnull ProfileSeed seed) {
        return writeQueue.submitTracked(
                "managed_coop_capture_profile_ensure",
                connection -> {
                    profiles.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                            seed.sourceNpcUuid(),
                            seed.ownerUuid(),
                            null,
                            seed.roleId(),
                            seed.displayName(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            seed.toolIds()
                    ));
                    String profileId = profiles.resolveProfileIdInTransaction(connection, seed.sourceNpcUuid());
                    if (profileId == null || profileId.isBlank()) {
                        throw new IllegalStateException("managed_coop_capture_profile_missing_after_upsert");
                    }
                    return new ProfileIdentity(profileId, seed.sourceNpcUuid());
                },
                null
        );
    }
}
