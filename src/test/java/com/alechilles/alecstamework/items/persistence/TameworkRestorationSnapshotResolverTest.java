package com.alechilles.alecstamework.items.persistence;

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
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for one-time released-snapshot restoration normalization. */
class TameworkRestorationSnapshotResolverTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final UUID SOURCE = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID TOOL_A = UUID.fromString(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final UUID TOOL_B = UUID.fromString(
            "30000000-0000-0000-0000-000000000002"
    );

    private final SnapshotCodecRegistry codecs = TameworkSnapshotCodecs.create();
    private final TameworkRestorationSnapshotResolver resolver =
            new TameworkRestorationSnapshotResolver(codecs);

    @Test
    void deathV1MapsEveryPersistedComponentFactWithoutLiveDefaults() {
        String attachments = encoded("head") + "," + encoded("crest");
        String raw = """
                {
                  "ownerId":"10000000-0000-0000-0000-000000000001",
                  "ownerName":"Owner",
                  "roleId":"Tamework_Dead",
                  "tamed":true,
                  "customName":"Ember",
                  "homePosition":{"x":-4.5,"y":5.25,"z":-6.75},
                  "diedAtMs":-260,
                  "respawnAvailableAtMs":-1000,
                  "breedingConfigId":"breed",
                  "breedingHappiness":0.7,
                  "breedingCooldownUntilMs":-2000,
                  "happinessConfigId":"happy",
                  "happinessValue":0.8,
                  "happinessLastUpdateMs":-3000,
                  "traitsConfigId":"traits",
                  "traitsRollSeed":42,
                  "traitsValues":"[{\\"id\\":\\"friendly\\",\\"value\\":1.25}]",
                  "levelingConfigId":"level",
                  "levelingLevel":4,
                  "levelingTotalXp":50.0,
                  "talentsConfigId":"talents",
                  "talentsSpentPoints":1,
                  "purchasedTalentIds":"swift|steady",
                  "lifeStage":"Adult",
                  "lifeStageBornAtMs":-4000,
                  "lifeStageAdultAtMs":-5000,
                  "lifeStageGender":"Female",
                  "attachmentsConfigId":"attachments",
                  "attachmentsValues":"%s"
                }
                """.formatted(attachments);
        CompanionSnapshot source = snapshot(
                TameworkSnapshotCodecs.DEATH,
                1,
                raw,
                -9_001L
        );
        CompanionProfileReadModel profile = profile(
                source,
                LifecycleState.DEAD_REVIVABLE,
                "Tamework_Dead",
                "{\"owner_name\":\"Owner\",\"custom_name\":\"Ember\","
                        + "\"tamed\":true}",
                List.of(
                        link(TOOL_B, "death"),
                        link(TOOL_A, "command"),
                        link(TOOL_B, "command")
                )
        );

        RestorationProjection projection = resolved(
                resolver.resolve(profile, source)
        );
        CoopResidentStateSnapshot state = fullState(projection);

        assertEquals(SOURCE, state.npcUuid());
        assertEquals("tamework_dead", state.roleId());
        assertEquals(-9_001L, state.capturedAtMs());
        assertArrayEquals(
                new String[]{TOOL_A.toString(), TOOL_B.toString()},
                state.commandLinks().getToolIds()
        );
        assertEquals(-4.5, state.commandLinks().getHomeX());
        assertEquals("Owner", state.owner().getOwnerName());
        assertTrue(state.tamed().isTamed());
        assertEquals("Ember", state.npcName().getName());
        assertEquals(0L, state.npcName().getLastUpdatedMs());
        assertEquals(
                TameworkNpcNameComponent.NameSource.System,
                state.npcName().getSource()
        );
        assertEquals(0.8, state.happiness().getValue());
        assertEquals(-3_000L, state.happiness().getLastUpdateMs());
        assertFalse(state.breeding().isReady());
        assertEquals(-2_000L, state.breeding().getCooldownUntilMs());
        assertEquals(0L, state.breeding().getCooldownStartedAtMs());
        assertEquals(0.0, state.leveling().getCurrentXp());
        assertEquals(50.0, state.leveling().getTotalXp());
        assertEquals("friendly", state.traits().getTraitValues()[0].getId());
        assertArrayEquals(
                new String[]{"swift", "steady"},
                state.talents().getPurchasedTalentIds()
        );
        assertEquals(-4_000L, state.lifeStage().getBornAtMs());
        assertEquals("Female", state.lifeStage().getGender());
        assertEquals(
                "crest",
                state.attachments().getAttachmentIds().get("head")
        );
        assertNull(state.needs());
        assertNull(state.healthPercent());
        assertEquals(2, projection.fullState().payloadVersion());
    }

    @Test
    void lostV1UsesOnlyCanonicalIdentityAndPersistedMetadata() {
        CompanionSnapshot source = snapshot(
                TameworkSnapshotCodecs.LOST,
                1,
                """
                {
                  "homePosition":{"x":1,"y":2,"z":3},
                  "lastRelocationQueuedAtMs":-11,
                  "lostAtMs":-12,
                  "relocationRetryAttempts":2
                }
                """,
                -9_002L
        );
        CompanionProfileReadModel profile = profile(
                source,
                LifecycleState.LOST,
                "Tamework_Lost",
                "{\"owner_name\":\"Owner\",\"custom_name\":\"Nova\","
                        + "\"tamed\":true}",
                List.of(link(TOOL_A, "lost"))
        );

        CoopResidentStateSnapshot state = fullState(
                resolved(resolver.resolve(profile, source))
        );

        assertEquals(SOURCE, state.npcUuid());
        assertEquals("tamework_lost", state.roleId());
        assertEquals(-9_002L, state.capturedAtMs());
        assertEquals(3.0, state.commandLinks().getHomeZ());
        assertEquals("Owner", state.owner().getOwnerName());
        assertEquals("Nova", state.npcName().getName());
        assertTrue(state.tamed().isTamed());
        assertNull(state.happiness());
        assertNull(state.needs());
        assertNull(state.breeding());
        assertNull(state.leveling());
        assertNull(state.traits());
        assertNull(state.talents());
        assertNull(state.lifeStage());
        assertNull(state.attachments());
        assertNull(state.healthPercent());
    }

    @Test
    void completeDeathAndLostPayloadsRemainByteExact() {
        for (SnapshotKind kind : List.of(
                TameworkSnapshotCodecs.DEATH,
                TameworkSnapshotCodecs.LOST
        )) {
            CoopResidentStateSnapshot state = new CoopResidentStateSnapshot(
                    SOURCE,
                    null,
                    -1,
                    "tamework_exact",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    -77L
            );
            SnapshotCodecRegistry.EncodedSnapshot encoded = codecs.encode(
                    kind,
                    2,
                    CoopResidentStateSnapshot.class,
                    state
            );
            CompanionSnapshot source = snapshot(
                    kind,
                    2,
                    encoded.payloadJson(),
                    -77L
            );
            CompanionProfileReadModel profile = profile(
                    source,
                    kind.equals(TameworkSnapshotCodecs.DEATH)
                            ? LifecycleState.DEAD_REVIVABLE
                            : LifecycleState.LOST,
                    "Tamework_Exact",
                    null,
                    List.of()
            );

            RestorationProjection projection = resolved(
                    resolver.resolve(profile, source)
            );

            assertEquals(encoded.payloadJson(), projection.fullState().payloadJson());
            assertEquals(encoded.payloadHash(), projection.fullState().payloadHash());
        }
    }

    @Test
    void malformedNestedEvidenceAndPartialLostRecoveryFailClosed() {
        CompanionSnapshot malformed = snapshot(
                TameworkSnapshotCodecs.DEATH,
                1,
                "{\"roleId\":\"Tamework_Dead\","
                        + "\"traitsValues\":\"not-json\"}",
                -1L
        );
        TameworkRestorationSnapshotResolver.Resolution.Failed invalid =
                assertInstanceOf(
                        TameworkRestorationSnapshotResolver.Resolution.Failed.class,
                        resolver.resolve(
                                profile(
                                        malformed,
                                        LifecycleState.DEAD_REVIVABLE,
                                        "Tamework_Dead",
                                        null,
                                        List.of()
                                ),
                                malformed
                        )
                );
        assertEquals(
                TameworkRestorationSnapshotResolver.Failure.DECODE_FAILED,
                invalid.failure()
        );
        assertEquals("traitsValues", invalid.field());

        CompanionSnapshot partial = snapshot(
                TameworkSnapshotCodecs.LOST,
                1,
                "{\"replacementNpcUuid\":\""
                        + UUID.randomUUID() + "\"}",
                -2L
        );
        TameworkRestorationSnapshotResolver.Resolution.Failed conflict =
                assertInstanceOf(
                        TameworkRestorationSnapshotResolver.Resolution.Failed.class,
                        resolver.resolve(
                                profile(
                                        partial,
                                        LifecycleState.LOST,
                                        "Tamework_Lost",
                                        null,
                                        List.of()
                                ),
                                partial
                        )
                );
        assertEquals(
                TameworkRestorationSnapshotResolver.Failure.EVIDENCE_CONFLICT,
                conflict.failure()
        );
        assertEquals("legacyRecoveryEvidence", conflict.field());
    }

    @Test
    void missingRoleAndAliasOrConflictingOwnerAreExplicitFailures() {
        CompanionSnapshot lost = snapshot(
                TameworkSnapshotCodecs.LOST,
                1,
                "{}",
                1L
        );
        CompanionProfileReadModel missingRole = profile(
                lost,
                LifecycleState.LOST,
                null,
                null,
                List.of()
        );
        assertEquals(
                TameworkRestorationSnapshotResolver.Failure.ROLE_MISSING,
                failed(resolver.resolve(missingRole, lost)).failure()
        );

        CompanionSnapshot death = snapshot(
                TameworkSnapshotCodecs.DEATH,
                1,
                "{\"ownerId\":\"99999999-0000-0000-0000-000000000009\","
                        + "\"roleId\":\"Tamework_Dead\"}",
                2L
        );
        CompanionProfileReadModel conflict = profile(
                death,
                LifecycleState.DEAD_REVIVABLE,
                "Tamework_Dead",
                null,
                List.of()
        );
        TameworkRestorationSnapshotResolver.Resolution.Failed ownerFailure =
                failed(resolver.resolve(conflict, death));
        assertEquals(
                TameworkRestorationSnapshotResolver.Failure.EVIDENCE_CONFLICT,
                ownerFailure.failure()
        );
        assertEquals("ownerId", ownerFailure.field());

        CompanionProfileReadModel noAlias = new CompanionProfileReadModel(
                missingRole.identity(),
                null,
                missingRole.lifecycle(),
                missingRole.toolLinks(),
                missingRole.currentSnapshots(),
                null
        );
        assertEquals(
                TameworkRestorationSnapshotResolver.Failure.SOURCE_ALIAS_MISSING,
                failed(resolver.resolve(noAlias, lost)).failure()
        );
    }

    private CompanionProfileReadModel profile(
            CompanionSnapshot source,
            LifecycleState state,
            String roleId,
            String metadataJson,
            List<CompanionToolLink> toolLinks
    ) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Display",
                roleId,
                metadataJson,
                metadataJson == null ? null : Sha256Hash.ofUtf8(metadataJson),
                "world",
                -10L,
                -9L,
                -8L,
                0L
        );
        CompanionAlias alias = new CompanionAlias(
                new NpcAlias(SOURCE),
                PROFILE,
                0L,
                CompanionAlias.State.CURRENT,
                null,
                -7L,
                null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                new OwnerId(OWNER),
                state,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -6L,
                ReconciliationGeneration.INITIAL,
                null
        );
        return new CompanionProfileReadModel(
                identity,
                alias,
                lifecycle,
                toolLinks,
                List.of(source),
                null
        );
    }

    private CompanionToolLink link(UUID toolId, String type) {
        return new CompanionToolLink(PROFILE, toolId, type, -5L, -4L);
    }

    private CompanionSnapshot snapshot(
            SnapshotKind kind,
            int version,
            String payload,
            long createdAtMs
    ) {
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                kind,
                version,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                true,
                createdAtMs
        );
    }

    private RestorationProjection resolved(
            TameworkRestorationSnapshotResolver.Resolution resolution
    ) {
        return assertInstanceOf(
                TameworkRestorationSnapshotResolver.Resolution.Resolved.class,
                resolution
        ).projection();
    }

    private TameworkRestorationSnapshotResolver.Resolution.Failed failed(
            TameworkRestorationSnapshotResolver.Resolution resolution
    ) {
        return assertInstanceOf(
                TameworkRestorationSnapshotResolver.Resolution.Failed.class,
                resolution
        );
    }

    private CoopResidentStateSnapshot fullState(
            RestorationProjection projection
    ) {
        SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> decoded =
                assertInstanceOf(
                        SnapshotDecodeResult.Decoded.class,
                        codecs.decode(
                                projection.fullState(),
                                CoopResidentStateSnapshot.class
                        )
                );
        return decoded.value();
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
