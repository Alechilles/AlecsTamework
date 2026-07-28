package com.alechilles.alecstamework.persistence.migration;

import javax.annotation.Nonnull;

/** Coordinates deterministic identity and lifecycle planning without performing I/O. */
final class PublicImportPlanner {
    private final PublicImportIdentityPlanner identityPlanner = new PublicImportIdentityPlanner();
    private final PublicImportLifecyclePlanner lifecyclePlanner =
            new PublicImportLifecyclePlanner();

    @Nonnull
    PublicImportPlan plan(
            @Nonnull LegacyPublicData source,
            @Nonnull LegacySourceFingerprint fingerprint,
            long importedAtMs
    ) throws Exception {
        if (source == null || fingerprint == null) {
            throw new IllegalArgumentException("Source rows and fingerprint are required");
        }
        PublicImportPlanningModel.Identity identity = identityPlanner.plan(source);
        PublicImportPlanningModel.Lifecycle lifecycle =
                lifecyclePlanner.plan(source, identity, fingerprint, importedAtMs);
        return new PublicImportPlan(
                identity.profiles().values().stream()
                        .map(PublicImportPlanningModel.ProfileDraft::target)
                        .toList(),
                identity.aliases(),
                identity.toolLinks(),
                lifecycle.snapshots(),
                identity.extensions(),
                lifecycle.coopSlots(),
                lifecycle.coopResidencies(),
                lifecycle.lifecycles(),
                lifecycle.incidents()
        );
    }
}
