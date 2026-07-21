package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Creates public source evidence exclusively from a Tamework-owned exact held-slot snapshot. */
public final class BondedVesselHeldSlotEvidenceFactory {
    public static final String HOTBAR_CONTAINER_PATH = "hotbar";
    private final BondedVesselItemFingerprintCodec fingerprints;

    public BondedVesselHeldSlotEvidenceFactory(@Nonnull BondedVesselItemFingerprintCodec fingerprints) {
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
    }

    @Nonnull
    public BondedVesselSourceItemEvidence create(
            @Nonnull UUID actorUuid,
            int slot,
            long monotonicInventoryRevision,
            @Nonnull BondedVesselItemFingerprintCodec.VesselItemMetadata metadata
    ) {
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(metadata, "metadata");
        if (slot < 0) throw new IllegalArgumentException("slot cannot be negative");
        if (monotonicInventoryRevision < 0L) {
            throw new IllegalArgumentException("monotonicInventoryRevision cannot be negative");
        }
        return new BondedVesselSourceItemEvidence(
                metadata.itemId(), holderEvidenceId(actorUuid), HOTBAR_CONTAINER_PATH,
                slot, monotonicInventoryRevision, fingerprints.fingerprint(metadata));
    }

    @Nonnull
    public static String holderEvidenceId(@Nonnull UUID actorUuid) {
        return "player:" + Objects.requireNonNull(actorUuid, "actorUuid")
                .toString().toLowerCase();
    }
}
