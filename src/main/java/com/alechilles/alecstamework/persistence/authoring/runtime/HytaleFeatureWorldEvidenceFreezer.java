package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.persistence.authoring.ReplacementFeatureLiveEvidenceSource;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Composes the focused Hytale readers used by the four restored feature authors. */
final class HytaleFeatureWorldEvidenceFreezer
        implements FeatureWorldEvidenceFreezer {
    private final HytaleFeatureInventoryEvidenceReader inventories;
    private final HytaleTimedWorldEvidenceReader timed;
    private final HytaleProvisioningWorldEvidenceReader provisioning;
    private final LongSupplier clock;

    HytaleFeatureWorldEvidenceFreezer(
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull com.alechilles.alecstamework.companion.snapshot
                    .SnapshotCodecRegistry snapshotCodecs,
            @Nonnull com.alechilles.alecstamework.items
                    .CoopResidentStateSnapshotService snapshots,
            @Nonnull LongSupplier clock
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        inventories = new HytaleFeatureInventoryEvidenceReader(
                Objects.requireNonNull(commandItems, "commandItems")
        );
        timed = new HytaleTimedWorldEvidenceReader(
                Objects.requireNonNull(snapshotCodecs, "snapshotCodecs"),
                Objects.requireNonNull(snapshots, "snapshots"),
                clock
        );
        provisioning = new HytaleProvisioningWorldEvidenceReader(
                snapshotCodecs, clock
        );
    }

    @Override
    @Nullable
    public ReplacementFeatureLiveEvidenceSource.RosterAccess freezeRoster(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.RosterAccessIntent intent
    ) {
        return inventories.freezeRoster(
                access, intent, clock.getAsLong()
        );
    }

    @Override
    @Nullable
    public ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence
    freezeTimed(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent
    ) {
        return timed.freeze(access, intent);
    }

    @Override
    @Nullable
    public ReplacementFeatureLiveEvidenceSource.ProvisioningWorldEvidence
    freezeProvisioning(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldIntent intent
    ) {
        return provisioning.freeze(access, intent);
    }

    @Override
    @Nullable
    public ReplacementFeatureLiveEvidenceSource.PaidInventoryEvidence
    freezePaid(
            @Nonnull HytaleOwnerWorldAccess access,
            @Nonnull ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent intent
    ) {
        return inventories.freezePaid(
                access, intent, clock.getAsLong()
        );
    }
}
