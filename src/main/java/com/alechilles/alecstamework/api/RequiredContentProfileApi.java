package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Public readiness view for required managed-content profiles. */
public interface RequiredContentProfileApi {
    @Nonnull
    RequiredContentProfileStatus status(@Nonnull String profileId);

    @Nonnull
    static RequiredContentProfileApi unavailable() {
        return profileId -> RequiredContentProfileStatus.unavailable(
                profileId == null || profileId.isBlank() ? "unavailable" : profileId,
                "required-content-profile-authority-unavailable"
        );
    }
}
