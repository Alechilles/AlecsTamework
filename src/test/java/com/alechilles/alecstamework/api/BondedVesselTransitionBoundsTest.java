package com.alechilles.alecstamework.api;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BondedVesselTransitionBoundsTest {
    @Test
    void requestIdentityFieldsAcceptExactBoundsAndRejectOneCharacterOver() {
        BondedVesselTransitionContext context = context("item", "holder", "hotbar", "fingerprint");
        assertDoesNotThrow(() -> new BondedVesselTransitionRequest(
                text(BondedVesselTransitionRequest.MAX_CALLER_NAMESPACE_LENGTH),
                text(BondedVesselTransitionRequest.MAX_IDEMPOTENCY_KEY_LENGTH),
                UUID.randomUUID(), UUID.randomUUID(), 1L, 2L,
                BondedVesselTransition.SUMMON, context));

        assertThrows(IllegalArgumentException.class, () -> request(
                text(BondedVesselTransitionRequest.MAX_CALLER_NAMESPACE_LENGTH + 1),
                "key", context));
        assertThrows(IllegalArgumentException.class, () -> request(
                "namespace",
                text(BondedVesselTransitionRequest.MAX_IDEMPOTENCY_KEY_LENGTH + 1), context));
    }

    @Test
    void requestRejectsBlankIdentityAndNegativeAuthorityRevisions() {
        BondedVesselTransitionContext context = context("item", "holder", "hotbar", "fingerprint");

        assertThrows(IllegalArgumentException.class, () -> request(" ", "key", context));
        assertThrows(IllegalArgumentException.class, () -> request("namespace", " ", context));
        assertThrows(IllegalArgumentException.class, () -> request(-1L, 2L, context));
        assertThrows(IllegalArgumentException.class, () -> request(1L, -1L, context));
    }

    @Test
    void contextEvidenceFieldsAcceptExactBoundsAndRejectOneCharacterOver() {
        assertDoesNotThrow(() -> context(
                text(BondedVesselTransitionContext.MAX_SOURCE_ITEM_ID_LENGTH),
                text(BondedVesselTransitionContext.MAX_HOLDER_EVIDENCE_ID_LENGTH),
                text(BondedVesselTransitionContext.MAX_CONTAINER_PATH_LENGTH),
                text(BondedVesselTransitionContext.MAX_ITEM_FINGERPRINT_LENGTH)));

        assertOverBoundContext(0, BondedVesselTransitionContext.MAX_SOURCE_ITEM_ID_LENGTH);
        assertOverBoundContext(1, BondedVesselTransitionContext.MAX_HOLDER_EVIDENCE_ID_LENGTH);
        assertOverBoundContext(2, BondedVesselTransitionContext.MAX_CONTAINER_PATH_LENGTH);
        assertOverBoundContext(3, BondedVesselTransitionContext.MAX_ITEM_FINGERPRINT_LENGTH);
    }

    @Test
    void contextRejectsBlankOrNegativeExactSourceEvidence() {
        assertBlankContext(0);
        assertBlankContext(1);
        assertBlankContext(2);
        assertBlankContext(3);
        assertThrows(IllegalArgumentException.class, () -> context(-1, 1L, null,
                new PopulationAdmissionLocation("world", 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> context(0, -1L, null,
                new PopulationAdmissionLocation("world", 0, 0)));
    }

    @Test
    void transitionSpecificEvidenceFailsClosedWhenMissingOrContradictory() {
        PopulationAdmissionLocation destination = new PopulationAdmissionLocation("world", 0, 0);
        UUID npcUuid = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> request(
                BondedVesselTransition.SUMMON, context(0, 1L, null, null)));
        assertThrows(IllegalArgumentException.class, () -> request(
                BondedVesselTransition.SUMMON, context(0, 1L, npcUuid, destination)));
        assertThrows(IllegalArgumentException.class, () -> request(
                BondedVesselTransition.STORE, context(0, 1L, null, null)));
        assertThrows(IllegalArgumentException.class, () -> request(
                BondedVesselTransition.STORE, context(0, 1L, npcUuid, destination)));
        assertThrows(IllegalArgumentException.class, () -> request(
                BondedVesselTransition.REPAIR_DEAD_TO_STORED,
                context(0, 1L, npcUuid, null)));
        assertThrows(IllegalArgumentException.class, () -> request(
                BondedVesselTransition.REPAIR_DEAD_TO_STORED,
                context(0, 1L, null, destination)));
    }

    @Test
    void projectionInvalidationMayRetainTheCurrentGeneration() {
        BondedVesselBindingInvalidatedEvent event = assertDoesNotThrow(() ->
                new BondedVesselBindingInvalidatedEvent(
                        UUID.randomUUID(), UUID.randomUUID(), "profile", UUID.randomUUID(),
                        "config", 4L, 4L, BondedVesselState.ACTIVE,
                        BondedVesselProjectionStatus.MISSING, "sealed-item-evidence-missing",
                        true, 100L, 101L));
        assertEquals(4L, event.newGeneration());
    }

    private static BondedVesselTransitionRequest request(
            String namespace, String key, BondedVesselTransitionContext context) {
        return new BondedVesselTransitionRequest(
                namespace, key, UUID.randomUUID(), UUID.randomUUID(), 1L, 2L,
                BondedVesselTransition.SUMMON, context);
    }

    private static BondedVesselTransitionRequest request(
            long expectedGeneration,
            long expectedProfileRevision,
            BondedVesselTransitionContext context) {
        return new BondedVesselTransitionRequest(
                "namespace", "key", UUID.randomUUID(), UUID.randomUUID(), expectedGeneration,
                expectedProfileRevision, BondedVesselTransition.SUMMON, context);
    }

    private static BondedVesselTransitionRequest request(
            BondedVesselTransition transition,
            BondedVesselTransitionContext context) {
        return new BondedVesselTransitionRequest(
                "namespace", "key", UUID.randomUUID(), UUID.randomUUID(), 1L, 2L,
                transition, context);
    }

    private static BondedVesselTransitionContext context(
            String item, String holder, String container, String fingerprint) {
        return new BondedVesselTransitionContext(
                item, holder, container, 0, 1L, fingerprint, null,
                new PopulationAdmissionLocation("world", 0, 0));
    }

    private static BondedVesselTransitionContext context(
            int slot,
            long revision,
            UUID expectedNpcUuid,
            PopulationAdmissionLocation destination) {
        return new BondedVesselTransitionContext(
                "item", "holder", "hotbar", slot, revision, "fingerprint",
                expectedNpcUuid, destination);
    }

    private static void assertOverBoundContext(int field, int maxLength) {
        String[] values = {"item", "holder", "hotbar", "fingerprint"};
        values[field] = text(maxLength + 1);
        assertThrows(IllegalArgumentException.class,
                () -> context(values[0], values[1], values[2], values[3]));
    }

    private static void assertBlankContext(int field) {
        String[] values = {"item", "holder", "hotbar", "fingerprint"};
        values[field] = " ";
        assertThrows(IllegalArgumentException.class,
                () -> context(values[0], values[1], values[2], values[3]));
    }

    private static String text(int length) {
        return "x".repeat(length);
    }
}
