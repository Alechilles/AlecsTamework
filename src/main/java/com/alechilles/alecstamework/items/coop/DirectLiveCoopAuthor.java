package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authors only the released direct-live coop persistence operations.
 *
 * <p>World scanning and presentation remain outside this class. The author accepts a positive
 * live-entity observation, registers physical slots without replacing imported occupancy,
 * adopts an unprofiled live NPC, and consumes the pre-encoded capture or release values required
 * by the shared replacement workflow.</p>
 */
public final class DirectLiveCoopAuthor {
    private final DirectLiveCoopPersistencePort persistence;
    private final Supplier<OperationId> operationIds;

    /** Creates the production author over the adapter-neutral replacement facades. */
    public DirectLiveCoopAuthor(@Nonnull PersistenceDomainFacades facades) {
        this(
                new DirectLiveCoopDomainFacadePort(facades),
                OperationId::create
        );
    }

    DirectLiveCoopAuthor(
            DirectLiveCoopPersistencePort persistence,
            Supplier<OperationId> operationIds
    ) {
        if (persistence == null || operationIds == null) {
            throw new IllegalArgumentException(
                    "Complete direct-live coop author dependencies are required"
            );
        }
        this.persistence = persistence;
        this.operationIds = operationIds;
    }

    /**
     * Registers loaded slots in canonical key order.
     *
     * <p>An imported occupied slot is authoritative and is never submitted as a structural
     * registration. Existing empty slots replay the same deterministic registration key.</p>
     */
    @Nonnull
    public CompletionStage<List<Outcome>> registerLoadedSlots(
            @Nonnull Collection<CoopSlotKey> loadedSlots
    ) {
        if (loadedSlots == null) {
            throw new IllegalArgumentException("Loaded coop slots are required");
        }
        ArrayList<CoopSlotKey> ordered = new ArrayList<>(loadedSlots);
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Loaded coop slot cannot be null");
        }
        ordered.sort(Comparator.naturalOrder());

