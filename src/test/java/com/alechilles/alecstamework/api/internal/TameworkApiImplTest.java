package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkApiImplTest {
    private static final ProfileId PROFILE_ID = ProfileId.parse(
            "30000000-0000-0000-0000-000000000001");
    private static final UUID NPC_UUID = UUID.fromString(
            "30000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_UUID = UUID.fromString(
            "30000000-0000-0000-0000-000000000003");
    private static final UUID TOOL_UUID = UUID.fromString(
            "30000000-0000-0000-0000-000000000004");
    @TempDir
    Path tempDir;
    @Test
    void replacementCompositionExposesStableReleasedApiContracts()
            throws Exception {
        AtomicLong clock = new AtomicLong(-10_000L);
        TameworkEventBus events = new TameworkEventBus(null);
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration(clock, events))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var created = persistence.facades().operations().mutateProfile(
                    OperationId.create(),
                    new IdempotencyKey("api-profile-adoption"),
                    profileAdoption(clock.incrementAndGet())
            );
            assertTrue(created.accepted());
            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    created.completion().toCompletableFuture()
                            .get(5, TimeUnit.SECONDS).status()
            );

            try (TameworkApiImpl api = ReplacementTameworkApiFactory.create(
                    persistence,
                    Duration.ofSeconds(5),
                    clock::incrementAndGet,
                    events,
                    null,
                    new InteractionExtensionRegistry(null),
                    new TraitEffectRegistry(null, null),
                    new SimpleClaimsTamedDamagePolicy()
            )) {
                assertEquals("0.10.0", api.getApiVersion());
                assertEquals(expectedCapabilities(), api.getCapabilities());

                assertEquals(
                        PROFILE_ID.toString(),
                        api.profiles().resolveProfileId(NPC_UUID).orElseThrow()
                );
                NpcProfileView profile = api.profiles()
                        .getByProfileId(PROFILE_ID.toString())
                        .orElseThrow();
                assertEquals(OWNER_UUID, profile.ownerUuid());
                assertEquals("Owner A", profile.ownerName());
                assertEquals("Custom A", profile.customName());
                assertTrue(profile.tamed());
                assertTrue(api.profiles().getByNpcUuid(NPC_UUID).isPresent());

                assertTrue(api.progression()
                        .getByProfileId(PROFILE_ID.toString()).isEmpty());
                assertEquals(
                        ProgressionMutationStatus.NOT_LOADED,
                        api.progression().setHappiness(
                                PROFILE_ID.toString(), 75.0
                        ).status()
                );
                assertEquals(
                        ProgressionMutationStatus.INVALID_ARGUMENT,
                        api.progression().setNeeds(
                                PROFILE_ID.toString(), null, null
                        ).status()
                );
                assertEquals(
                        ProgressionMutationStatus.INVALID_ARGUMENT,
                        api.progression().setHappiness(
                                PROFILE_ID.toString(), Double.NaN
                        ).status()
                );
                assertEquals(
                        ProgressionMutationStatus.INVALID_ARGUMENT,
                        api.progression().applyHappinessDelta(
                                PROFILE_ID.toString(),
                                Double.POSITIVE_INFINITY
                        ).status()
                );
                assertEquals(
                        ProgressionMutationStatus.INVALID_ARGUMENT,
                        api.progression().setTraits(
                                PROFILE_ID.toString(), null
                        ).status()
                );
                assertEquals(
                        ProgressionMutationStatus.INVALID_ARGUMENT,
                        api.progression().setStoredAttachments(
                                PROFILE_ID.toString(), null
                        ).status()
                );
                assertEquals(
                        ProgressionMutationStatus.NOT_FOUND,
                        api.progression().setHappiness(
                                UUID.randomUUID(), 10.0
                        ).status()
                );

                assertTrue(api.policies()
                        .getOwnershipByProfileId(PROFILE_ID.toString())
                        .isPresent());
                assertTrue(api.policies().isOwner(
                        PROFILE_ID.toString(), OWNER_UUID
                ));
                assertEquals(
                        Set.of(TOOL_UUID.toString()),
                        api.commandLinks().listLinkedToolIds(
                                PROFILE_ID.toString()
                        )
                );
                assertFalse(api.commandLinks()
                        .hasHomePosition(PROFILE_ID.toString()));

                assertTrue(api.profileData().put(
                        PROFILE_ID.toString(),
                        "example.plugin",
                        "state",
                        "{\"level\":2}"
                ));
                awaitProfileData(api, "{\"level\":2}");
                assertFalse(api.profileData().put(
                        PROFILE_ID.toString(),
                        "example.plugin",
                        "state",
                        "not json"
                ));
                assertFalse(api.profileData().put(
                        PROFILE_ID.toString(),
                        "Alechilles:Tamework",
                        "state",
                        "{\"level\":3}"
                ));

                assertNotNull(api.traitEffects());
                assertNotNull(api.configs().getGlobalConfig());
                assertNotNull(api.diagnostics().getPersistenceDiagnostics());
            }
        } finally {
            events.close();
        }
    }
    @Test
    void commandLinksReadCanonicalSnapshotProjectionWithoutLegacyStore() {
        NpcProfileView profile = new NpcProfileView(
                PROFILE_ID.toString(),
                NPC_UUID,
                OWNER_UUID,
                "Owner A",
                "Mob_Test",
                "Display A",
                "Custom A",
                true,
                null,
                null,
                Set.of(TOOL_UUID.toString()),
                Set.of("capture"),
                -10L
        );
        NpcProfilesApi profiles = snapshotProfiles(profile);
        try (TameworkApiImpl api = new TameworkApiImpl(
                profiles,
                emptyProfileData(),
                emptyDiagnostics(),
                new TameworkEventBus(null),
                null,
                new InteractionExtensionRegistry(null),
                new TraitEffectRegistry(null, profiles),
                new SimpleClaimsTamedDamagePolicy()
        )) {
            assertTrue(api.commandLinks()
                    .getByProfileId(PROFILE_ID.toString()).isPresent());
            assertTrue(api.commandLinks().getByNpcUuid(NPC_UUID).isPresent());
            assertEquals(
                    Set.of(TOOL_UUID.toString()),
                    api.commandLinks().listLinkedToolIds(
                            PROFILE_ID.toString()
                    )
            );
            assertEquals(
                    3.0,
                    api.commandLinks().getHomePosition(
                            PROFILE_ID.toString()
                    ).orElseThrow().x()
            );
            assertEquals(
                    9.0,
                    api.commandLinks().getByProfileId(
                            PROFILE_ID.toString()
                    ).orElseThrow().lastKnownPosition().x()
            );
        }
    }
    @Test
    void buildsUsefulRoleIdCandidatesFromNonCanonicalInputs() {
        assertEquals(
                List.of("npcRoles.Tamed_Cow.name", "Tamed_Cow"),
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates(
                        " npcRoles.Tamed_Cow.name "
                ))
        );
        assertEquals(
                List.of(
                        "Server/NPC/Roles/Creature/Livestock/Tamed/Tamed_Cow.json",
                        "Server/NPC/Roles/Creature/Livestock/Tamed/Tamed_Cow",
                        "Tamed_Cow"
                ),
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates(
                        "Server/NPC/Roles/Creature/Livestock/Tamed/Tamed_Cow.json"
                ))
        );
        assertEquals(
                List.of("AnimalHusbandry:Cow", "Cow"),
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates(
                        "AnimalHusbandry:Cow"
                ))
        );
        assertEquals(
                List.of("Creature.Livestock.Cow", "Cow"),
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates(
                        "Creature.Livestock.Cow"
                ))
        );
    }
    private PublicPersistenceRuntimeConfiguration configuration(
            AtomicLong clock,
            TameworkEventBus events
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "replacement-api-impl-test",
                clock::incrementAndGet,
                (claim, operation) -> confirmed("refund"),
                events::publishProfileChanged,
                boundaries(),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }
    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }
    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }
    private CompanionProfileMutation.AdoptLive profileAdoption(long now) {
        String metadata = """
                {"owner_name":"Owner A","custom_name":"Custom A","tamed":true}
                """.trim();
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE_ID,
                "Display A",
                "Mob_Test",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                now,
                now,
                now,
                0L
        );
        return new CompanionProfileMutation.AdoptLive(
                identity,
                new NpcAlias(NPC_UUID),
                new OwnerId(OWNER_UUID),
                "world",
                List.of(new CompanionToolLink(
                        PROFILE_ID,
                        TOOL_UUID,
                        "command",
                        now,
                        now
                )),
                now
        );
    }

    private EnumSet<TameworkApiCapability> expectedCapabilities() {
        return EnumSet.of(
                TameworkApiCapability.PROFILES,
                TameworkApiCapability.COMMAND_LINKS,
                TameworkApiCapability.PROGRESSION,
                TameworkApiCapability.PROGRESSION_MUTATIONS,
                TameworkApiCapability.POLICY,
                TameworkApiCapability.INTERACTION_EXTENSIONS,
                TameworkApiCapability.TRAIT_EFFECTS,
                TameworkApiCapability.PROFILE_DATA,
                TameworkApiCapability.EVENTS,
                TameworkApiCapability.COMPANION_XP_EVENTS,
                TameworkApiCapability.CONFIG_READ,
                TameworkApiCapability.DIAGNOSTICS,
                TameworkApiCapability.PROFILE_DATA_TRANSACTIONS
        );
    }

    private void awaitProfileData(TameworkApiImpl api, String expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (api.profileData().get(
                    PROFILE_ID.toString(),
                    "example.plugin",
                    "state"
            ).filter(expected::equals).isPresent()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Profile data did not reach expected state");
    }

    private NpcProfilesApi snapshotProfiles(NpcProfileView profile) {
        return new NpcProfilesApi() {
            @Override
            public Optional<String> resolveProfileId(UUID npcUuid) {
                return NPC_UUID.equals(npcUuid)
                        ? Optional.of(PROFILE_ID.toString())
                        : Optional.empty();
            }

            @Override
            public Optional<NpcProfileView> getByProfileId(String profileId) {
                return PROFILE_ID.toString().equals(profileId)
                        ? Optional.of(profile)
                        : Optional.empty();
            }

            @Override
            public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
                return NPC_UUID.equals(npcUuid)
                        ? Optional.of(profile)
                        : Optional.empty();
            }

            @Override
            public Optional<String> getActiveSnapshot(
                    String profileId,
                    String snapshotType
            ) {
                if (!PROFILE_ID.toString().equals(profileId)
                        || !"capture".equals(snapshotType)) {
                    return Optional.empty();
                }
                return Optional.of("""
                        {
                          "lastKnownPosition":{"x":9.0,"y":8.0,"z":7.0},
                          "homePosition":{"x":3.0,"y":4.0,"z":5.0}
                        }
                        """);
            }

            @Override
            public Set<String> listActiveSnapshotTypes(String profileId) {
                return PROFILE_ID.toString().equals(profileId)
                        ? Set.of("capture")
                        : Set.of();
            }
        };
    }

    private ProfileDataApi emptyProfileData() {
        return new ProfileDataApi() {
            @Override
            public Optional<String> get(
                    String profileId,
                    String namespace,
                    String key
            ) {
                return Optional.empty();
            }

            @Override
            public Map<String, String> list(
                    String profileId,
                    String namespace
            ) {
                return Map.of();
            }

            @Override
            public boolean put(
                    String profileId,
                    String namespace,
                    String key,
                    String jsonPayload
            ) {
                return false;
            }

            @Override
            public boolean delete(
                    String profileId,
                    String namespace,
                    String key
            ) {
                return false;
            }
        };
    }

    private DiagnosticsApi emptyDiagnostics() {
        return () -> new PersistenceDiagnosticsView(
                "",
                0L,
                0L,
                0L,
                0L,
                new PersistenceDiagnosticsView.QueueMetricsView(
                        0, 0, 0, 0L, 0L, 0L, 0L,
                        0.0, 0.0, 0.0, null, 0L
                ),
                new PersistenceDiagnosticsView.HealthView(
                        "UNAVAILABLE", null, 0L
                )
        );
    }
}
