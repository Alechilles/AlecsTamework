package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sole internal facade for applying prepared Tamework owner-component mutations.
 */
public final class OwnerComponentMutationService {
    private final OwnerPopulationAdmissionCoordinator coordinator;
    private final OwnerDerivedAuthorityMutationService derivedAuthority =
            new OwnerDerivedAuthorityMutationService();

    public OwnerComponentMutationService(@Nonnull OwnerPopulationAdmissionCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Nonnull
    public MutationResult applyImmediate(@Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull PreparedOwnerPopulationAdmission prepared,
                                         @Nullable UUID newOwnerId,
                                         @Nullable String newOwnerName,
                                         long settingsRevision,
                                         @Nonnull ClaimProviderGeneration providerGeneration) {
        return applyImmediate(
                npcRef,
                store,
                prepared,
                prepared.plan().transition().expectedOwnerId(),
                newOwnerId,
                newOwnerName,
                settingsRevision,
                providerGeneration
        );
    }

    /**
     * Applies a prepared transition when the live representation intentionally differs from the
     * durable profile, such as a dormant snapshot restored into a replacement UUID.
     */
    @Nonnull
    public MutationResult applyImmediate(@Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull PreparedOwnerPopulationAdmission prepared,
                                         @Nullable UUID expectedLiveOwnerId,
                                         @Nullable UUID newOwnerId,
                                         @Nullable String newOwnerName,
                                         long settingsRevision,
                                         @Nonnull ClaimProviderGeneration providerGeneration) {
        if (!npcRef.isValid()) {
            coordinator.cancelAsync(prepared, "owner-component-reference-invalid");
            return MutationResult.notApplied("owner-component-reference-invalid");
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            coordinator.cancelAsync(prepared, "owner-component-type-unavailable");
            return MutationResult.notApplied("owner-component-type-unavailable");
        }
        TameworkOwnerComponent previous = store.getComponent(npcRef, ownerType);
        if (!matchesExpectedOwner(previous, expectedLiveOwnerId)) {
            coordinator.cancelAsync(prepared, "owner-component-state-changed");
            return MutationResult.notApplied("owner-component-state-changed");
        }
        if (!coordinator.claimForApply(
                prepared,
                settingsRevision,
                providerGeneration
        )) {
            return MutationResult.notApplied("owner-component-reservation-invalid");
        }
        WriteResult write = writeClaimedImmediate(
                npcRef,
                store,
                prepared,
                expectedLiveOwnerId,
                newOwnerId,
                newOwnerName
        );
        if (!write.applied()) {
            return MutationResult.notApplied(write.reason());
        }
        return MutationResult.applied(commitSafely(prepared));
    }

    /** Writes a component after a combined owner/claim coordinator has claimed both reservations. */
    @Nonnull
    public WriteResult writeClaimedImmediate(@Nonnull Ref<EntityStore> npcRef,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull PreparedOwnerPopulationAdmission prepared,
                                             @Nullable UUID expectedLiveOwnerId,
                                             @Nullable UUID newOwnerId,
                                             @Nullable String newOwnerName) {
        if (prepared.state() != PreparedOwnerPopulationAdmission.State.APPLYING) {
            return WriteResult.notApplied("owner-component-capability-not-applying");
        }
        if (!npcRef.isValid()) {
            coordinator.cancelAsync(prepared, "owner-component-reference-invalid");
            return WriteResult.notApplied("owner-component-reference-invalid");
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            coordinator.cancelAsync(prepared, "owner-component-type-unavailable");
            return WriteResult.notApplied("owner-component-type-unavailable");
        }
        TameworkOwnerComponent previous = store.getComponent(npcRef, ownerType);
        if (!matchesExpectedOwner(previous, expectedLiveOwnerId)) {
            coordinator.cancelAsync(prepared, "owner-component-state-changed");
            return WriteResult.notApplied("owner-component-state-changed");
        }
        OwnerDerivedAuthorityMutationService.Snapshot previousDerived;
        try {
            previousDerived = derivedAuthority.capture(npcRef, store);
        } catch (RuntimeException | LinkageError exception) {
            coordinator.cancelAsync(prepared, "owner-derived-authority-read-failed");
            return WriteResult.notApplied("owner-derived-authority-read-failed");
        }
        try {
            if (newOwnerId == null) {
                store.tryRemoveComponent(npcRef, ownerType);
            } else {
                store.putComponent(
                        npcRef,
                        ownerType,
                        new TameworkOwnerComponent(newOwnerId, normalizeName(newOwnerName))
                );
            }
            derivedAuthority.applyImmediate(
                    npcRef, store, previousDerived, expectedLiveOwnerId, newOwnerId
            );
            return WriteResult.success();
        } catch (RuntimeException | LinkageError exception) {
            if (restoreImmediate(store, npcRef, ownerType, previous)) {
                if (derivedAuthority.restoreImmediate(npcRef, store, previousDerived)) {
                    coordinator.cancelAsync(prepared, "owner-component-write-failed");
                    return WriteResult.notApplied("owner-component-write-failed");
                }
            }
            markReadinessDegradedSafely("owner_component_write_ambiguous");
            return WriteResult.notApplied("owner-component-write-ambiguous");
        }
    }

    /**
     * Installs a planned identity and owner into an NPC spawn holder after the combined batch unit
     * has entered APPLYING. The holder is not live yet, so a failed store insertion can still
     * cancel the unit without exposing an uncounted owned entity.
     */
    @Nonnull
    public WriteResult writeClaimedSpawnHolder(
            @Nonnull Holder<EntityStore> holder,
            @Nonnull PreparedOwnerPopulationAdmission prepared,
            @Nonnull UUID plannedNpcUuid,
            @Nullable UUID newOwnerId,
            @Nullable String newOwnerName
    ) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
        if (prepared.state() != PreparedOwnerPopulationAdmission.State.APPLYING) {
            return WriteResult.notApplied("owner-component-capability-not-applying");
        }
        OwnerPopulationAdmissionPlan plan = prepared.plan();
        if (!Objects.equals(plan.finalNpcUuid(), plannedNpcUuid)
                || !Objects.equals(plan.transition().newOwnerId(), newOwnerId)) {
            return WriteResult.notApplied("owner-component-spawn-plan-mismatch");
        }
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (uuidType == null || (newOwnerId != null && ownerType == null)) {
            return WriteResult.notApplied("owner-component-type-unavailable");
        }
        try {
            holder.putComponent(uuidType, new UUIDComponent(plannedNpcUuid));
            if (newOwnerId != null) {
                holder.putComponent(
                        ownerType,
                        new TameworkOwnerComponent(newOwnerId, normalizeName(newOwnerName))
                );
            }
            return WriteResult.success();
        } catch (RuntimeException | LinkageError exception) {
            return WriteResult.notApplied("owner-component-spawn-holder-write-failed");
        }
    }