        CompletionStage<List<Outcome>> sequence =
                CompletableFuture.completedFuture(new ArrayList<>());
        for (CoopSlotKey slot : ordered) {
            sequence = sequence.thenCompose(outcomes ->
                    ensureRegistered(slot).thenApply(outcome -> {
                        outcomes.add(outcome);
                        return outcomes;
                    })
            );
        }
        return sequence.thenApply(List::copyOf);
    }

    /**
     * Captures one positive live-entity observation into an exact empty physical slot.
     *
     * <p>No captured artifact type is accepted by this API. A missing profile is first adopted
     * through the shared profile operation, then read back before lifecycle-fenced capture is
     * authored.</p>
     */
    @Nonnull
    public CompletionStage<Outcome> captureLive(
            @Nonnull CoopSlotKey slot,
            @Nonnull LiveNpcSource source
    ) {
        require(slot, "Coop slot");
        require(source, "Live NPC source");
        validateSourceForSlot(slot, source);
        if (occupied(slot)) {
            return completed(Outcome.OCCUPIED);
        }
        return ensureRegistered(slot).thenCompose(registration -> {
            if (!registration.successfulRegistration()) {
                return completed(registration);
            }
            if (occupied(slot)) {
                return completed(Outcome.OCCUPIED);
            }
            return readOrAdopt(source).thenCompose(read -> {
                if (!(read instanceof PersistenceReadResult.Found<
                        CompanionProfileReadModel> found)) {
                    return completed(read instanceof PersistenceReadResult.Failed<?>
                            ? Outcome.READ_FAILED : Outcome.PROFILE_UNAVAILABLE);
                }
                return submitCapture(slot, source, found.value());
            });
        });
    }

    /**
     * Releases the exact current resident after a world-side roaming or removal decision.
     */
    @Nonnull
    public CompletionStage<Outcome> releaseOccupied(
            @Nonnull CoopSlotKey slot,
            @Nonnull CompanionSpawnPlacement placement
    ) {
        require(slot, "Coop slot");
        require(placement, "Frozen coop release placement");
        CoopOccupancy occupancy = persistence.projectedCoopSnapshot().get(slot);
        if (occupancy == null) {
            return completed(Outcome.EMPTY);
        }
        ProfileId profileId = occupancy.residency().profileId();
        return persistence.findProfile(profileId).thenCompose(profileRead -> {
            if (!(profileRead instanceof PersistenceReadResult.Found<
                    CompanionProfileReadModel> profileFound)) {
                return completed(profileRead instanceof PersistenceReadResult.Failed<?>
                        ? Outcome.READ_FAILED : Outcome.PROFILE_UNAVAILABLE);
            }
            return persistence.findCoopResidency(profileId)
                    .thenCompose(residencyRead -> {
                        if (!(residencyRead instanceof PersistenceReadResult.Found<
                                CoopResidency> residencyFound)) {
                            return completed(
                                    residencyRead instanceof PersistenceReadResult.Failed<?>
                                            ? Outcome.READ_FAILED
                                            : Outcome.RESIDENCY_UNAVAILABLE
                            );
                        }
                        return submitRelease(
                                slot,
                                profileFound.value(),
                                residencyFound.value(),
                                placement
                        );
                    });
        });
    }

    private CompletionStage<Outcome> ensureRegistered(CoopSlotKey slot) {
        if (occupied(slot)) {
            return completed(Outcome.OCCUPIED_PRESERVED);
        }
        return persistence.findCoopSlot(slot).thenCompose(read -> {
            if (read instanceof PersistenceReadResult.Found<CoopSlot>) {
                return completed(Outcome.ALREADY_REGISTERED);
            }
            if (read instanceof PersistenceReadResult.Failed<CoopSlot>) {
                return completed(Outcome.READ_FAILED);
            }
            PublicOperationSubmission submission = persistence.registerCoopSlot(
                    operationIds.get(),
                    idempotency("coop-slot", slot.toString()),
                    new CoopSlotRegistration(CoopSlot.unoccupied(slot), 0L)
            );
            return completionOutcome(
                    submission, Outcome.REGISTERED, Outcome.REGISTRATION_FAILED
            );
        });
    }

    private CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    readOrAdopt(LiveNpcSource source) {
        return persistence.findProfile(source.alias()).thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Absent<
                    CompanionProfileReadModel>)) {
                return CompletableFuture.completedFuture(read);
            }
            PublicOperationSubmission adoption = persistence.mutateProfile(
                    operationIds.get(),
                    idempotency("coop-adopt", source.alias().toString()),
                    source.adoption()
            );
            if (!adoption.accepted()) {
                return CompletableFuture.completedFuture(
                        PersistenceReadResult.absent()
                );
            }
            return adoption.completion().thenCompose(result -> {
                if (!published(result)) {
                    return CompletableFuture.completedFuture(
                            PersistenceReadResult.absent()
                    );
                }
                return persistence.findProfile(source.alias());
            });
        });
    }

    private CompletionStage<Outcome> submitCapture(
            CoopSlotKey slot,
            LiveNpcSource source,
            CompanionProfileReadModel profile
    ) {
        if (!validLiveProfile(source, profile)) {
            return completed(Outcome.PROFILE_UNAVAILABLE);
        }
        long now = source.adoption().requestedAtMs();
        String material = slot + "|" + source.alias() + "|"
                + profile.lifecycle().revision().value();
        OperationId operationId = operationIds.get();
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                source.encodedSnapshot();
        CompanionSnapshot snapshot = new CompanionSnapshot(
                stableSnapshotId("coop-capture", material),
                profile.identity().profileId(),
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                profile.lifecycle().revision().next(),
                true,
                now
        );
        String retirementReceipt = "coop-retire:"
                + Sha256Hash.ofUtf8(material).value();
        CompanionCoopCaptureRequest request =
                new CompanionCoopCaptureRequest(
                        profile.identity().profileId(),
                        profile.lifecycle().revision(),
                        slot,
                        snapshot,
                        new CoopCaptureSourceEvidence(
                                source.alias(),
                                source.worldKey(),
                                retirementReceipt
                        ),
                        now
                );
        PublicOperationSubmission submission = persistence.captureToCoop(
                operationId,
                idempotency("coop-capture", material),
                request
        );
        return completionOutcome(
                submission, Outcome.CAPTURE_SUBMITTED, Outcome.CAPTURE_FAILED
        );
    }

    private CompletionStage<Outcome> submitRelease(
            CoopSlotKey slot,
            CompanionProfileReadModel profile,
            CoopResidency residency,
            CompanionSpawnPlacement placement
    ) {
        if (!validCoopProfile(slot, profile, residency)) {
            return completed(Outcome.RESIDENCY_UNAVAILABLE);
        }
        CompanionSnapshot snapshot = profile.currentSnapshots().stream()
                .filter(candidate -> candidate.snapshotId().equals(
                        residency.snapshotId()))
                .filter(candidate -> CompanionCoopCaptureRequest.SNAPSHOT_KIND
                        .equals(candidate.kind()))
                .findFirst()
                .orElse(null);
        if (snapshot == null) {
            return completed(Outcome.SNAPSHOT_UNAVAILABLE);
        }
        String material = slot + "|" + residency.profileId() + "|"
                + profile.lifecycle().revision().value();
        NpcAlias targetAlias = new NpcAlias(stableUuid("coop-release", material));
        long now = residency.updatedAtMs();
        CompanionCoopReleaseRequest request =
                new CompanionCoopReleaseRequest(
                        residency.profileId(),
                        profile.lifecycle().revision(),
                        residency,
                        snapshot,
                        targetAlias,
                        placement,
                        "coop-spawn:" + Sha256Hash.ofUtf8(material).value(),
                        now
                );
        PublicOperationSubmission submission = persistence.releaseFromCoop(
                operationIds.get(),
                idempotency("coop-release", material),
                request
        );
        return completionOutcome(
                submission, Outcome.RELEASE_SUBMITTED, Outcome.RELEASE_FAILED
        );
    }

    private CompletionStage<Outcome> completionOutcome(
            PublicOperationSubmission submission,
            Outcome success,
            Outcome failure
    ) {
        if (submission == null || !submission.accepted()) {
            return completed(failure);
        }
        return submission.completion().thenApply(
                result -> published(result) ? success : failure
        );
    }

    private boolean occupied(CoopSlotKey slot) {
        return persistence.projectedCoopSnapshot().containsKey(slot);
    }

    private boolean validLiveProfile(
            LiveNpcSource source,
            CompanionProfileReadModel profile
    ) {
        CompanionAlias alias = profile.currentAlias();
        return profile.lifecycle().state() == LifecycleState.ACTIVE
                && alias != null
                && alias.state() == CompanionAlias.State.CURRENT
                && alias.alias().equals(source.alias())
                && source.worldKey().equals(profile.lifecycle().location().worldKey());
    }

    private boolean validCoopProfile(
            CoopSlotKey slot,
            CompanionProfileReadModel profile,
            CoopResidency residency
    ) {
        return profile.lifecycle().state() == LifecycleState.COOP
                && slot.equals(residency.slotKey())
                && profile.identity().profileId().equals(residency.profileId())
                && profile.lifecycle().location().key().equals(slot.toString());
    }

    private void validateSourceForSlot(
            CoopSlotKey slot,
            LiveNpcSource source
    ) {
        if (!slot.worldKey().equals(source.worldKey())
                || !source.alias().equals(source.adoption().alias())
                || !source.adoption().profileId().equals(source.profileId())
                || !slot.equals(source.observedSlot())
                || !CompanionCoopCaptureRequest.SNAPSHOT_KIND.equals(
                source.encodedSnapshot().kind())
                || source.encodedSnapshot().payloadVersion()
                != CompanionCoopCaptureRequest.SNAPSHOT_VERSION) {
            throw new IllegalArgumentException(
                    "Live coop source must describe the exact entity and physical slot"
            );
        }
    }

    private IdempotencyKey idempotency(String kind, String material) {
        return new IdempotencyKey(
                "released-" + kind + ":" + Sha256Hash.ofUtf8(material).value()
        );
    }

    private SnapshotId stableSnapshotId(String kind, String material) {
        return new SnapshotId(stableUuid(kind, material));
    }

    private UUID stableUuid(String kind, String material) {
        return UUID.nameUUIDFromBytes(
                ("tamework:" + kind + ":" + material)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean published(@Nullable OperationWorkflowResult result) {
        return result != null
                && result.status() == OperationWorkflowResult.Status.PUBLISHED;
    }

    private CompletionStage<Outcome> completed(Outcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private static void require(Object value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    /**
     * Immutable positive world-thread evidence for one live NPC, never a captured item.
     *
     * <p>No ECS component or store-affine object may be added to this record.</p>
     */
    public record LiveNpcSource(
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias alias,
            @Nonnull String worldKey,
            @Nonnull CompanionProfileMutation.AdoptLive adoption,
            @Nonnull CoopSlotKey observedSlot,
            @Nonnull SnapshotCodecRegistry.EncodedSnapshot encodedSnapshot
    ) {
        public LiveNpcSource {
            if (profileId == null || alias == null || adoption == null
                    || observedSlot == null || encodedSnapshot == null
                    || worldKey == null
                    || worldKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Complete live NPC source evidence is required"
                );
            }
            worldKey = worldKey.trim();
        }
    }

    /** Stable author outcomes used by the scanning system and focused tests. */
    public enum Outcome {
        REGISTERED,
        ALREADY_REGISTERED,
        OCCUPIED_PRESERVED,
        OCCUPIED,
        EMPTY,
        CAPTURE_SUBMITTED,
        RELEASE_SUBMITTED,
        READ_FAILED,
        PROFILE_UNAVAILABLE,
        RESIDENCY_UNAVAILABLE,
        SNAPSHOT_UNAVAILABLE,
        REGISTRATION_FAILED,
        CAPTURE_FAILED,
        RELEASE_FAILED;

        boolean successfulRegistration() {
            return this == REGISTERED || this == ALREADY_REGISTERED;
        }
    }

}
