package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the old missing-component-equals-removed false positive. */
class ManagedCoopRemovalEvidenceTest {

    @Test
    void unloadedAndAmbiguousEvidenceNeverConfirmRemoval() {
        var unloaded = classify(false, false, false, false, false,
                false, false, false, false, false);
        var unknownBlock = classify(true, false, true, false, false,
                false, false, false, false, false);
        var invalidRef = classify(true, true, true, true, false,
                false, false, false, false, false);
        var wrongBlockInfo = classify(true, true, true, true, true,
                false, false, false, false, false);

        assertEquals(ManagedCoopRemovalEvidence.Status.DEFERRED_UNLOADED,
                unloaded.status());
        assertEquals(ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS,
                unknownBlock.status());
        assertEquals(ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS,
                invalidRef.status());
        assertEquals(ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS,
                wrongBlockInfo.status());
        assertFalse(unloaded.confirmedRemoved());
        assertFalse(wrongBlockInfo.confirmedRemoved());
    }

    @Test
    void matchingBlockWithoutComponentIsDeferredInsteadOfReleased() {
        var noEntity = classify(true, true, true, false, false,
                false, false, true, false, false);
        var noCoopComponent = classify(true, true, true, true, true,
                true, false, true, false, false);

        assertEquals(
                ManagedCoopRemovalEvidence.Status.DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING,
                noEntity.status());
        assertEquals(
                ManagedCoopRemovalEvidence.Status.DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING,
                noCoopComponent.status());
        assertFalse(noEntity.permitsDisabledRelease());
        assertFalse(noCoopComponent.permitsDisabledRelease());
    }

    @Test
    void onlyExactManagedOrKnownReplacementEvidenceCanProject() {
        var exactManaged = classify(true, true, true, true, true,
                true, true, true, true, true);
        var nonCoopReplacement = classify(true, true, true, false, false,
                false, false, false, false, false);
        var differentTypedCoop = classify(true, true, true, true, true,
                true, true, false, false, false);
        var contradictory = classify(true, true, true, true, true,
                true, true, true, false, false);
        var missingAsset = ManagedCoopRemovalEvidence.classify(
                true, true, true, true, true, true,
                true, false, true, false, false, false, 7);

        assertTrue(exactManaged.exactManagedCoop());
        assertTrue(nonCoopReplacement.confirmedRemoved());
        assertTrue(differentTypedCoop.confirmedRemoved());
        assertEquals(ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS,
                contradictory.status());
        assertEquals(ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS,
                missingAsset.status());
    }

    private static ManagedCoopRemovalEvidence.Result classify(
            boolean chunkLoaded,
            boolean blockTypeKnown,
            boolean componentTypesAvailable,
            boolean blockReferencePresent,
            boolean blockReferenceValid,
            boolean exactTypedIdentity,
            boolean coopComponentPresent,
            boolean matchingBlockType,
            boolean matchingCoopAsset,
            boolean exactManagedContext) {
        return ManagedCoopRemovalEvidence.classify(
                chunkLoaded, blockTypeKnown, componentTypesAvailable,
                blockReferencePresent, blockReferenceValid, exactTypedIdentity,
                coopComponentPresent, coopComponentPresent, true,
                matchingBlockType, matchingCoopAsset,
                exactManagedContext, 7);
    }
}
