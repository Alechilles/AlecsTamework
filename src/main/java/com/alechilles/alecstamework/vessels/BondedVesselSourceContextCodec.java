package com.alechilles.alecstamework.vessels;

import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable schema-v8 source evidence snapshot used for restart comparison and recovery. */
final class BondedVesselSourceContextCodec {
    private static final int VERSION = 1;
    private final Gson gson = new Gson();

    @Nonnull
    String encode(@Nonnull BondedVesselTransitionContext context) {
        Objects.requireNonNull(context, "context");
        PopulationAdmissionLocation destination = context.destination();
        return gson.toJson(new SourceContextDocument(
                VERSION,
                context.sourceItemId(),
                context.sourceHolderEvidenceId(),
                context.sourceContainerPath(),
                context.sourceInventorySlot(),
                context.sourceInventoryRevision(),
                context.sourceItemFingerprint(),
                context.expectedNpcUuid() == null ? null : context.expectedNpcUuid().toString(),
                destination == null ? null : destination.worldName(),
                destination == null ? null : destination.chunkX(),
                destination == null ? null : destination.chunkZ()
        ));
    }

    @Nonnull
    BondedVesselTransitionContext decode(@Nonnull String encoded) {
        try {
            SourceContextDocument document = gson.fromJson(
                    Objects.requireNonNull(encoded, "encoded"), SourceContextDocument.class);
            if (document == null || document.version() != VERSION) {
                throw new IllegalArgumentException("unsupported-source-context-version");
            }
            UUID expectedNpcUuid = document.expectedNpcUuid() == null
                    ? null : UUID.fromString(document.expectedNpcUuid());
            PopulationAdmissionLocation destination = null;
            if (document.destinationWorld() != null) {
                if (document.destinationChunkX() == null || document.destinationChunkZ() == null) {
                    throw new IllegalArgumentException("incomplete-source-destination");
                }
                destination = new PopulationAdmissionLocation(
                        document.destinationWorld(),
                        document.destinationChunkX(),
                        document.destinationChunkZ());
            } else if (document.destinationChunkX() != null || document.destinationChunkZ() != null) {
                throw new IllegalArgumentException("incomplete-source-destination");
            }
            return new BondedVesselTransitionContext(
                    document.sourceItemId(),
                    document.sourceHolderEvidenceId(),
                    document.sourceContainerPath(),
                    document.sourceInventorySlot(),
                    document.sourceInventoryRevision(),
                    document.sourceItemFingerprint(),
                    expectedNpcUuid,
                    destination
            );
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid-bonded-vessel-source-context", exception);
        }
    }

    private record SourceContextDocument(int version,
                                         @Nonnull String sourceItemId,
                                         @Nonnull String sourceHolderEvidenceId,
                                         @Nonnull String sourceContainerPath,
                                         int sourceInventorySlot,
                                         long sourceInventoryRevision,
                                         @Nonnull String sourceItemFingerprint,
                                         @Nullable String expectedNpcUuid,
                                         @Nullable String destinationWorld,
                                         @Nullable Integer destinationChunkX,
                                         @Nullable Integer destinationChunkZ) {
    }
}
