package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trip and invariant tests for durable extension operation evidence. */
class ProfileExtensionMutationCodecTest {
    private static final ProfileExtensionKey KEY = new ProfileExtensionKey(
            ProfileId.parse("20000000-0000-0000-0000-000000000001"),
            "example:integration",
            "state|with:separators"
    );

    @Test
    void operationDefinitionRoundTripsCanonicalJsonAndSignedTime() {
        ProfileExtensionMutation mutation = new ProfileExtensionMutation(
                KEY,
                ProfileExtensionMutationAction.PUT,
                4L,
                "{ \"enabled\" : true }",
                -9_000
        );

        String encoded = ProfileExtensionMutationDefinition.INSTANCE.encode(mutation);
        ProfileExtensionMutation decoded =
                ProfileExtensionMutationDefinition.INSTANCE.decode(encoded);

        assertEquals(mutation, decoded);
        assertEquals("{\"enabled\":true}", decoded.jsonPayload());
    }

    @Test
    void deleteRoundTripsNullableFields() {
        ProfileExtensionMutation mutation = new ProfileExtensionMutation(
                KEY,
                ProfileExtensionMutationAction.DELETE,
                null,
                null,
                -8_000
        );

        ProfileExtensionMutation decoded = ProfileExtensionMutationDefinition.INSTANCE.decode(
                ProfileExtensionMutationDefinition.INSTANCE.encode(mutation)
        );

        assertEquals(mutation, decoded);
        assertNull(decoded.expectedRevision());
        assertNull(decoded.jsonPayload());
    }

    @Test
    void durableOutcomeRoundTripsAllProjectionFields() {
        ProfileExtensionMutationOutcome outcome = new ProfileExtensionMutationOutcome(
                ProfileExtensionMutationOutcome.Status.APPLIED,
                KEY,
                5,
                "{\"enabled\":true}",
                -7_000
        );

        String encoded = ProfileExtensionMutationEventCodec.encode(outcome);

        assertEquals(
                outcome,
                ProfileExtensionMutationEventCodec.decode(
                        ProfileExtensionMutationEventCodec.VERSION,
                        encoded
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ProfileExtensionMutationEventCodec.decode(2, encoded)
        );
    }

    @Test
    void aggregateIdCannotCollideAtNamespaceBoundary() {
        ProfileExtensionKey other = new ProfileExtensionKey(
                KEY.profileId(),
                "example",
                "integration|state|with:separators"
        );

        org.junit.jupiter.api.Assertions.assertNotEquals(
                KEY.aggregateId(),
                other.aggregateId()
        );
    }
}
