package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
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
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink.RecallFailure;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Guards durable reconstruction after an exact explicit Recall is exhausted. */
class MissingUnloadedRecallRecoveryAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID TOOL = UUID.fromString(
            "40000000-0000-0000-0000-000000000001"
    );
    private final MissingUnloadedRecallRecoveryAuthor author =
            new MissingUnloadedRecallRecoveryAuthor();

    @Test
    void authorsRestorableLostStateFromDurableCompanionFacts() {
        CompanionDormantTransitionRequest request = author.author(
                profile(LifecycleState.UNLOADED, false),
                failure()
        );

        assertNotNull(request);
        assertEquals(LifecycleState.LOST, request.targetState());
        assertEquals(
                DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED,
                request.source().kind()
        );
        assertEquals(ALIAS, request.source().sourceAlias());
        assertEquals("world-a", request.source().sourceWorldKey());
        assertTrue(request.snapshot().payloadHash().matchesUtf8(
                request.snapshot().payloadJson()
        ));

        CoopResidentStateSnapshot state = new FullStateSnapshotCodecAdapter(
                TameworkSnapshotCodecs.LOST,
                2
        ).decode(request.snapshot().payloadJson());
        assertEquals(ALIAS.value(), state.npcUuid());
        assertEquals("role-a", state.roleId());
        assertEquals(OWNER.value(), state.owner().getOwnerId());
        assertEquals("Owner", state.owner().getOwnerName());
        assertTrue(state.tamed().isTamed());
        assertEquals("Buddy", state.npcName().getName());
        assertEquals(OWNER.value(), state.commandLinks().getOwnerId());
        assertArrayEquals(
                new String[]{TOOL.toString()},
                state.commandLinks().getToolIds()
        );
        CompanionProfileReadModel before = profile(
                LifecycleState.UNLOADED, false
        );
        CompanionProfileReadModel lost = new CompanionProfileReadModel(
                before.identity(),
                null,
                new CompanionLifecycle(
                        PROFILE,
                        OWNER,
                        LifecycleState.LOST,
                        LifecycleLocation.none(),
                        before.lifecycle().revision().next(),
                        null,
                        failure().failedAtMs(),
                        before.lifecycle().lastReconciledGeneration(),
                        null,
                        before.lifecycle().ownerWorldKey()
                ),
                before.toolLinks(),
                List.of(request.snapshot()),
                null
        );
        assertInstanceOf(
                TameworkRestorationSnapshotResolver.Resolution.Resolved.class,
                new TameworkRestorationSnapshotResolver().resolve(
                        lost, request.snapshot()
                )
        );
    }

    @Test
    void rejectsLiveOrQuarantinedProfiles() {
        assertNull(author.author(profile(LifecycleState.ACTIVE, false), failure()));
        assertNull(author.author(profile(LifecycleState.UNLOADED, true), failure()));
    }

    private CompanionProfileReadModel profile(
            LifecycleState state,
            boolean quarantined
    ) {
        String metadata = """
                {"owner_name":"Owner","custom_name":"Buddy","tamed":true}
                """.trim();
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Buddy",
                "role-a",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world-a",
                -10_000,
                -9_000,
                -9_000,
                4
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS,
                PROFILE,
                0,
                CompanionAlias.State.CURRENT,
                null,
                -9_000,
                null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                state,
                state == LifecycleState.ACTIVE
                        ? LifecycleLocation.liveEntity(ALIAS.toString(), "world-a")
                        : LifecycleLocation.none(),
                new LifecycleRevision(3),
                null,
                -8_000,
                new ReconciliationGeneration(5),
                quarantined
                        ? com.alechilles.alecstamework.persistence.incidents
                        .IncidentId.create()
                        : null,
                "owner-world"
        );
        CompanionToolLink link = new CompanionToolLink(
                PROFILE,
                TOOL,
                "command",
                -9_000,
                -9_000
        );
        return new CompanionProfileReadModel(
                identity,
                alias,
                lifecycle,
                List.of(link),
                List.of(),
                null
        );
    }

    private RecallFailure failure() {
        return new RecallFailure(
                ALIAS.value(),
                OWNER.value(),
                -7_000,
                -6_000
        );
    }
}