    /**
     * Queues a system-safe component write. The journal reconciles ambiguity if buffer flush fails.
     */
    @Nonnull
    public MutationResult applyBuffered(@Nonnull Ref<EntityStore> npcRef,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull PreparedOwnerPopulationAdmission prepared,
                                        @Nullable UUID newOwnerId,
                                        @Nullable String newOwnerName,
                                        long settingsRevision,
                                        @Nonnull ClaimProviderGeneration providerGeneration) {
        return applyBuffered(
                npcRef,
                store,
                commandBuffer,
                prepared,
                prepared.plan().transition().expectedOwnerId(),
                newOwnerId,
                newOwnerName,
                settingsRevision,
                providerGeneration
        );
    }

    /** Buffered counterpart for a replacement representation with an explicit live expectation. */
    @Nonnull
    public MutationResult applyBuffered(@Nonnull Ref<EntityStore> npcRef,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nonnull PreparedOwnerPopulationAdmission prepared,
                                        @Nullable UUID expectedLiveOwnerId,
                                        @Nullable UUID newOwnerId,
                                        @Nullable String newOwnerName,
                                        long settingsRevision,
                                        @Nonnull ClaimProviderGeneration providerGeneration) {
        if (!npcRef.isValid()) {
            coordinator.cancelAsync(prepared, "owner-component-reference-invalid");
            return MutationResult.notApplied("owner-component-reference-invalid");
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            coordinator.cancelAsync(prepared, "owner-component-type-unavailable");
            return MutationResult.notApplied("owner-component-type-unavailable");
        }
        TameworkOwnerComponent previous = store.getComponent(npcRef, ownerType);
        if (!matchesExpectedOwner(previous, expectedLiveOwnerId)) {
            coordinator.cancelAsync(prepared, "owner-component-state-changed");
            return MutationResult.notApplied("owner-component-state-changed");
        }
        OwnerDerivedAuthorityMutationService.Snapshot previousDerived;
        try {
            previousDerived = derivedAuthority.capture(npcRef, store);
        } catch (RuntimeException | LinkageError exception) {
            coordinator.cancelAsync(prepared, "owner-derived-authority-read-failed");
            return MutationResult.notApplied("owner-derived-authority-read-failed");
        }
        if (!coordinator.claimForApply(prepared, settingsRevision, providerGeneration)) {
            return MutationResult.notApplied("owner-component-reservation-invalid");
        }
        try {
            if (newOwnerId == null) {
                commandBuffer.tryRemoveComponent(npcRef, ownerType);
            } else {
                commandBuffer.putComponent(
                        npcRef,
                        ownerType,
                        new TameworkOwnerComponent(newOwnerId, normalizeName(newOwnerName))
                );
            }
            derivedAuthority.applyBuffered(
                    npcRef, commandBuffer, previousDerived, expectedLiveOwnerId, newOwnerId
            );
        } catch (RuntimeException | LinkageError exception) {
            markReadinessDegradedSafely("owner_component_buffer_write_ambiguous");
            return MutationResult.notApplied("owner-component-buffer-write-ambiguous");
        }
        return MutationResult.applied(commitSafely(prepared));
    }

