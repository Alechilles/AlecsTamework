package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.LostRecoveryWriteResult;
import com.alechilles.alecstamework.persistence.sqlite.LostRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.RecoveredProjectionSnapshotLoadResult;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Builds and durably publishes new lost transitions with their complete live-state snapshot.
 *
 * <p>A transition is visible to runtime callers only after the tracked SQLite transaction commits.
 * Missing full state, queue rejection, and terminal write failure all fail closed.</p>
 */
final class CommandLostTransitionPersistenceService {
    private final FullSnapshotSource snapshotSource;
    private final DurableSnapshotSource durableSnapshotSource;
    private final TrackedLostWriter writer;
    private final TrackedLostDeleter deleter;
    private final ConcurrentHashMap<UUID, UUID> pendingTokensByNpc = new ConcurrentHashMap<>();

    CommandLostTransitionPersistenceService(@Nonnull CommandLinkedNpcStateSnapshotService snapshotService,
                                            @Nonnull LostRepository repository) {
        this(
                snapshotService::getFullSnapshot,
                repository::loadRecoveredProjectionSnapshot,
                repository::upsertTracked,
                repository::deleteTracked
        );
    }

    CommandLostTransitionPersistenceService(@Nonnull FullSnapshotSource snapshotSource,
                                            @Nonnull TrackedLostWriter writer,
                                            @Nonnull TrackedLostDeleter deleter) {
        this(
                snapshotSource,
                ignored -> RecoveredProjectionSnapshotLoadResult.notFound("fallback_not_configured"),
                writer,
                deleter
        );
    }

