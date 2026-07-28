package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.companion.snapshot
        .CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.items.CommandCompanionPlacementService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeatureLiveEvidenceSource;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds source-neutral initial provisioning state from one exact owner world. */
final class HytaleProvisioningWorldEvidenceReader {
    private final SnapshotCodecRegistry snapshotCodecs;
    private final CommandCompanionPlacementService placements =
            new CommandCompanionPlacementService();
    private final LongSupplier clock;

    HytaleProvisioningWorldEvidenceReader(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull LongSupplier clock
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nullable
    ReplacementFeatureLiveEvidenceSource.ProvisioningWorldEvidence freeze(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.ProvisioningWorldIntent intent
    ) {
        access.store().assertThread();
        if (!access.ownerUuid().equals(intent.ownerUuid())
                || !access.worldKey().equals(intent.ownerWorldKey())
                || !availableRole(intent.roleId())) {
            return null;
        }
        long observedAtMs = clock.getAsLong();
        String ownerName = OwnerNameUtil.resolve(access.player());
        String metadata = metadata(intent, ownerName);
        if (intent.targetAlias() == null) {
            return intent.destination() == null
                    ? new ReplacementFeatureLiveEvidenceSource
                    .ProvisioningWorldEvidence(
                    access.ownerUuid(),
                    ownerName,
                    metadata,
                    null,
                    null,
                    null,
                    observedAtMs
            ) : null;
        }
        Ref<EntityStore> existing = access.world().getEntityRef(
                intent.targetAlias().value()
        );
        if (existing != null && existing.isValid()) {
            return null;
        }
        var placement = placements.computeRestorationPlacement(
                access.actorRef(),
                access.store(),
                5.0D,
                intent.roleId(),
                null
        );
        if (placement == null
                || !intent.ownerWorldKey().equals(placement.worldKey())
                || !matchesDestination(placement, intent.destination())) {
            return null;
        }
        CoopResidentStateSnapshot state =
                new CoopResidentStateSnapshot(
                        intent.targetAlias().value(),
                        null,
                        -1,
                        intent.roleId(),
                        null,
                        new TameworkOwnerComponent(
                                intent.ownerUuid(), ownerName
                        ),
                        new TameworkTamedComponent(true),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        observedAtMs
                );
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshotCodecs.encode(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        CoopResidentStateSnapshot.class,
                        state
                );
        return new ReplacementFeatureLiveEvidenceSource
                .ProvisioningWorldEvidence(
                access.ownerUuid(),
                ownerName,
                metadata,
                intent.destination(),
                placement,
                encoded,
                observedAtMs
        );
    }

    private boolean availableRole(String roleId) {
        try {
            NPCPlugin plugin = NPCPlugin.get();
            return plugin != null && plugin.hasRoleName(roleId);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean matchesDestination(
            com.alechilles.alecstamework.companion.placement
                    .CompanionSpawnPlacement placement,
            @Nullable PopulationAdmissionLocation destination
    ) {
        if (destination == null) {
            return true;
        }
        return placement.worldKey().equals(destination.worldName())
                && ChunkUtil.indexChunkFromBlock(
                placement.x(), placement.z()
        ) == ChunkUtil.indexChunk(
                destination.chunkX(), destination.chunkZ()
        );
    }

    private String metadata(
            ReplacementFeatureLiveEvidenceSource.ProvisioningWorldIntent intent,
            @Nullable String ownerName
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("source", "replacement-provisioning");
        json.addProperty("origin", intent.origin().stableKey());
        json.addProperty("ownerUuid", intent.ownerUuid().toString());
        if (ownerName != null) {
            json.addProperty("ownerName", ownerName);
        }
        json.addProperty("ownerWorldKey", intent.ownerWorldKey());
        json.addProperty("roleId", intent.roleId());
        return json.toString();
    }
}
