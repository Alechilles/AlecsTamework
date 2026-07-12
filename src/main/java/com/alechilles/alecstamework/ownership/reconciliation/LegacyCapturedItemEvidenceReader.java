package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
        UUID effectiveOwner = currentOwner != null ? currentOwner : conservativeLegacyOwner(stack, sourceOwner);
        return Optional.of(new CompanionPopulationEvidence(
                evidenceKey,
                npcUuid,
                effectiveOwner,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                null,
                null,
                null,
                null,
                source
        ));
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

    @Nullable
    private UUID conservativeLegacyOwner(@Nonnull ItemStack stack, @Nullable UUID sourceOwner) {
        if (sourceOwner == null) {
            return null;
        }
        ItemFeatureConfig config = itemFeatures != null ? itemFeatures.get(stack.getItemId()) : null;
        if (config != null && config.isCaptureClearsOwner()) {
            return null;
        }
        // An unknown legacy config cannot safely prove that the source owner was intentionally cleared.
        return sourceOwner;
    }
}
