package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads the stable legacy identity and owner fields carried by filled Tamework spawners.
 */
public final class LegacyCapturedItemEvidenceReader {
    @Nullable
    private final ItemFeatureRegistry itemFeatures;
    private final BondedVesselInventoryEvidence vesselItems =
            new BondedVesselInventoryEvidence();

    public LegacyCapturedItemEvidenceReader(@Nullable ItemFeatureRegistry itemFeatures) {
        this.itemFeatures = itemFeatures;
    }

    @Nonnull
    public Optional<CompanionPopulationEvidence> read(@Nullable ItemStack stack,
                                                       @Nonnull String evidenceKey,
                                                       @Nonnull String source) {
        if (stack == null || ItemStack.isEmpty(stack)) {
            return Optional.empty();
        }
        UUID npcUuid = stack.getFromMetadataOrNull(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING);
        if (npcUuid == null || !isFilledSpawner(stack)) {
            return Optional.empty();
        }
        UUID currentOwner = stack.getFromMetadataOrNull(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        UUID sourceOwner = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID,
                Codec.UUID_STRING
        );
        OwnerEvidence owner = resolveOwnerEvidence(stack, currentOwner, sourceOwner);
        return Optional.of(new CompanionPopulationEvidence(
                evidenceKey,
                npcUuid,
                owner.ownerUuid(),
                owner.kind(),
                null,
                null,
                null,
                null,
                source
        ));
    }

    /** Reads every independent projection carried by one stack. */
    @Nonnull
    public List<CompanionPopulationEvidence> readAll(
            @Nullable ItemStack stack,
            @Nonnull String evidenceKey,
            @Nonnull String source
    ) {
        List<CompanionPopulationEvidence> evidence = new ArrayList<>(2);
        read(stack, evidenceKey, source).ifPresent(evidence::add);
        vesselItems.read(stack, evidenceKey, source).ifPresent(evidence::add);
        return List.copyOf(evidence);
    }

    @Nonnull
    private OwnerEvidence resolveOwnerEvidence(@Nonnull ItemStack stack,
                                                @Nullable UUID currentOwner,
                                                @Nullable UUID sourceOwner) {
        if (currentOwner != null) {
            return OwnerEvidence.authoritative(currentOwner);
        }
        Boolean ownerCleared = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_OWNER_CLEARED,
                Codec.BOOLEAN
        );
        if (ownerCleared != null) {
            return OwnerEvidence.authoritative(Boolean.TRUE.equals(ownerCleared) ? null : sourceOwner);
        }
        ItemFeatureConfig config = itemFeatures != null ? itemFeatures.get(stack.getItemId()) : null;
        if (config != null) {
            return OwnerEvidence.authoritative(config.isCaptureClearsOwner() ? null : sourceOwner);
        }
        if (sourceOwner != null) {
            return new OwnerEvidence(
                    sourceOwner,
                    CompanionPopulationEvidence.Kind.CAPTURED_ITEM_LEGACY_OWNER_HINT
            );
        }
        return OwnerEvidence.authoritative(null);
    }

    private boolean isFilledSpawner(@Nonnull ItemStack stack) {
        Boolean captured = stack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN);
        if (Boolean.TRUE.equals(captured)) {
            return true;
        }
        String itemId = stack.getItemId();
        if (itemId == null || !itemId.contains("_State_")) {
            return false;
        }
        ItemFeatureConfig config = itemFeatures != null ? itemFeatures.get(itemId) : null;
        return config == null || config.isSpawnerEnabled();
    }

    private record OwnerEvidence(
            @Nullable UUID ownerUuid,
            @Nonnull CompanionPopulationEvidence.Kind kind
    ) {
        @Nonnull
        private static OwnerEvidence authoritative(@Nullable UUID ownerUuid) {
            return new OwnerEvidence(ownerUuid, CompanionPopulationEvidence.Kind.CAPTURED_ITEM);
        }
    }
}
