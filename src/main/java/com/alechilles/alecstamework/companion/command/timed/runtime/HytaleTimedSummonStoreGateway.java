package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.ReceiptProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.SourceProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.companion.snapshot
        .SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot
        .SnapshotDecodeResult;
import com.alechilles.alecstamework.items
        .CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components
        .TameworkPersistenceRetirementComponent;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/** Exact source marker, snapshot identity, and post-durable removal bridge for STORE. */
final class HytaleTimedSummonStoreGateway {
    private final World world;
    private final Store<EntityStore> store;
    private final OperationEnvelope operation;
    private final SnapshotCodecRegistry snapshotCodecs;
    private final ComponentType<
            EntityStore,
            TameworkPersistenceRetirementComponent> retirementType;

    HytaleTimedSummonStoreGateway(
            World world,
            Store<EntityStore> store,
            OperationEnvelope operation,
            SnapshotCodecRegistry snapshotCodecs,
            ComponentType<
                    EntityStore,
                    TameworkPersistenceRetirementComponent> retirementType
    ) {
        this.world = world;
        this.store = store;
        this.operation = operation;
        this.snapshotCodecs = snapshotCodecs;
        this.retirementType = retirementType;
    }

    StoreProbe probe(TimedSummonWorldAuthority.Store authority) {
        try {
            store.assertThread();
            Ref<EntityStore> source =
                    world.getEntityRef(authority.liveAlias().value());
            if (source == null || !source.isValid()) {
                return StoreProbe.of(
                        ReceiptProbe.absent(),
                        SourceProbe.absent()
                );
            }
            Components components = components(source);
            if (components == null) {
                return retryable(null);
            }
            WorldChunk chunk = currentChunk(components.transform());
            if (chunk == null) {
                return retryable(null);
            }
            String sourceMismatch = sourceMismatch(authority, components);
            if (sourceMismatch != null) {
                return conflict(new IllegalStateException(
                        "timed_summon_store_source_" + sourceMismatch
                ));
            }
            TameworkPersistenceRetirementComponent receipt =
                    components.receipt();
            ReceiptProbe receiptProbe = receipt == null
                    ? ReceiptProbe.absent()
                    : receipt.matches(authority.profileId(), operation)
                    ? ReceiptProbe.exact(chunk.getIndex())
                    : ReceiptProbe.conflict(new IllegalStateException(
                            "timed_summon_store_receipt-conflict"
                    ));
            return StoreProbe.of(
                    receiptProbe,
                    SourceProbe.exact(chunk.getIndex())
            );
        } catch (RuntimeException | LinkageError failure) {
            return retryable(failure);
        }
    }

    MutationAttempt installReceipt(
            TimedSummonWorldAuthority.Store authority
    ) {
        StoreProbe before = probe(authority);
        if (before.receipt().status()
                == TimedSummonWorldAttempt.EvidenceStatus.EXACT
                && before.source().status()
                == TimedSummonWorldAttempt.EvidenceStatus.EXACT) {
            return MutationAttempt.exact(before.receipt().chunkIndex());
        }
        if (before.source().status()
                != TimedSummonWorldAttempt.EvidenceStatus.EXACT
                || before.receipt().status()
                != TimedSummonWorldAttempt.EvidenceStatus.ABSENT) {
            return before.source().status()
                    == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE
                    || before.receipt().status()
                    == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE
                    ? MutationAttempt.retryable(
                            first(
                                    before.receipt().cause(),
                                    before.source().cause()
                            )
                    )
                    : MutationAttempt.conflict(
                            first(
                                    before.receipt().cause(),
                                    before.source().cause()
                            )
                    );
        }
        try {
            Ref<EntityStore> source =
                    world.getEntityRef(authority.liveAlias().value());
            if (source == null || !source.isValid()) {
                return MutationAttempt.conflict(null);
            }
            store.putComponent(
                    source,
                    retirementType,
                    TameworkPersistenceRetirementComponent.exact(
                            authority.profileId(), operation
                    )
            );
            StoreProbe after = probe(authority);
            return exactPair(after)
                    ? MutationAttempt.exact(
                            after.receipt().chunkIndex()
                    )
                    : MutationAttempt.retryable(evidenceCause(after));
        } catch (RuntimeException | LinkageError failure) {
            StoreProbe after = probe(authority);
            return exactPair(after)
                    ? MutationAttempt.exact(
                            after.receipt().chunkIndex()
                    )
                    : MutationAttempt.retryable(failure);
        }
    }

