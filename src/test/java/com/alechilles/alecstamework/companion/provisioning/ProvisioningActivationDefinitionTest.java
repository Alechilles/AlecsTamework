package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Typed payload and lifecycle contract tests for initial activation. */
class ProvisioningActivationDefinitionTest {
    private static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("test:activation", "profile");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000098");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("10000000-0000-0000-0000-000000000098");
    private static final long NOW = -4_000;

    @Test
    void timedActivationRoundTripsAndProducesFinalFenceRevision() {
        ProvisioningActivationRequest request = request(true);

        assertEquals(-12.5, request.placement().x());
        assertEquals(NOW, request.requestedAtMs());
        assertEquals(3,
                ProvisioningActivationDefinition.INSTANCE.payloadVersion());
        assertEquals(
                request,
                ProvisioningActivationDefinition.INSTANCE.decode(
                        ProvisioningActivationDefinition.INSTANCE.encode(
                                request
                        )
                )
        );
        assertEquals(new LifecycleRevision(2),
                request.finalLifecycle().revision());
        assertEquals(LifecycleState.ACTIVE,
                request.finalLifecycle().state());
    }

    @Test
    void activationRejectsAProjectionWithTheWrongSnapshotKind() {
        ProvisioningActivationRequest valid = request(false);
        String payload = valid.fullState().payloadJson();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProvisioningActivationRequest(
                        valid.origin(),
                        valid.groupAdmission(),
                        valid.targetAlias(),
                        valid.expectedRoleId(),
                        new SnapshotCodecRegistry.EncodedSnapshot(
                                new SnapshotKind("not_full_state"),
                                CompanionFullStateProjection.VERSION,
                                payload,
                                Sha256Hash.ofUtf8(payload)
                        ),
                        valid.placement(),
                        valid.spawnReceiptKey(),
                        null,
                        valid.requestedAtMs()
                )
        );
    }

    @Test
    void activationCodecRejectsAFullStateHashMismatch() {
        JsonObject encoded = JsonParser.parseString(
                ProvisioningActivationDefinition.INSTANCE.encode(
                        request(false)
                )
        ).getAsJsonObject();
        encoded.getAsJsonObject("fullState").addProperty(
                "payloadHash",
                "0000000000000000000000000000000000000000000000000000000000000000"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ProvisioningActivationDefinition.INSTANCE.decode(
                        encoded.toString()
                )
        );
    }

    @Test
    void aDifferentOwnerWorldOrPartialTimedSessionIsRejected() {
        ProvisioningActivationRequest valid = request(true);
        CompanionLifecycle wrongWorld = lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-b"
                ),
                1,
                "world-b"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProvisioningActivationRequest(
                        ORIGIN,
                        new PopulationGroupTransitionAdmissionRequest(
                                valid.groupAdmission().before(),
                                wrongWorld,
                                1,
                                7,
                                valid.groupAdmission().policies(),
                                NOW
                        ),
                        ALIAS,
                        "Mini",
                        projection(ALIAS),
                        placement("world-b"),
                        "receipt",
                        valid.timedActivation(),
                        NOW
                )
        );
        TimedSummonLease partial = new TimedSummonLease(
                ORIGIN.profileId(),
                1,
                new TimedSummonSessionId(new UUID(0, 99)),
                5_000L,
                null,
                policy(),
                Set.of(),
                NOW,
                NOW,
                NOW
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProvisioningActivationRequest(
                        ORIGIN,
                        valid.groupAdmission(),
                        ALIAS,
                        "Mini",
                        projection(ALIAS),
                        placement("world-a"),
                        "receipt",
                        new TimedSummonActivation(
                                valid.timedActivation().familyKey(),
                                valid.timedActivation().slotId(),
                                1,
                                partial
                        ),
                        NOW
                )
        );
    }

    private ProvisioningActivationRequest request(boolean timed) {
        CompanionLifecycle before = lifecycle(
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        ORIGIN.stableKey()
                ),
                0,
                "world-a"
        );
        CompanionLifecycle after = lifecycle(
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                ),
                1,
                "world-a"
        );
        return new ProvisioningActivationRequest(
                ORIGIN,
                new PopulationGroupTransitionAdmissionRequest(
                        before,
                        after,
                        1,
                        7,
                        List.of(new PopulationGroupPolicy(
                                "mod:mini",
                                PopulationGroupScope.GLOBAL,
                                2,
                                1,
                                7
                        )),
                        NOW
                ),
                ALIAS,
                "Mini",
                projection(ALIAS),
                placement("world-a"),
                "receipt",
                timed ? timed() : null,
                NOW
        );
    }

    private CompanionSpawnPlacement placement(String world) {
        return new CompanionSpawnPlacement(
                world, -12.5, -63.05, -4.5,
                -0.25f, -1.5f, -0.5f
        );
    }

    private SnapshotCodecRegistry.EncodedSnapshot projection(
            NpcAlias alias
    ) {
        String payload = "{\"npcUuid\":\"" + alias + "\"}";
        return new SnapshotCodecRegistry.EncodedSnapshot(
                CompanionFullStateProjection.KIND,
                CompanionFullStateProjection.VERSION,
                payload,
                Sha256Hash.ofUtf8(payload)
        );
    }

    private TimedSummonActivation timed() {
        return new TimedSummonActivation(
                new CommandFamilyKey(OWNER, "summon"),
                new CommandRosterSlotId(new UUID(0, 98)),
                1,
                new TimedSummonLease(
                        ORIGIN.profileId(),
                        1,
                        new TimedSummonSessionId(new UUID(0, 98)),
                        10_000L,
                        null,
                        policy(),
                        Set.of(),
                        NOW,
                        NOW,
                        NOW
                )
        );
    }

    private TimedSummonPolicy policy() {
        return new TimedSummonPolicy(
                "role:timed",
                7L,
                10_000,
                2_000,
                true,
                List.of(5_000L)
        );
    }

    private CompanionLifecycle lifecycle(
            LifecycleState state,
            LifecycleLocation location,
            long revision,
            String ownerWorld
    ) {
        return new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                state,
                location,
                new LifecycleRevision(revision),
                null,
                state == LifecycleState.PROVISIONED_DORMANT
                        ? -5_000
                        : NOW,
                ReconciliationGeneration.INITIAL,
                null,
                ownerWorld
        );
    }
}
