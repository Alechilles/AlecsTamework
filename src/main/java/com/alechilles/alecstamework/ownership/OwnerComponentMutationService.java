package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
        if (!matchesExpectedOwner(previous, prepared.plan().transition().expectedOwnerId())) {
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
        try {
            store.putComponent(
                    npcRef,
                    ownerType,
                    new TameworkOwnerComponent(newOwnerId, normalizeName(newOwnerName))
            );
        } catch (RuntimeException | LinkageError exception) {
            restoreImmediate(store, npcRef, ownerType, previous);
            coordinator.cancelAsync(prepared, "owner-component-write-failed");
            return MutationResult.notApplied("owner-component-write-failed");
        }
        return MutationResult.applied(coordinator.commitAsync(prepared));
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
        if (!matchesExpectedOwner(previous, prepared.plan().transition().expectedOwnerId())) {
            coordinator.cancelAsync(prepared, "owner-component-state-changed");
            return MutationResult.notApplied("owner-component-state-changed");
        }
        if (!coordinator.claimForApply(prepared, settingsRevision, providerGeneration)) {
            return MutationResult.notApplied("owner-component-reservation-invalid");
        }
        try {
            commandBuffer.putComponent(
                    npcRef,
                    ownerType,
                    new TameworkOwnerComponent(newOwnerId, normalizeName(newOwnerName))
            );
        } catch (RuntimeException | LinkageError exception) {
            coordinator.cancelAsync(prepared, "owner-component-buffer-write-failed");
            return MutationResult.notApplied("owner-component-buffer-write-failed");
        }
        return MutationResult.applied(coordinator.commitAsync(prepared));
    }

    private static boolean matchesExpectedOwner(@Nullable TameworkOwnerComponent component,
                                                @Nullable UUID expectedOwnerId) {
        UUID actualOwnerId = component == null ? null : component.getOwnerId();
        return Objects.equals(actualOwnerId, expectedOwnerId);
    }

    private static void restoreImmediate(@Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                                         @Nullable TameworkOwnerComponent previous) {
        try {
            store.putComponent(
                    npcRef,
                    ownerType,
                    previous == null ? new TameworkOwnerComponent(null, null) : previous.clone()
            );
        } catch (RuntimeException | LinkageError ignored) {
            // The prepared operation remains recoverable; startup reconciliation will quarantine it.
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
}
