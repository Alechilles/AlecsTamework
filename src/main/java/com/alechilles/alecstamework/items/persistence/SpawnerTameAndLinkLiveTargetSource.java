package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture
        .CaptureTameLiveStateHasher;
import com.alechilles.alecstamework.companion.capture.runtime
        .HytaleCaptureTameLiveStateFreezer;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Freezes exact target component state, profile metadata, and desired
 * post-tame state from the input's Store/Ref pair.
 */
final class SpawnerTameAndLinkLiveTargetSource
        implements TameworkSpawnerTameAndLinkEvidenceSource.LiveTargetSource {
    private final TameworkFullStateSnapshotReader snapshots;

    SpawnerTameAndLinkLiveTargetSource(
            TameworkFullStateSnapshotReader snapshots
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    @Nullable
    public TameworkSpawnerTameAndLinkEvidenceSource.LiveTargetSnapshot freeze(
            SpawnerTameAndLinkIntentFactory.Input input,
            TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot config
    ) {
        TameworkFullStateSnapshotReader.ReadResult read = snapshots.read(
                input.sourceRef(),
                input.sourceStore(),
                input.sourceAlias(),
                input.roleId()
        );
        CaptureTameLiveStateHasher.State expected =
                HytaleCaptureTameLiveStateFreezer.freeze(
                        input.sourceRef(),
                        input.sourceStore(),
                        input.sourceAlias()
                );
        if (!read.successful() || read.snapshot() == null
                || expected == null || !validExpected(input, expected)) {
            return null;
        }
        CoopResidentStateSnapshot snapshot = read.snapshot();
        if (!input.sourceAlias().value().equals(snapshot.npcUuid())) {
            return null;
        }
        SpawnerCaptureLiveFacts facts =
                SpawnerCaptureLiveFacts.freeze(snapshot);
        CaptureTameLiveStateHasher.State target =
                target(input, config);
        return new TameworkSpawnerTameAndLinkEvidenceSource
                .LiveTargetSnapshot(
                targetMetadata(facts.metadataJson(), input.actorName()),
                CaptureTameLiveStateHasher.hash(expected),
                CaptureTameLiveStateHasher.hash(target),
                home(input)
        );
    }

    private boolean validExpected(
            SpawnerTameAndLinkIntentFactory.Input input,
            CaptureTameLiveStateHasher.State state
    ) {
        return input.roleId().equals(state.roleId())
                && state.ownerId() == null
                && !state.tamed();
    }

    private CaptureTameLiveStateHasher.State target(
            SpawnerTameAndLinkIntentFactory.Input input,
            TameworkSpawnerTameAndLinkEvidenceSource.ConfigSnapshot config
    ) {
        OwnerId owner = config.familyKey().ownerId();
        return new CaptureTameLiveStateHasher.State(
                config.targetRoleId(),
                true,
                owner,
                input.actorName(),
                true,
                true,
                true,
                owner,
                List.of(rosterLink(owner, config.familyKey().familyId())),
                false,
                0.0D,
                0.0D,
                0.0D,
                AssetMapWithIndexes.NOT_FOUND,
                AssetMapWithIndexes.NOT_FOUND,
                false,
                false
        );
    }

    private String rosterLink(OwnerId owner, String familyId) {
        return "roster:" + owner + ":" + familyId;
    }

    private String targetMetadata(String source, String ownerName) {
        JsonObject metadata;
        try {
            metadata = JsonParser.parseString(source).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Live tame/link metadata is invalid", invalid
            );
        }
        metadata.addProperty("owner_name", ownerName);
        metadata.addProperty("tamed", true);
        return metadata.toString();
    }

    @Nullable
    private CommandRosterHome home(
            SpawnerTameAndLinkIntentFactory.Input input
    ) {
        SpawnerPublishedEffect effect = input.publishedEffect();
        return effect == null
                ? null
                : new CommandRosterHome(
                        input.worldKey(),
                        effect.x(),
                        effect.y(),
                        effect.z()
                );
    }
}
