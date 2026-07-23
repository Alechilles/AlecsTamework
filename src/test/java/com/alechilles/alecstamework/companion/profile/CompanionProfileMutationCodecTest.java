package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trip and invariant tests for versioned profile operation evidence. */
class CompanionProfileMutationCodecTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");

    @Test
    void createRoundTripsIdentityLifecycleLinksAndSignedTimes() {
        CompanionProfileMutation.Create create = new CompanionProfileMutation.Create(
                identity(0, "Companion", -9_000),
                lifecycle(),
                List.of(link("50000000-0000-0000-0000-000000000001", -9_000)),
                -9_000
        );

        assertEquals(create, decode(encode(create)));
    }

    @Test
    void updateRoundTripsAndSortsCompleteToolSet() {
        CompanionToolLink later =
                link("50000000-0000-0000-0000-000000000002", -8_000);
        CompanionToolLink earlier =
                link("50000000-0000-0000-0000-000000000001", -8_000);
        CompanionProfileMutation.Update update = new CompanionProfileMutation.Update(
                identity(1, "Updated", -8_000),
                0,
                List.of(later, earlier),
                -8_000
        );

        CompanionProfileMutation.Update decoded =
                (CompanionProfileMutation.Update) decode(encode(update));
        assertEquals(update, decoded);
        assertEquals(earlier.toolId(), decoded.toolLinks().getFirst().toolId());
    }

    @Test
    void outcomeRoundTripsAndUnsupportedVersionFails() {
        CompanionProfileMutationOutcome outcome = new CompanionProfileMutationOutcome(
                CompanionProfileMutationOutcome.Status.UPDATED,
                PROFILE,
                2,
                -7_000
        );
        String encoded = CompanionProfileMutationEventCodec.encode(outcome);

        assertEquals(
                outcome,
                CompanionProfileMutationEventCodec.decode(
                        CompanionProfileMutationEventCodec.VERSION,
                        encoded
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CompanionProfileMutationEventCodec.decode(2, encoded)
        );
    }

    private String encode(CompanionProfileMutation mutation) {
        return CompanionProfileMutationDefinition.INSTANCE.encode(mutation);
    }

    private CompanionProfileMutation decode(String json) {
        return CompanionProfileMutationDefinition.INSTANCE.decode(json);
    }

    private CompanionIdentity identity(long revision, String name, long updatedAtMs) {
        String metadata = "{\"source\":\"test\"}";
        return new CompanionIdentity(
                PROFILE,
                name,
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -10_000,
                updatedAtMs,
                updatedAtMs,
                revision
        );
    }

    private CompanionLifecycle lifecycle() {
        return new CompanionLifecycle(
                PROFILE,
                OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -9_000,
                ReconciliationGeneration.INITIAL,
                null
        );
    }

    private CompanionToolLink link(String toolId, long updatedAtMs) {
        return new CompanionToolLink(
                PROFILE,
                UUID.fromString(toolId),
                "command",
                updatedAtMs,
                updatedAtMs
        );
    }
}