    @Nonnull
    private CompletableFuture<OwnerPopulationCommitResult> commitSafely(
            @Nonnull PreparedOwnerPopulationAdmission prepared
    ) {
        try {
            CompletableFuture<OwnerPopulationCommitResult> completion =
                    coordinator.commitAsync(prepared);
            if (completion != null) {
                return completion;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The live write already happened; readiness is degraded below for reconciliation.
        }
        markReadinessDegradedSafely("owner_component_commit_start_failed");
        return CompletableFuture.completedFuture(new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED,
                "owner-component-commit-start-failed",
                null
        ));
    }

    private static boolean matchesExpectedOwner(@Nullable TameworkOwnerComponent component,
                                                @Nullable UUID expectedOwnerId) {
        UUID actualOwnerId = component == null ? null : component.getOwnerId();
        return Objects.equals(actualOwnerId, expectedOwnerId);
    }

    private static boolean restoreImmediate(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nullable TameworkOwnerComponent previous
    ) {
        try {
            if (previous == null) {
                store.tryRemoveComponent(npcRef, ownerType);
            } else {
                store.putComponent(npcRef, ownerType, previous.clone());
            }
            return sameComponent(previous, store.getComponent(npcRef, ownerType));
        } catch (RuntimeException | LinkageError ignored) {
            // The prepared operation remains recoverable; startup reconciliation will quarantine it.
            try {
                return sameComponent(previous, store.getComponent(npcRef, ownerType));
            } catch (RuntimeException | LinkageError readFailure) {
                return false;
            }
        }
    }

    private static boolean sameComponent(@Nullable TameworkOwnerComponent expected,
                                         @Nullable TameworkOwnerComponent actual) {
        return expected == null
                ? actual == null
                : actual != null
                && Objects.equals(expected.getOwnerId(), actual.getOwnerId())
                && Objects.equals(expected.getOwnerName(), actual.getOwnerName());
    }

    private void markReadinessDegradedSafely(@Nonnull String reason) {
        try {
            coordinator.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The APPLYING journal remains conservative if readiness reporting also fails.
        }
    }

    @Nullable
    private static String normalizeName(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record MutationResult(boolean applied,
                                 @Nonnull String reason,
                                 @Nullable CompletableFuture<OwnerPopulationCommitResult> completion) {
        @Nonnull
        static MutationResult notApplied(@Nonnull String reason) {
            return new MutationResult(false, reason, null);
        }

        @Nonnull
        static MutationResult applied(@Nonnull CompletableFuture<OwnerPopulationCommitResult> completion) {
            return new MutationResult(true, "owner-component-applied", completion);
        }
    }

    public record WriteResult(boolean applied, @Nonnull String reason) {
        public boolean safeToCancel() {
            return !reason.endsWith("-ambiguous");
        }

        @Nonnull
        static WriteResult notApplied(@Nonnull String reason) {
            return new WriteResult(false, reason);
        }

        @Nonnull
        static WriteResult success() {
            return new WriteResult(true, "owner-component-applied");
        }
    }
}
