package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip and invariant tests for versioned profile operation evidence. */
class CompanionProfileMutationCodecTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("10000000-0000-0000-0000-000000000001");

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
    void liveAdoptionRoundTripsAndDerivesExactInitialLifecycle() {
        CompanionProfileMutation.AdoptLive adoption =
                new CompanionProfileMutation.AdoptLive(
                        identity(0, "Companion", -9_000),
                        ALIAS,
                        OWNER,
                        " world ",
                        List.of(link(
                                "50000000-0000-0000-0000-000000000001",
                                -9_000
                        )),
                        -9_000
                );

        CompanionProfileMutation.AdoptLive decoded =
                (CompanionProfileMutation.AdoptLive) decode(encode(adoption));

        assertEquals(adoption, decoded);
        assertEquals(LifecycleState.ACTIVE, decoded.initialLifecycle().state());
        assertEquals(
                LifecycleLocation.liveEntity(ALIAS.toString(), "world"),
                decoded.initialLifecycle().location()
        );
        assertEquals(OWNER, decoded.initialLifecycle().ownerId());
        assertEquals(LifecycleRevision.INITIAL, decoded.initialLifecycle().revision());
    }

    @Test
    void liveAdoptionRejectsIdentityFromADifferentWorld() {
        CompanionIdentity identity = identity(0, "Companion", -9_000);

        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionProfileMutation.AdoptLive(
                        identity,
                        ALIAS,
                        OWNER,
                        "different-world",
                        List.of(),
                        -9_000
                )
        );
    }

    @Test
    void loadedReconciliationRoundTripsAndDerivesOneCanonicalAdvance() {
        CompanionProfileMutation.ReconcileLoaded reconciliation =
                new CompanionProfileMutation.ReconcileLoaded(
                        PROFILE,
                        new LifecycleRevision(3),
                        new ReconciliationGeneration(5),
                        ALIAS,
                        NpcAlias.parse(
                                "30000000-0000-0000-0000-000000000002"
                        ),
                        " loaded-world ",
                        -8_000
                );

        CompanionProfileMutation.ReconcileLoaded decoded =
                (CompanionProfileMutation.ReconcileLoaded) decode(
                        encode(reconciliation)
                );
        CompanionLifecycle current = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.UNRESOLVED,
                LifecycleLocation.unresolved(),
                new LifecycleRevision(3),
                null,
                -9_000,
                new ReconciliationGeneration(5),
                null,
                "owner-world"
        );

        assertEquals(reconciliation, decoded);
        CompanionLifecycle resolved = decoded.resolvedLifecycle(current);
        assertEquals(LifecycleState.ACTIVE, resolved.state());
        assertEquals(
                LifecycleLocation.liveEntity(
                        decoded.observedAlias().toString(),
                        "loaded-world"
                ),
                resolved.location()
        );
        assertEquals(new LifecycleRevision(4), resolved.revision());
        assertEquals(
                new ReconciliationGeneration(6),
                resolved.lastReconciledGeneration()
        );
        assertEquals("owner-world", resolved.ownerWorldKey());
    }

    @Test
    void unownedLiveAdoptionRoundTripsExplicitNullAndAcceptsMissingOwner() {
        CompanionProfileMutation.AdoptLive adoption =
                new CompanionProfileMutation.AdoptLive(
                        identity(0, "Wild Companion", -9_000),
                        ALIAS,
                        null,
                        "world",
                        List.of(),
                        -9_000
                );

        JsonObject encoded = JsonParser.parseString(encode(adoption))
                .getAsJsonObject();
        assertTrue(encoded.has("ownerId"));
        assertTrue(encoded.get("ownerId").isJsonNull());
        assertEquals(adoption, decode(encoded.toString()));
        assertNull(adoption.initialLifecycle().ownerId());
        assertNull(adoption.initialLifecycle().ownerWorldKey());

        encoded.remove("ownerId");
        assertEquals(adoption, decode(encoded.toString()));
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