    CommandLostTransitionPersistenceService(@Nonnull FullSnapshotSource snapshotSource,
                                            @Nonnull DurableSnapshotSource durableSnapshotSource,
                                            @Nonnull TrackedLostWriter writer,
                                            @Nonnull TrackedLostDeleter deleter) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.durableSnapshotSource = Objects.requireNonNull(
                durableSnapshotSource, "durableSnapshotSource");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.deleter = Objects.requireNonNull(deleter, "deleter");
    }

    @Nonnull
    static CommandLinkedNpcLostService.LostLinkedNpcSnapshot prepare(
            @Nullable CommandLinkedNpcLostService.LostLinkedNpcSnapshot current,
            @Nonnull UUID npcUuid,
            @Nullable Vector3d sourceHintPosition,
            @Nullable Vector3d alternateSourceHintPosition,
            @Nullable Vector3d destination,
            long queuedAtMs,
            long droppedAtMs,
            int retryAttempts,
            long now) {
        long resolvedQueuedAtMs = queuedAtMs != 0L ? queuedAtMs : now;
        long resolvedDroppedAtMs = droppedAtMs != 0L ? droppedAtMs : now;
        Vector3d resolvedLastKnown = copy(firstNonNull(
                sourceHintPosition, alternateSourceHintPosition, destination));
        Vector3d resolvedHome = copy(alternateSourceHintPosition);
        if (current != null) {
            resolvedLastKnown = resolvedLastKnown != null
                    ? resolvedLastKnown
                    : copy(current.lastKnownPosition());
            resolvedHome = resolvedHome != null ? resolvedHome : copy(current.homePosition());
            resolvedQueuedAtMs = current.lastRelocationQueuedAtMs() != 0L
                    ? current.lastRelocationQueuedAtMs()
                    : resolvedQueuedAtMs;
        }
        return new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                npcUuid,
                resolvedLastKnown,
                resolvedHome,
                resolvedQueuedAtMs,
                resolvedDroppedAtMs,
                Math.max(0, retryAttempts),
                null,
                0L
        );
    }

    @Nonnull
    PersistStatus persist(
            @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot lostSnapshot,
            @Nonnull Consumer<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> committedPublisher) {
        SnapshotResolution resolution = resolveSnapshot(lostSnapshot.npcUuid());
        if (resolution.snapshot() == null) {
            return resolution.failureStatus();
        }
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot = resolution.snapshot();
        UUID token = UUID.randomUUID();
        if (pendingTokensByNpc.putIfAbsent(lostSnapshot.npcUuid(), token) != null) {
            return PersistStatus.ALREADY_PENDING;
        }
        final PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> submission;
        try {
            submission = writer.upsert(lostSnapshot, fullSnapshot);
        } catch (RuntimeException exception) {
            pendingTokensByNpc.remove(lostSnapshot.npcUuid(), token);
            return PersistStatus.REJECTED;
        }
        if (submission == null || !submission.accepted()) {
            pendingTokensByNpc.remove(lostSnapshot.npcUuid(), token);
            return PersistStatus.REJECTED;
        }
        submission.completion().whenComplete((outcome, failure) -> {
            if (!pendingTokensByNpc.remove(lostSnapshot.npcUuid(), token)) {
                return;
            }
            if (failure == null && outcome != null && outcome.isCommitted()) {
                committedPublisher.accept(lostSnapshot);
            }
        });
        return PersistStatus.ACCEPTED_PENDING;
    }

    @Nonnull
    private SnapshotResolution resolveSnapshot(@Nonnull UUID npcUuid) {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot liveSnapshot = null;
        try {
            liveSnapshot = snapshotSource.get(npcUuid);
        } catch (RuntimeException ignored) {
            // A verified durable recovery envelope may still safely bridge a lost process cache.
        }
        if (liveSnapshot != null) {
            return npcUuid.equals(liveSnapshot.npcUuid())
                    ? SnapshotResolution.found(liveSnapshot)
                    : SnapshotResolution.missing(PersistStatus.MISSING_FULL_SNAPSHOT);
        }
        RecoveredProjectionSnapshotLoadResult durable;
        try {
            durable = durableSnapshotSource.load(npcUuid);
        } catch (RuntimeException exception) {
            return SnapshotResolution.missing(PersistStatus.DURABLE_SNAPSHOT_READ_FAILED);
        }
        if (durable == null) {
            return SnapshotResolution.missing(PersistStatus.DURABLE_SNAPSHOT_READ_FAILED);
        }
        CommandLostTransitionDiagnostics.recordDurableFallback(durable);
        if (durable.isFound() && durable.snapshot() != null) {
            return SnapshotResolution.found(copyForNpc(durable.snapshot(), npcUuid));
        }
        PersistStatus failure = switch (durable.status()) {
            case CONFLICT -> PersistStatus.DURABLE_SNAPSHOT_CONFLICT;
            case FAILED -> PersistStatus.DURABLE_SNAPSHOT_READ_FAILED;
            case FOUND, NOT_FOUND -> PersistStatus.MISSING_FULL_SNAPSHOT;
        };
        return SnapshotResolution.missing(failure);
    }

    @Nonnull
    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot copyForNpc(
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot source,
            @Nonnull UUID npcUuid) {
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                npcUuid,
                source.coopId(),
                source.residentSlot(),
                source.roleId(),
                source.commandLinks(),
                source.owner(),
                source.tamed(),
                source.npcName(),
                source.happiness(),
                source.needs(),
                source.breeding(),
                source.leveling(),
                source.traits(),
                source.talents(),
                source.lifeStage(),
                source.attachments(),
                source.healthPercent(),
                source.capturedAtMs()
        );
    }

    @Nonnull
    CancelStatus cancel(@Nullable UUID npcUuid) {
        if (npcUuid == null || pendingTokensByNpc.remove(npcUuid) == null) {
            return CancelStatus.NO_PENDING;
        }
        try {
            PersistenceWriteQueue.WriteSubmission<Void> compensation = deleter.delete(npcUuid);
            return compensation != null && compensation.accepted()
                    ? CancelStatus.COMPENSATION_PENDING
                    : CancelStatus.COMPENSATION_REJECTED;
        } catch (RuntimeException exception) {
            return CancelStatus.COMPENSATION_REJECTED;
        }
    }

    /** Returns whether a complete lost transition has been accepted but not yet published. */
    boolean isPending(@Nullable UUID npcUuid) {
        return npcUuid != null && pendingTokensByNpc.containsKey(npcUuid);
    }

    @Nullable
    private static Vector3d firstNonNull(@Nullable Vector3d first,
                                         @Nullable Vector3d second,
                                         @Nullable Vector3d third) {
        return first != null ? first : second != null ? second : third;
    }

    @Nullable
    private static Vector3d copy(@Nullable Vector3d value) {
        return value != null ? new Vector3d(value) : null;
    }

    enum PersistStatus {
        ACCEPTED_PENDING,
        ALREADY_PENDING,
        MISSING_FULL_SNAPSHOT,
        DURABLE_SNAPSHOT_CONFLICT,
        DURABLE_SNAPSHOT_READ_FAILED,
        REJECTED
    }

    enum CancelStatus {
        NO_PENDING,
        COMPENSATION_PENDING,
        COMPENSATION_REJECTED
    }

    @FunctionalInterface
    interface FullSnapshotSource {
        @Nullable
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot get(@Nonnull UUID npcUuid);
    }

    @FunctionalInterface
    interface DurableSnapshotSource {
        @Nonnull
        RecoveredProjectionSnapshotLoadResult load(@Nonnull UUID currentNpcUuid);
    }

    @FunctionalInterface
    interface TrackedLostWriter {
        @Nonnull
        PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> upsert(
                @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot lostSnapshot,
                @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot);
    }

    @FunctionalInterface
    interface TrackedLostDeleter {
        @Nonnull
        PersistenceWriteQueue.WriteSubmission<Void> delete(@Nonnull UUID npcUuid);
    }

    private record SnapshotResolution(
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            @Nonnull PersistStatus failureStatus) {
        @Nonnull
        private static SnapshotResolution found(
                @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
            return new SnapshotResolution(snapshot, PersistStatus.ACCEPTED_PENDING);
        }

        @Nonnull
        private static SnapshotResolution missing(@Nonnull PersistStatus failureStatus) {
            return new SnapshotResolution(null, failureStatus);
        }
    }
}
