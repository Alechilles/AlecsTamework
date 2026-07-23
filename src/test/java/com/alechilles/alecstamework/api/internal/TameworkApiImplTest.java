package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.PopulationGroupReconciliationView;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationDirection;
import com.alechilles.alecstamework.api.PersistenceMutationDomain;
import com.alechilles.alecstamework.api.PersistenceScopeKind;
import com.alechilles.alecstamework.api.PersistenceScopeReference;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import org.joml.Vector3d;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.persistence.sqlite.LegacyNpcProfilesApi;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;

import java.nio.file.Path;
import java.util.List;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkApiImplTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesVersionCapabilitiesProfilesEventsAndProfileData() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            TameworkEventBus bus = new TameworkEventBus(null);
            runtime.getNpcProfileRepository().setChangeObserver(bus);
            CommandLinkedNpcStateSnapshotService stateSnapshotService =
                    new CommandLinkedNpcStateSnapshotService(runtime.getNpcProfileRepository());
            TameworkApi api = LegacyTameworkApiFactory.create(
                    runtime,
                    bus,
                    stateSnapshotService,
                    new InteractionExtensionRegistry(null),
                    new TraitEffectRegistry(
                            null,
                            new LegacyNpcProfilesApi(
                                    runtime.getNpcProfileRepository()
                            )
                    )
            );

            assertEquals("0.9.0", api.getApiVersion());
            assertEquals(
                    EnumSet.of(
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
                            TameworkApiCapability.PERSISTENCE_RESILIENCE,
                            TameworkApiCapability.PROFILE_DATA_TRANSACTIONS
                    ),
                    api.getCapabilities()
            );
            assertEquals(PopulationGroupReconciliationView.Readiness.UNAVAILABLE,
                    api.policies().populationGroups().getReconciliationStatus().readiness());
            assertTrue(api.companionProvisioning().getByProfileId("missing-profile").isEmpty());

            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    ownerUuid,
                    "Owner A",
                    "Mob_Test",
                    "Display A",
                    "Custom A",
                    true,
                    null,
                    null,
                    null,
                    new String[]{"tool-a"}
            )));

            assertTrue(awaitUntil(() -> api.profiles().resolveProfileId(npcUuid).isPresent()));
            String profileId = api.profiles().resolveProfileId(npcUuid).orElseThrow();
            Optional<NpcProfileView> byProfileId = api.profiles().getByProfileId(profileId);
            Optional<NpcProfileView> byNpcUuid = api.profiles().getByNpcUuid(npcUuid);
            assertTrue(byProfileId.isPresent());
            assertTrue(byNpcUuid.isPresent());
            assertEquals(profileId, byNpcUuid.orElseThrow().profileId());
            assertTrue(api.progression().getByProfileId(profileId).isEmpty());
            assertTrue(api.progression().getByNpcUuid(npcUuid).isEmpty());
            assertNotNull(api.traitEffects());
            assertEquals(
                    ProgressionMutationStatus.NOT_LOADED,
                    api.progression().setHappiness(profileId, 75.0).status()
            );
            assertEquals(
                    ProgressionMutationStatus.NOT_LOADED,
                    api.progression().setNeeds(profileId, 10.0, 20.0).status()
            );
            assertEquals(
                    ProgressionMutationStatus.INVALID_ARGUMENT,
                    api.progression().setNeeds(profileId, null, null).status()
            );
            assertEquals(
                    ProgressionMutationStatus.INVALID_ARGUMENT,
                    api.progression().setHappiness(profileId, Double.NaN).status()
            );
            assertEquals(
                    ProgressionMutationStatus.INVALID_ARGUMENT,
                    api.progression().applyHappinessDelta(profileId, Double.POSITIVE_INFINITY).status()
            );
            assertEquals(
                    ProgressionMutationStatus.INVALID_ARGUMENT,
                    api.progression().setTraits(profileId, null).status()
            );
            assertEquals(
                    ProgressionMutationStatus.INVALID_ARGUMENT,
                    api.progression().setStoredAttachments(profileId, null).status()
            );
            assertEquals(
                    ProgressionMutationStatus.NOT_FOUND,
                    api.progression().setHappiness(UUID.randomUUID(), 10.0).status()
            );
            assertTrue(api.policies().getOwnershipByProfileId(profileId).isPresent());
            assertTrue(api.policies().isOwner(profileId, ownerUuid));
            assertTrue(api.commandLinks().getByProfileId(profileId).isPresent());
            assertEquals(Set.of("tool-a"), api.commandLinks().listLinkedToolIds(profileId));
            assertFalse(api.commandLinks().hasHomePosition(profileId));

            UUID remappedUuid = UUID.randomUUID();
            assertTrue(runtime.getNpcProfileRepository().remapCurrentUuidAsync(npcUuid, remappedUuid));
            assertTrue(awaitUntil(() -> api.profiles().resolveProfileId(remappedUuid).orElse("").equals(profileId)));

            assertTrue(runtime.getCaptureRepository().upsertAsync(new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                    remappedUuid,
                    ownerUuid,
                    new String[]{"tool-a"},
                    "Mob_Test",
                    "Display A",
                    new Vector3d(9.0, 8.0, 7.0),
                    new Vector3d(3.0, 4.0, 5.0),
                    System.currentTimeMillis()
            )));
            assertTrue(awaitUntil(() -> api.commandLinks().hasHomePosition(profileId)));
            assertEquals(3.0, api.commandLinks().getHomePosition(profileId).orElseThrow().x());
            assertEquals(4.0, api.commandLinks().getHomePosition(profileId).orElseThrow().y());
            assertEquals(5.0, api.commandLinks().getHomePosition(profileId).orElseThrow().z());
            assertTrue(api.commandLinks().getByNpcUuid(remappedUuid).isPresent());
            assertEquals(
                    9.0,
                    api.commandLinks().getByProfileId(profileId).orElseThrow().lastKnownPosition().x()
            );

            assertTrue(api.profileData().put(profileId, "example.plugin", "state", "{\"level\":2}"));
            assertTrue(awaitUntil(() -> api.profileData().get(profileId, "example.plugin", "state")
                    .orElse("")
                    .equals("{\"level\":2}")));
            assertFalse(api.profileData().put(profileId, "example.plugin", "state", "not json"));
            assertFalse(api.profileData().put(profileId, "Alechilles:Tamework", "state", "{\"level\":3}"));

            AtomicInteger profileChangeEvents = new AtomicInteger();
            AutoCloseable subscription = api.events().subscribe(NpcProfileChangedEvent.class, event -> {
                if (profileId.equals(event.profileId())) {
                    profileChangeEvents.incrementAndGet();
                }
            });
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    remappedUuid,
                    ownerUuid,
                    "Owner A",
                    "Mob_Test",
                    "Display B",
                    "Custom A",
                    true,
                    null,
                    null,
                    null,
                    new String[]{"tool-a"}
            )));
            assertTrue(awaitUntil(() -> profileChangeEvents.get() >= 1));

            subscription.close();
            assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    remappedUuid,
                    ownerUuid,
                    "Owner A",
                    "Mob_Test",
                    "Display C",
                    "Custom C",
                    true,
                    null,
                    null,
                    null,
                    new String[]{"tool-a"}
            )));
            Thread.sleep(150L);
            assertEquals(1, profileChangeEvents.get());

            assertNotNull(api.configs().getGlobalConfig());
            assertNotNull(api.diagnostics().getPersistenceDiagnostics());
            assertEquals("HEALTHY", api.diagnostics().getPersistenceResilience().storageState());
            assertTrue(api.diagnostics().queryPersistenceAvailability(
                    new PersistenceMutationAvailabilityRequest(
                            PersistenceMutationDomain.OWNER_MUTATION,
                            "integration-read-only-check",
                            List.of(new PersistenceScopeReference(
                                    PersistenceScopeKind.PROFILE, profileId,
                                    "canonical_profile_catalog")),
                            Set.of(), PersistenceMutationDirection.ZERO,
                            null, null, false, false)).allowed());

            String rawScopeKey = "profile-raw-api-test";
            var reported = runtime.getIncidentReporter().report(new PersistenceFailureContext(
                    "api_publication_failure", PersistenceDomain.OWNER_MUTATION,
                    PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.COMMITTED,
                    List.of(runtime.getPersistenceScopeFactory().profile(rawScopeKey)),
                    true, true, false, false, false,
                    false, false, true, "operation-api-test",
                    new IllegalStateException("publish")));
            assertTrue(reported.durableCompletion().get(5, TimeUnit.SECONDS));
            var incident = api.diagnostics().findPersistenceIncident(
                    reported.incidentId().substring(0, 8)).orElseThrow();
            assertEquals(reported.incidentId(), incident.incidentId());
            assertEquals("PROFILE", incident.scopes().getFirst().kind());
            assertFalse(rawScopeKey.equals(incident.scopes().getFirst().scopeHash()));
        }
    }

    @Test
    void buildsUsefulRoleIdCandidatesFromNonCanonicalInputs() {
        assertEquals(
                List.of("npcRoles.Tamed_Cow.name", "Tamed_Cow"),
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates(" npcRoles.Tamed_Cow.name "))
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
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates("AnimalHusbandry:Cow"))
        );
        assertEquals(
                List.of("Creature.Livestock.Cow", "Cow"),
                List.copyOf(TameworkApiImpl.buildRoleIdCandidates("Creature.Livestock.Cow"))
        );
    }

    private boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }
}
