package com.alechilles.alecstamework.items;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fail-closed physical evidence for deciding whether a managed coop block still exists.
 *
 * <p>Only a loaded exact coordinate with a known replacement block can prove removal. Missing
 * chunks, invalid component identities, contradictory block evidence, and a matching block whose
 * coop component is temporarily missing remain deferred.</p>
 */
public final class ManagedCoopRemovalEvidence {
    public enum Status {
        EXACT_MANAGED_COOP,
        CONFIRMED_REMOVED,
        DEFERRED_UNLOADED,
        DEFERRED_AMBIGUOUS,
        DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING
    }

    /** Immutable physical result copied on the owning chunk-store thread. */
    public record Result(@Nonnull Status status,
                         int currentRotationIndex,
                         @Nullable String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
        }

        public boolean confirmedRemoved() {
            return status == Status.CONFIRMED_REMOVED;
        }

        public boolean exactManagedCoop() {
            return status == Status.EXACT_MANAGED_COOP;
        }

        public boolean permitsDisabledRelease() {
            return exactManagedCoop() || confirmedRemoved();
        }
    }

    private ManagedCoopRemovalEvidence() {
    }

    /**
     * Pure classification seam used by the Hytale reader and regression tests.
     *
     * @param exactTypedIdentity whether the block ref and BlockStateInfo prove the exact position
     */
    @Nonnull
    static Result classify(boolean chunkLoaded,
                           boolean blockTypeKnown,
                           boolean componentTypesAvailable,
                           boolean blockReferencePresent,
                           boolean blockReferenceValid,
                           boolean exactTypedIdentity,
                           boolean coopComponentPresent,
                           boolean coopAssetKnown,
                           boolean blockTypeIdentityAvailable,
                           boolean matchingBlockType,
                           boolean matchingCoopAsset,
                           boolean exactManagedContext,
                           int rotationIndex) {
        if (!chunkLoaded) {
            return result(Status.DEFERRED_UNLOADED, rotationIndex, "coop_chunk_unloaded");
        }
        if (!blockTypeKnown || !componentTypesAvailable) {
            return ambiguous(rotationIndex, "coop_block_type_or_component_type_unavailable");
        }
        if (!blockReferencePresent) {
            if (!blockTypeIdentityAvailable) {
                return ambiguous(rotationIndex, "coop_block_identity_lookup_unavailable");
            }
            return matchingBlockType
                    ? result(Status.DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING,
                            rotationIndex, "matching_coop_block_missing_component_entity")
                    : result(Status.CONFIRMED_REMOVED, rotationIndex,
                            "exact_coordinate_contains_non_coop_block");
        }
        if (!blockReferenceValid || !exactTypedIdentity) {
            return ambiguous(rotationIndex, "coop_block_component_identity_ambiguous");
        }
        if (!coopComponentPresent) {
            if (!blockTypeIdentityAvailable) {
                return ambiguous(rotationIndex, "coop_block_identity_lookup_unavailable");
            }
            return matchingBlockType
                    ? result(Status.DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING,
                            rotationIndex, "matching_coop_block_missing_coop_component")
                    : result(Status.CONFIRMED_REMOVED, rotationIndex,
                            "exact_typed_replacement_has_no_coop_component");
        }
        if (!coopAssetKnown) {
            return ambiguous(rotationIndex, "coop_component_asset_identity_unavailable");
        }
        if (matchingCoopAsset) {
            return exactManagedContext
                    ? result(Status.EXACT_MANAGED_COOP, rotationIndex, null)
                    : ambiguous(rotationIndex, "matching_coop_asset_not_exact_managed_context");
        }
        if (matchingBlockType) {
            return ambiguous(rotationIndex, "coop_block_type_and_asset_identity_conflict");
        }
        return result(Status.CONFIRMED_REMOVED, rotationIndex,
                "exact_typed_coordinate_contains_different_coop");
    }

    @Nonnull
    private static Result ambiguous(int rotationIndex, String detail) {
        return result(Status.DEFERRED_AMBIGUOUS, rotationIndex, detail);
    }

    @Nonnull
    private static Result result(Status status, int rotationIndex, @Nullable String detail) {
        return new Result(status, rotationIndex, detail);
    }
}
