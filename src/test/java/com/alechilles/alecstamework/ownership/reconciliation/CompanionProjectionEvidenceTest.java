package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the schema-neutral saved projection evidence codec. */
class CompanionProjectionEvidenceTest {
    @Test
    void fingerprintIsDeterministicLengthSafeAndCoversEveryMarkerField() {
        UUID source = new UUID(0L, 11L);
        String fingerprint = fingerprint("profile-ab", "operation-c", "RECOVERY", "slot", source, 4L);

        assertEquals(fingerprint,
                fingerprint("profile-ab", "operation-c", "RECOVERY", "slot", source, 4L));
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertNotEquals(
                fingerprint("ab", "c", "RECOVERY", null, null, 1L),
                fingerprint("a", "bc", "RECOVERY", null, null, 1L)
        );
        assertNotEquals(fingerprint,
                fingerprint("profile-other", "operation-c", "RECOVERY", "slot", source, 4L));
        assertNotEquals(fingerprint,
                fingerprint("profile-ab", "operation-other", "RECOVERY", "slot", source, 4L));
        assertNotEquals(fingerprint,
                fingerprint("profile-ab", "operation-c", "BREEDING_CHILD", "slot", source, 4L));
        assertNotEquals(fingerprint,
                fingerprint("profile-ab", "operation-c", "RECOVERY", null, source, 4L));
        assertNotEquals(fingerprint,
                fingerprint("profile-ab", "operation-c", "RECOVERY", "slot", null, 4L));
        assertNotEquals(fingerprint,
                fingerprint("profile-ab", "operation-c", "RECOVERY", "slot", source, 5L));
    }

    @Test
    void suffixRoundTripsBothEntityIdentities() {
        UUID componentUuid = new UUID(0L, 21L);
        UUID legacyUuid = new UUID(0L, 22L);
        String fingerprint = fingerprint("profile", "operation", "RECOVERY", null, null, 1L);

        String key = CompanionProjectionEvidence.appendToEvidenceKey(
                "world/default/entity", fingerprint, componentUuid, legacyUuid
        );
        CompanionProjectionEvidence.ProjectionObservation parsed =
                CompanionProjectionEvidence.parseEvidenceKey(key);

        assertEquals(fingerprint, parsed.fingerprint());
        assertEquals(componentUuid, parsed.componentUuid());
        assertEquals(legacyUuid, parsed.legacyNpcUuid());
        assertEquals(false, parsed.deathObserved());

        String deadKey = CompanionProjectionEvidence.appendToEvidenceKey(
                "world/default/corpse", fingerprint, componentUuid, legacyUuid, true
        );
        assertTrue(CompanionProjectionEvidence.parseEvidenceKey(deadKey).deathObserved());

        String legacyV1 = "legacy::tamework-projection-v1:" + fingerprint
                + ":" + componentUuid + ":" + legacyUuid;
        assertEquals(false,
                CompanionProjectionEvidence.parseEvidenceKey(legacyV1).deathObserved());
    }

    @Test
    void codecRejectsMalformedOrAmbiguousSuffixes() {
        String fingerprint = fingerprint("profile", "operation", "RECOVERY", null, null, 1L);
        String valid = CompanionProjectionEvidence.appendToEvidenceKey(
                "base", fingerprint, null, null
        );

        assertNull(CompanionProjectionEvidence.parseEvidenceKey(null));
        assertNull(CompanionProjectionEvidence.parseEvidenceKey("plain-evidence-key"));
        assertNull(CompanionProjectionEvidence.parseEvidenceKey(valid + ":trailing"));
        assertNull(CompanionProjectionEvidence.parseEvidenceKey(valid.replace(fingerprint,
                fingerprint.toUpperCase())));
        assertNull(CompanionProjectionEvidence.parseEvidenceKey(valid.replace(":~:~", ":bad:~")));
        assertNull(CompanionProjectionEvidence.parseEvidenceKey(
                valid.substring(0, valid.length() - 1) + "2"
        ));
        assertNull(CompanionProjectionEvidence.parseEvidenceKey(
                "base" + valid.substring(valid.indexOf("::tamework")) + valid.substring(valid.indexOf("::tamework"))
        ));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProjectionEvidence.appendToEvidenceKey("base", "bad", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> CompanionProjectionEvidence.appendToEvidenceKey(valid, fingerprint, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                fingerprint(" profile", "operation", "RECOVERY", null, null, 1L));
        assertThrows(IllegalArgumentException.class, () ->
                fingerprint("profile", "operation", "RECOVERY", " ", null, 1L));
    }

    @Test
    void populationEvidenceValidatesProjectionKindAgainstCodecSuffix() {
        UUID identity = new UUID(0L, 31L);
        String fingerprint = fingerprint("profile", "operation", "RECOVERY", null, null, 1L);
        String key = CompanionProjectionEvidence.appendToEvidenceKey(
                "saved-marker", fingerprint, identity, identity
        );

        assertThrows(IllegalArgumentException.class, () -> projection("plain-key", identity));
        assertThrows(IllegalArgumentException.class, () -> new CompanionPopulationEvidence(
                key,
                identity,
                null,
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                "default",
                "default",
                0,
                0,
                "test"
        ));
        assertThrows(IllegalArgumentException.class, () -> projection(
                key.substring(0, key.length() - 1), identity
        ));
    }

    private static CompanionPopulationEvidence projection(String key, UUID identity) {
        return new CompanionPopulationEvidence(
                key,
                identity,
                null,
                true,
                CompanionPopulationEvidence.Kind.PROJECTION_MARKER,
                "default",
                "default",
                0,
                0,
                "test"
        );
    }

    private static String fingerprint(
            String profileId,
            String operationId,
            String kind,
            String slotKey,
            UUID sourceNpcUuid,
            long generation
    ) {
        return CompanionProjectionEvidence.fingerprint(
                profileId, operationId, kind, slotKey, sourceNpcUuid, generation
        );
    }
}
