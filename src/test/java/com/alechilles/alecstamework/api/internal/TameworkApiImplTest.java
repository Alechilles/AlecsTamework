package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void ownerCapV2CountsEveryRequestedSlotAndNeedsAWorldForAPerWorldCap() {
        OwnerPopulationCapService.Decision oneFree = OwnerPopulationCapService.evaluateResolved(
                5, 4, TwGlobalConfig.PerPlayerLimitScope.PER_WORLD);

        OwnerPopulationCapDecisionViewV2 one = TameworkApiImpl.ownerCapV2(
                new OwnerPopulationCapRequestV2(OWNER_UUID, "world", 1), true, oneFree);
        OwnerPopulationCapDecisionViewV2 two = TameworkApiImpl.ownerCapV2(
                new OwnerPopulationCapRequestV2(OWNER_UUID, "world", 2), true, oneFree);
        OwnerPopulationCapDecisionViewV2 noWorld = TameworkApiImpl.ownerCapV2(
                new OwnerPopulationCapRequestV2(OWNER_UUID, null, 1), true, oneFree);

        assertTrue(one.allowed());
        assertEquals(4L, one.committedCount());
        assertFalse(two.allowed());
        assertTrue(two.authoritative());
        assertEquals(1L, two.remainingHeadroom());
        assertFalse(noWorld.allowed());
        assertEquals(OwnerPopulationCapDecisionViewV2.Readiness.UNAVAILABLE, noWorld.readiness());
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
