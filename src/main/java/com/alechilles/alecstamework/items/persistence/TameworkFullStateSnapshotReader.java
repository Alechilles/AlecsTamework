package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads one complete live companion snapshot without consulting durable persistence.
 */
public final class TameworkFullStateSnapshotReader {
    private final SnapshotSource source;
    private final CoopResidentStateSnapshotCodec codec;

    public TameworkFullStateSnapshotReader(
            @Nonnull CoopResidentStateSnapshotService snapshots
    ) {
        Objects.requireNonNull(snapshots, "Snapshot service is required");
        this.codec = new CoopResidentStateSnapshotCodec();
        this.source = (reference, store, npcUuid, context) ->
                context.coop()
                        ? snapshots.captureSnapshotForManagedCoopPersistence(
                                reference,
                                store,
                                npcUuid,
                                context.coopId(),
                                context.residentSlot(),
                                context.roleId()
                        )
                        : snapshots.captureSnapshotForPersistence(
                                reference,
                                store,
                                npcUuid,
                                context.roleId()
                        );
    }

    TameworkFullStateSnapshotReader(@Nonnull SnapshotSource source) {
        this.source = Objects.requireNonNull(
                source, "Snapshot source is required"
        );
        this.codec = new CoopResidentStateSnapshotCodec();
    }

    /** Reads source-neutral full state for capture, death, or lost authoring. */
    @Nonnull
    public ReadResult read(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store,
            @Nullable NpcAlias sourceAlias,
            @Nullable String roleId
    ) {
        return readSourceNeutral(reference, store, sourceAlias, roleId);
    }

    /**
     * Reads a profile-owned full-state snapshot without assigning the source
     * NPC any durable roster or lifecycle authority.
     */
    @Nonnull
    public ReadResult readSourceNeutral(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store,
            @Nullable NpcAlias sourceAlias,
            @Nullable String roleId
    ) {
        return readInternal(
                reference,
                store,
                sourceAlias,
                new SnapshotContext(false, null, -1, roleId)
        );
    }

    /** Reads full state bound to one exact managed-coop resident slot. */
    @Nonnull
    public ReadResult readCoop(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store,
            @Nullable NpcAlias sourceAlias,
            @Nullable String coopId,
            int residentSlot,
            @Nullable String roleId
    ) {
        if (coopId == null || coopId.isBlank() || residentSlot < 0) {
            return ReadResult.failed(Failure.INVALID_REQUEST);
        }
        return readInternal(
                reference,
                store,
                sourceAlias,
                new SnapshotContext(
                        true,
                        coopId.trim(),
                        residentSlot,
                        roleId
                )
        );
    }

    private ReadResult readInternal(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            NpcAlias sourceAlias,
            SnapshotContext context
    ) {
        if (sourceAlias == null) {
            return ReadResult.failed(Failure.INVALID_REQUEST);
        }
        try {
            CoopResidentStateSnapshot snapshot = source.capture(
                    reference,
                    store,
                    sourceAlias.value(),
                    context
            );
            return snapshot == null
                    ? ReadResult.failed(Failure.SOURCE_UNAVAILABLE)
                    : ReadResult.captured(codec.copy(snapshot));
        } catch (RuntimeException | LinkageError failure) {
            return ReadResult.failed(Failure.CAPTURE_FAILED);
        }
    }

    /** Stable failure categories suitable for author feedback and retry decisions. */
    public enum Failure {
        INVALID_REQUEST,
        SOURCE_UNAVAILABLE,
        CAPTURE_FAILED
    }

    /**
     * Immutable result with exactly one captured snapshot or one failure.
     *
     * @param snapshot complete copied state when the read succeeded
     * @param failure stable failure category when the read failed
     */
    public record ReadResult(
            @Nullable CoopResidentStateSnapshot snapshot,
            @Nullable Failure failure
    ) {
        public ReadResult {
            if ((snapshot == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "Snapshot read must contain exactly one result"
                );
            }
        }

        @Nonnull
        static ReadResult captured(
                @Nonnull CoopResidentStateSnapshot snapshot
        ) {
            return new ReadResult(
                    Objects.requireNonNull(snapshot, "Snapshot is required"),
                    null
            );
        }

        @Nonnull
        static ReadResult failed(@Nonnull Failure failure) {
            return new ReadResult(
                    null,
                    Objects.requireNonNull(failure, "Failure is required")
            );
        }

        /** Returns whether a complete copied snapshot is present. */
        public boolean successful() {
            return snapshot != null;
        }
    }

    @FunctionalInterface
    interface SnapshotSource {
        @Nullable
        CoopResidentStateSnapshot capture(
                @Nullable Ref<EntityStore> reference,
                @Nullable Store<EntityStore> store,
                @Nonnull UUID npcUuid,
                @Nonnull SnapshotContext context
        );
    }

    record SnapshotContext(
            boolean coop,
            @Nullable String coopId,
            int residentSlot,
            @Nullable String roleId
    ) {
    }
}
