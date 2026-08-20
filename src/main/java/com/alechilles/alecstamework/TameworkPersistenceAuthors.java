package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CommandRestorationCompletionListener;
import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.SpawnerEffectService;
import com.alechilles.alecstamework.items.persistence.FreeCompanionRestorationAuthor;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.items.persistence.HytaleUuidCompletionDispatcher;
import com.alechilles.alecstamework.items.persistence.PositiveEvidenceDormantAuthor;
import com.alechilles.alecstamework.items.persistence.ReplacementProfileSnapshotSink;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureAuthor;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturePublishedEventMapper;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactReleaseAuthor;
import com.alechilles.alecstamework.items.persistence.SpawnerPersistenceAuthorResult;
import com.alechilles.alecstamework.items.persistence.SpawnerPersistenceCompletionListener;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.alechilles.alecstamework.items.persistence.TameworkDormantCompanionEventSink;
import com.alechilles.alecstamework.items.persistence.TameworkDormantSnapshotFactsReader;
import com.alechilles.alecstamework.items.persistence.TameworkFullStateSnapshotReader;
import com.alechilles.alecstamework.items.persistence.TameworkRestorationSnapshotResolver;
import com.alechilles.alecstamework.items.persistence.checkpoint.ReplacementCompanionEntityCheckpointSink;
import com.alechilles.alecstamework.items.persistence.checkpoint.ExactCheckpointCompanionRecallRecovery;
import com.alechilles.alecstamework.npc.components.TameworkPersistenceRetirementComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopAuthor;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopProjectionView;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Composes released gameplay authors and read-only views over one facade bundle.
 *
 * <p>This collaborator keeps author construction out of the bootstrap
 * orchestrator and makes the shared persistence authority explicit.</p>
 */
final class TameworkPersistenceAuthors {
    private TameworkPersistenceAuthors() {
    }

    @Nonnull
    static Bundle create(
            @Nonnull HytaleLogger logger,
            @Nonnull TameworkEventBus events,
            @Nonnull LoadedNpcIdentityIndex identityIndex,
            @Nonnull PersistenceDomainFacades facades,
            @Nonnull ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent
                    > retirementType
    ) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(identityIndex, "identityIndex");
        Objects.requireNonNull(facades, "facades");

        ReplacementProfileSnapshotSink profileSnapshots =
                profileSnapshots(logger, facades);
        ExactCheckpointCompanionRecallRecovery exactRecallRecovery =
                new ExactCheckpointCompanionRecallRecovery(
                        facades, identityIndex, retirementType, logger
                );
        ReplacementCompanionEntityCheckpointSink checkpointSink =
                new ReplacementCompanionEntityCheckpointSink(
                        facades,
                        detail -> logger.at(Level.WARNING).log(detail),
                        identityIndex,
                        exactRecallRecovery::recoverReturnedOriginal,
                        exactRecallRecovery::suppressesCheckpoint,
                        facades.throughputMetrics()
                );
        CommandLinkedNpcStateSnapshotService stateSnapshots =
                new CommandLinkedNpcStateSnapshotService(
                        profileSnapshots,
                        identityIndex,
                        checkpointSink
                );
        TameworkFullStateSnapshotReader snapshots =
                new TameworkFullStateSnapshotReader(
                        new CoopResidentStateSnapshotService()
                );
        HytaleCapturedArtifactAdapter artifacts =
                new HytaleCapturedArtifactAdapter();
        HytaleUuidCompletionDispatcher completions =
                new HytaleUuidCompletionDispatcher();
        SpawnerPersistenceCompletionListener feedback =
                new SpawnerCompletionFeedback(logger);

