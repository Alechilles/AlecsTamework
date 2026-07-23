package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Canonical immutable provisioning-provenance boundary. */
public interface ProvisioningPort {
    @Nonnull
    Optional<ProvisioningRecord> findByProfile(
            @Nonnull ProfileId profileId
    );

    @Nonnull
    Optional<ProvisioningRecord> findByOrigin(
            @Nonnull ProvisioningOrigin origin
    );

    @Nonnull
    List<ProvisioningRecord> findAll();

    @Nonnull
    PersistenceMutationResult<ProvisioningRecord> create(
            @Nonnull ProvisioningRecord record
    );
}
