package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeatureLiveEvidenceSource;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Production Hytale 0.5.7 live-evidence source for restored persistence APIs.
 *
 * <p>The registered player-ref system records only owner UUIDs and current
 * world references. Every call then re-resolves the current actor and player
 * inside {@code World.execute}; only immutable domain evidence leaves that
 * continuation.</p>
 */
public final class HytaleReplacementFeatureLiveEvidenceSource
        implements ReplacementFeatureLiveEvidenceSource, AutoCloseable {
    private final OwnerWorldSnapshotExecutor worlds;
    private final FeatureWorldEvidenceFreezer freezer;
    @Nullable
    private final HytaleOwnerWorldDirectory directory;

    public HytaleReplacementFeatureLiveEvidenceSource(
            @Nonnull CommandItemRegistry commandItems,
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull CoopResidentStateSnapshotService snapshots
    ) {
        this(
                new HytaleOwnerWorldDirectory(
                        Objects.requireNonNull(
                                PlayerRef.getComponentType(),
                                "PlayerRef component type"
                        )
                ),
                commandItems,
                snapshotCodecs,
                snapshots
        );
    }

    private HytaleReplacementFeatureLiveEvidenceSource(
            HytaleOwnerWorldDirectory directory,
            CommandItemRegistry commandItems,
            SnapshotCodecRegistry snapshotCodecs,
            CoopResidentStateSnapshotService snapshots
    ) {
        this(
                directory,
                new HytaleFeatureWorldEvidenceFreezer(
                        commandItems,
                        snapshotCodecs,
                        snapshots,
                        System::currentTimeMillis
                ),
                directory
        );
    }

    HytaleReplacementFeatureLiveEvidenceSource(
            @Nonnull OwnerWorldSnapshotExecutor worlds,
            @Nonnull FeatureWorldEvidenceFreezer freezer
    ) {
        this(worlds, freezer, null);
    }

    private HytaleReplacementFeatureLiveEvidenceSource(
            OwnerWorldSnapshotExecutor worlds,
            FeatureWorldEvidenceFreezer freezer,
            @Nullable HytaleOwnerWorldDirectory directory
    ) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.freezer = Objects.requireNonNull(freezer, "freezer");
        this.directory = directory;
    }

    /**
     * Returns the player add/remove lifecycle hook central composition must
     * register before exposing the restored feature APIs.
     */
    @Nonnull
    public RefSystem<EntityStore> trackingSystem() {
        if (directory == null) {
            throw new IllegalStateException(
                    "Test evidence source has no tracking system"
            );
        }
        return directory.trackingSystem();
    }

    @Override
    @Nonnull
    public CompletionStage<RosterAccess> freezeRosterAccess(
            @Nonnull RosterAccessIntent intent
    ) {
        if (intent == null) {
            return CompletableFuture.completedFuture(null);
        }
        return read(
                intent.publicRequest().ownerUuid(),
                null,
                access -> freezer.freezeRoster(access, intent)
        );
    }

    @Override
    @Nonnull
    public CompletionStage<TimedWorldEvidence> freezeTimedWorld(
            @Nonnull TimedWorldIntent intent
    ) {
        if (intent == null) {
            return CompletableFuture.completedFuture(null);
        }
        return read(
                intent.publicRequest().ownerUuid(),
                null,
                access -> freezer.freezeTimed(access, intent)
        );
    }

    @Override
    @Nonnull
    public CompletionStage<ProvisioningWorldEvidence>
    freezeProvisioningWorld(
            @Nonnull ProvisioningWorldIntent intent
    ) {
        if (intent == null) {
            return CompletableFuture.completedFuture(null);
        }
        return read(
                intent.ownerUuid(),
                intent.ownerWorldKey(),
                access -> freezer.freezeProvisioning(access, intent)
        );
    }

    @Override
    @Nonnull
    public CompletionStage<PaidInventoryEvidence> freezePaidInventory(
            @Nonnull PaidInventoryIntent intent
    ) {
        if (intent == null) {
            return CompletableFuture.completedFuture(null);
        }
        return read(
                intent.ownerUuid(),
                null,
                access -> freezer.freezePaid(access, intent)
        );
    }

    @Override
    public void close() {
        if (directory != null) {
            directory.close();
        }
    }

    private <T> CompletionStage<T> read(
            UUID ownerUuid,
            @Nullable String expectedWorldKey,
            Function<HytaleOwnerWorldAccess, T> read
    ) {
        try {
            CompletionStage<T> stage = worlds.read(
                    ownerUuid, expectedWorldKey, read::apply
            );
            return stage == null
                    ? CompletableFuture.completedFuture(null)
                    : stage.exceptionally(failure -> null);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