    MutationAttempt retireExact(
            TimedSummonWorldAuthority.Store authority
    ) {
        StoreProbe before = probe(authority);
        if (!exactPair(before)
                || !java.util.Objects.equals(
                before.receipt().chunkIndex(),
                before.source().chunkIndex()
        )) {
            return before.receipt().status()
                    == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE
                    || before.source().status()
                    == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE
                    ? MutationAttempt.retryable(evidenceCause(before))
                    : MutationAttempt.conflict(evidenceCause(before));
        }
        long chunkIndex = before.source().chunkIndex();
        try {
            Ref<EntityStore> source =
                    world.getEntityRef(authority.liveAlias().value());
            if (source == null || !source.isValid()) {
                return MutationAttempt.retryable(null);
            }
            store.removeEntity(source, RemoveReason.REMOVE);
        } catch (RuntimeException | LinkageError failure) {
            StoreProbe after = probe(authority);
            return after.source().status()
                    == TimedSummonWorldAttempt.EvidenceStatus.ABSENT
                    ? MutationAttempt.exact(chunkIndex)
                    : MutationAttempt.retryable(failure);
        }
        StoreProbe after = probe(authority);
        return after.source().status()
                == TimedSummonWorldAttempt.EvidenceStatus.ABSENT
                ? MutationAttempt.exact(chunkIndex)
                : after.source().status()
                == TimedSummonWorldAttempt.EvidenceStatus.RETRYABLE
                ? MutationAttempt.retryable(after.source().cause())
                : MutationAttempt.conflict(after.source().cause());
    }

    @Nullable
    private Components components(Ref<EntityStore> source) {
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        ComponentType<EntityStore, NPCEntity> npcType =
                NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType =
                TransformComponent.getComponentType();
        if (uuidType == null || npcType == null
                || transformType == null || retirementType == null) {
            return null;
        }
        return new Components(
                store.getComponent(source, uuidType),
                store.getComponent(source, npcType),
                store.getComponent(source, transformType),
                store.getComponent(source, retirementType)
        );
    }

    @Nullable
    private String sourceMismatch(
            TimedSummonWorldAuthority.Store authority,
            Components components
    ) {
        if (components.identity() == null || components.npc() == null) {
            return "missing-components";
        }
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshotCodecs.decode(
                        authority.snapshot(),
                        CoopResidentStateSnapshot.class
                );
        if (!(decoded instanceof SnapshotDecodeResult.Decoded<?> exact)
                || !(exact.value()
                instanceof CoopResidentStateSnapshot snapshot)) {
            return "snapshot-decode";
        }
        return TimedSummonStoreSourceEvidence.mismatch(
                authority.liveAlias().value(),
                components.identity().getUuid(),
                components.npc().getUuid(),
                snapshot.npcUuid(),
                snapshot.roleId(),
                components.npc().getRoleName()
        );
    }

    /** Accepts only the tester-era case damage, never a different role ID. */
    static boolean sameRole(String frozenRoleId, String liveRoleId) {
        return frozenRoleId != null && liveRoleId != null
                && frozenRoleId.equalsIgnoreCase(liveRoleId);
    }

    @Nullable
    private WorldChunk currentChunk(
            @Nullable TransformComponent transform
    ) {
        Ref<ChunkStore> chunkRef =
                transform == null ? null : transform.getChunkRef();
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunks =
                chunkStore == null ? null : chunkStore.getStore();
        if (chunkRef == null || !chunkRef.isValid() || chunks == null) {
            return null;
        }
        WorldChunk chunk = chunks.getComponent(
                chunkRef, WorldChunk.getComponentType()
        );
        return chunk != null && chunk.getWorld() == world ? chunk : null;
    }

    private boolean exactPair(StoreProbe probe) {
        return probe.receipt().status()
                == TimedSummonWorldAttempt.EvidenceStatus.EXACT
                && probe.source().status()
                == TimedSummonWorldAttempt.EvidenceStatus.EXACT;
    }

    private StoreProbe retryable(@Nullable Throwable cause) {
        return StoreProbe.of(
                ReceiptProbe.retryable(cause),
                SourceProbe.retryable(cause)
        );
    }

    private StoreProbe conflict(@Nullable Throwable cause) {
        return StoreProbe.of(
                ReceiptProbe.conflict(cause),
                SourceProbe.conflict(cause)
        );
    }

    private Throwable evidenceCause(StoreProbe probe) {
        return first(probe.receipt().cause(), probe.source().cause());
    }

    private Throwable first(Throwable first, Throwable second) {
        return first == null ? second : first;
    }

    private record Components(
            UUIDComponent identity,
            NPCEntity npc,
            TransformComponent transform,
            TameworkPersistenceRetirementComponent receipt
    ) {
    }
}
