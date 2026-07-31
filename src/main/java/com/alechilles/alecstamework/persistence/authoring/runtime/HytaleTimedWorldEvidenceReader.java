package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSnapshotRoleResolver;
import com.alechilles.alecstamework.companion.command.timed
        .TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.placement
        .CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CommandCompanionPlacementService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence
        .TameworkFullStateSnapshotReader;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeatureLiveEvidenceSource;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Freezes timed summon placement or live state on the owner's current world. */
final class HytaleTimedWorldEvidenceReader {
    private static final int SNAPSHOT_VERSION = 1;

    private final SnapshotCodecRegistry snapshotCodecs;
    private final TameworkFullStateSnapshotReader snapshots;
    private final CommandCompanionPlacementService placements =
            new CommandCompanionPlacementService();
    private final LongSupplier clock;

    HytaleTimedWorldEvidenceReader(
            @Nonnull SnapshotCodecRegistry snapshotCodecs,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull LongSupplier clock
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
        this.snapshots = new TameworkFullStateSnapshotReader(
                Objects.requireNonNull(snapshots, "snapshots")
        );
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nullable
    ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence freeze(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent
    ) {
        access.store().assertThread();
        if (!access.ownerUuid().equals(
                intent.publicRequest().ownerUuid()
        )) {
            return null;
        }
        long observedAtMs = clock.getAsLong();
        return intent.action()
                == TimedSummonTransitionRequest.Action.START
                ? start(access, intent, observedAtMs)
                : store(access, intent, observedAtMs);
    }

    @Nullable
    private ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence start(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent,
            long observedAtMs
    ) {
        Ref<EntityStore> existing = access.world().getEntityRef(
                intent.expectedAlias().value()
        );
        if (existing != null && existing.isValid()) {
            return null;
        }
        CompanionSnapshot source = currentTimedSnapshot(
                intent.profile().currentSnapshots()
        );
        if (source == null) {
            return null;
        }
        source = repairStoredSnapshotRole(
                source, intent.profile().identity().roleId()
        );
        CompanionSpawnPlacement placement = intent.requestedPlacement();
        if (placement == null) {
            placement = placements.computeRestorationPlacement(
                    access.actorRef(),
                    access.store(),
                    5.0D,
                    intent.profile().identity().roleId(),
                    null
            );
        }
        if (placement == null
                || !access.worldKey().equals(placement.worldKey())) {
            return null;
        }
        return new ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence(
                access.ownerUuid(),
                access.worldKey(),
                intent.expectedAlias(),
                placement,
                source,
                observedAtMs
        );
    }

    @Nullable
    private ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence store(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent,
            long observedAtMs
    ) {
        if (intent.requestedPlacement() != null
                || intent.profile().currentAlias() == null
                || !intent.expectedAlias().equals(
                intent.profile().currentAlias().alias()
        )) {
            return null;
        }
        Ref<EntityStore> npcRef = access.world().getEntityRef(
                intent.expectedAlias().value()
        );
        NPCEntity npc = npcRef == null || !npcRef.isValid()
                || NPCEntity.getComponentType() == null
                ? null : access.store().getComponent(
                npcRef, NPCEntity.getComponentType()
        );
        if (npc == null || npc.getRoleName() == null
                || npc.getRoleName().isBlank()) {
            return null;
        }
        String snapshotRoleId = resolveSnapshotRoleId(
                npc.getRoleName(), npcRef, access.store()
        );
        TameworkFullStateSnapshotReader.ReadResult read = snapshots.read(
                npcRef,
                access.store(),
                intent.expectedAlias(),
                snapshotRoleId
        );
        if (!read.successful() || read.snapshot() == null) {
            return null;
        }
        CoopResidentStateSnapshot state = withObservedAt(
                read.snapshot(), observedAtMs
        );
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshotCodecs.encode(
                        TimedSummonTransitionRequest.SNAPSHOT_KIND,
                        SNAPSHOT_VERSION,
                        CoopResidentStateSnapshot.class,
                        state
                );
        CompanionSnapshot snapshot = new CompanionSnapshot(
                snapshotId(intent, encoded),
                intent.profile().identity().profileId(),
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                intent.profile().lifecycle().revision().next(),
                true,
                observedAtMs
        );
        return new ReplacementFeatureLiveEvidenceSource.TimedWorldEvidence(
                access.ownerUuid(),
                access.worldKey(),
                intent.expectedAlias(),
                null,
                snapshot,
                observedAtMs
        );
    }

    @Nonnull
    static String resolveSnapshotRoleId(
            @Nonnull String liveRoleId,
            @Nullable AvatarFlightSourceComponent source
    ) {
        return AvatarFlightSnapshotRoleResolver.resolve(liveRoleId, source);
    }

    @Nonnull
    static String resolveStoredSnapshotRoleId(
            @Nullable String storedRoleId,
            @Nullable String profileRoleId
    ) {
        return AvatarFlightSnapshotRoleResolver.repairStoredRole(
                storedRoleId, profileRoleId
        );
    }

    @Nonnull
    private static String resolveSnapshotRoleId(
            @Nonnull String liveRoleId,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Store<EntityStore> store
    ) {
        return AvatarFlightSnapshotRoleResolver.resolve(
                liveRoleId, npcRef, store
        );
    }

    @Nonnull
    private CompanionSnapshot repairStoredSnapshotRole(
            CompanionSnapshot source,
            String profileRoleId
    ) {
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshotCodecs.decode(source, CoopResidentStateSnapshot.class);
        if (!(decoded instanceof SnapshotDecodeResult.Decoded<?> found)) {
            return source;
        }
        CoopResidentStateSnapshot state =
                (CoopResidentStateSnapshot) found.value();
        String repairedRoleId = resolveStoredSnapshotRoleId(
                state.roleId(), profileRoleId
        );
        if (repairedRoleId.equals(normalizeRoleId(state.roleId()))) {
            return source;
        }
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshotCodecs.encode(
                        source.kind(),
                        source.payloadVersion(),
                        CoopResidentStateSnapshot.class,
                        withRoleId(state, repairedRoleId)
                );
        return new CompanionSnapshot(
                source.snapshotId(),
                source.profileId(),
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                source.sourceLifecycleRevision(),
                source.current(),
                source.createdAtMs()
        );
    }

    @Nullable
    private CompanionSnapshot currentTimedSnapshot(
            List<CompanionSnapshot> current
    ) {
        List<CompanionSnapshot> matches = current.stream()
                .filter(snapshot -> TimedSummonTransitionRequest
                        .SNAPSHOT_KIND.equals(snapshot.kind()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private SnapshotId snapshotId(
            ReplacementFeatureLiveEvidenceSource.TimedWorldIntent intent,
            SnapshotCodecRegistry.EncodedSnapshot encoded
    ) {
        String material = "tamework:timed-store-snapshot:v1\u0000"
                + intent.profile().identity().profileId() + "\u0000"
                + intent.expectedAlias() + "\u0000"
                + intent.profile().lifecycle().revision().next().value()
                + "\u0000" + encoded.payloadHash();
        return new SnapshotId(UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private CoopResidentStateSnapshot withObservedAt(
            CoopResidentStateSnapshot source,
            long observedAtMs
    ) {
        return new CoopResidentStateSnapshot(
                source.npcUuid(),
                null,
                -1,
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
                observedAtMs
        );
    }

    private CoopResidentStateSnapshot withRoleId(
            CoopResidentStateSnapshot source,
            String roleId
    ) {
        return new CoopResidentStateSnapshot(
                source.npcUuid(),
                source.coopId(),
                source.residentSlot(),
                roleId,
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
                source.currentHealth(),
                source.maximumHealth(),
                source.healthPercent(),
                source.capturedAtMs()
        );
    }

    @Nonnull
    private static String normalizeRoleId(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim();
    }
}