        return new Bundle(
                stateSnapshots,
                profileSnapshots,
                checkpointSink,
                captureAuthor(
                        facades,
                        events,
                        snapshots,
                        artifacts,
                        completions,
                        feedback
                ),
                new SpawnerCapturedArtifactReleaseAuthor(
                        facades,
                        artifacts,
                        completions,
                        System::currentTimeMillis,
                        identityIndex,
                        feedback
                ),
                restorationAuthor(facades, completions),
                dormantAuthor(logger, events, facades, snapshots),
                new DirectLiveCoopAuthor(facades),
                new DirectLiveCoopProjectionView(facades),
                exactRecallRecovery
        );
    }

    private static ReplacementProfileSnapshotSink profileSnapshots(
            HytaleLogger logger,
            PersistenceDomainFacades facades
    ) {
        return new ReplacementProfileSnapshotSink(
                facades.queries(),
                facades.operations(),
                System::currentTimeMillis,
                detail -> logger.at(Level.WARNING).log(detail),
                facades.throughputMetrics()
        );
    }

    private static SpawnerCaptureAuthor captureAuthor(
            PersistenceDomainFacades facades,
            TameworkEventBus events,
            TameworkFullStateSnapshotReader snapshots,
            HytaleCapturedArtifactAdapter artifacts,
            HytaleUuidCompletionDispatcher completions,
            SpawnerPersistenceCompletionListener feedback
    ) {
        return new SpawnerCaptureAuthor(
                facades,
                snapshots,
                artifacts,
                completions,
                System::currentTimeMillis,
                feedback,
                (profile, evidence) -> events.publishCaptureRecorded(
                        SpawnerCapturePublishedEventMapper.map(
                                profile,
                                evidence,
                                System.currentTimeMillis()
                        )
                )
        );
    }

    private static FreeCompanionRestorationAuthor restorationAuthor(
            PersistenceDomainFacades facades,
            HytaleUuidCompletionDispatcher completions
    ) {
        return new FreeCompanionRestorationAuthor(
                facades,
                new TameworkDormantSnapshotFactsReader(),
                new TameworkRestorationSnapshotResolver(),
                completions,
                System::currentTimeMillis,
                profile -> CompanionRevivePolicy.featureEnabled(
                        profile.identity().roleId()
                ),
                new CommandRestorationCompletionListener()
        );
    }

    private static PositiveEvidenceDormantAuthor dormantAuthor(
            HytaleLogger logger,
            TameworkEventBus events,
            PersistenceDomainFacades facades,
            TameworkFullStateSnapshotReader snapshots
    ) {
        return new PositiveEvidenceDormantAuthor(
                facades,
                snapshots,
                System::currentTimeMillis,
                new TameworkDormantCompanionEventSink(
                        events::publishDeathRecorded,
                        events::publishLostRecorded
                ),
                warning -> logger.at(Level.WARNING).log(
                        warning.code() + ": " + warning.message()
                                + " (profile=" + warning.profileId() + ")"
                )
        );
    }

    /** Released gameplay boundaries built over one immutable facade reference. */
    record Bundle(
            CommandLinkedNpcStateSnapshotService snapshots,
            ReplacementProfileSnapshotSink profileSink,
            ReplacementCompanionEntityCheckpointSink checkpointSink,
            SpawnerCaptureAuthor captureAuthor,
            SpawnerCapturedArtifactReleaseAuthor releaseAuthor,
            FreeCompanionRestorationAuthor restorationAuthor,
            PositiveEvidenceDormantAuthor dormantAuthor,
            DirectLiveCoopAuthor directLiveCoopAuthor,
            DirectLiveCoopProjectionView directLiveCoopProjections,
            ExactCheckpointCompanionRecallRecovery exactRecallRecovery
    ) {
        Bundle {
            Objects.requireNonNull(snapshots, "snapshots");
            Objects.requireNonNull(profileSink, "profileSink");
            Objects.requireNonNull(checkpointSink, "checkpointSink");
            Objects.requireNonNull(captureAuthor, "captureAuthor");
            Objects.requireNonNull(releaseAuthor, "releaseAuthor");
            Objects.requireNonNull(restorationAuthor, "restorationAuthor");
            Objects.requireNonNull(dormantAuthor, "dormantAuthor");
            Objects.requireNonNull(
                    directLiveCoopAuthor, "directLiveCoopAuthor"
            );
            Objects.requireNonNull(
                    directLiveCoopProjections, "directLiveCoopProjections"
            );
            Objects.requireNonNull(
                    exactRecallRecovery, "exactRecallRecovery"
            );
        }
    }

    /** Best-effort player feedback after a canonical spawner workflow resolves. */
    private static final class SpawnerCompletionFeedback
            implements SpawnerPersistenceCompletionListener {
        private final HytaleLogger logger;
        private final TameworkUiMessageService messages =
                new TameworkUiMessageService();
        private final SpawnerEffectService effects =
                new SpawnerEffectService();

        private SpawnerCompletionFeedback(HytaleLogger logger) {
            this.logger = logger;
        }

        @Override
        public void complete(
                SpawnerPersistenceAuthorResult result,
                SpawnerPublishedEffect publishedEffect,
                com.hypixel.hytale.server.core.universe.world.World world,
                com.hypixel.hytale.component.Store<
                        com.hypixel.hytale.server.core.universe.world.storage.EntityStore
                        > store,
                com.hypixel.hytale.component.Ref<
                        com.hypixel.hytale.server.core.universe.world.storage.EntityStore
                        > actorRef,
                com.hypixel.hytale.server.core.entity.entities.Player player
        ) {
            if (result.published()) {
                effects.playPublishedEffect(world, publishedEffect);
                return;
            }
            messages.show(
                    player,
                    result.kind() == SpawnerPersistenceAuthorResult.Kind.CAPTURE
                            ? "Capture could not be completed."
                            : "Companion release could not be completed.",
                    NotificationStyle.Warning
            );
            String message =
                    "Spawner persistence workflow did not publish (kind="
                            + result.kind() + ", status=" + result.status()
                            + ", workflowStatus=" + result.workflowStatus()
                            + ", detail=" + result.detail() + ").";
            if (result.failure() == null) {
                logger.at(Level.WARNING).log(message);
            } else {
                logger.at(Level.WARNING)
                        .withCause(result.failure())
                        .log(message);
            }
        }
    }
}
