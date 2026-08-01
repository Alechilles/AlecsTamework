package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Authority matrix for transient bonded-card flight projection. */
class BondedCompanionPanelFlightProjectionTest {
    private static final String ROLE_ID = "Bonded_Miniwyvern_Storm";
    private static final String WORLD = "world-a";

    @Test
    void configuredActiveProjectionReturnsGroundedAndAirborneModes()
            throws Exception {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null)) {
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            BondedCompanionProfileView profile = activeProfile(
                    UUID.fromString("71000000-0000-0000-0000-000000000009"));
            TwCompanionFlightToggleSettings settings = configuredSettings();

            assertEquals(Optional.of(false), projection(false).read(
                    profile, WORLD, reference, store,
                    resolver(npc, ROLE_ID, settings)));
            assertEquals(Optional.of(true), projection(true).read(
                    profile, WORLD, reference, store,
                    resolver(npc, ROLE_ID, settings)));
        }
    }

    @Test
    void lifecycleLeaseAndWorldMustRemainAuthoritative() throws Exception {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null)) {
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            TwCompanionFlightToggleSettings settings = configuredSettings();
            BondedCompanionPanelFlightProjection projection = projection(true);

            assertUnavailable(projection, storedProfile(), WORLD, reference,
                    store, npc, ROLE_ID, settings);
            assertUnavailable(projection, deadProfile(), WORLD, reference,
                    store, npc, ROLE_ID, settings);
            assertUnavailable(projection, activeProfile(null), WORLD, reference,
                    store, npc, ROLE_ID, settings);
            assertUnavailable(projection, activeProfile(UUID.randomUUID()),
                    null, reference, store, npc, ROLE_ID, settings);
            assertUnavailable(projection, activeProfile(UUID.randomUUID()),
                    "world-b", reference, store, npc, ROLE_ID, settings);
            assertThrows(IllegalArgumentException.class,
                    () -> profile(BondedCompanionStateView.ACTIVE, null));
        }
    }

    @Test
    void referenceStoreAndNpcMustMatchTheLiveProjection() throws Exception {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null);
             TestEntityComponentStore otherStore =
                     new TestEntityComponentStore(null)) {
            BondedCompanionProfileView profile = activeProfile(
                    UUID.randomUUID());
            TwCompanionFlightToggleSettings settings = configuredSettings();
            BondedCompanionPanelFlightProjection projection = projection(true);
            NPCEntity npc = new NPCEntity();
            Ref<EntityStore> valid = store.createReference();
            Ref<EntityStore> invalid = new Ref<>(store);

            assertUnavailable(projection, profile, WORLD, null, store, npc,
                    ROLE_ID, settings);
            assertUnavailable(projection, profile, WORLD, valid, null, npc,
                    ROLE_ID, settings);
            assertUnavailable(projection, profile, WORLD, invalid, store, npc,
                    ROLE_ID, settings);
            AtomicBoolean foreignResolved = new AtomicBoolean();
            assertTrue(projection.read(profile, WORLD, valid, otherStore,
                    resolver(npc, ROLE_ID, settings, foreignResolved)).isEmpty());
            assertFalse(foreignResolved.get(),
                    "foreign refs must be rejected before entity resolution");
            assertUnavailable(projection, profile, WORLD, valid, store, null,
                    ROLE_ID, settings);
        }
    }

    @Test
    void configuredLiveRoleMayDifferFromPersistedProfileRole()
            throws Exception {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null)) {
            Ref<EntityStore> reference = store.createReference();
            BondedCompanionProfileView profile = activeProfile(
                    UUID.randomUUID());

            assertEquals(Optional.of(true), projection(true).read(
                    profile, WORLD, reference, store,
                    resolver(new NPCEntity(), "Bonded_Miniwyvern_Void",
                            configuredSettings())));
        }
    }

    @Test
    void liveRoleAndConfiguredCapabilityGateControllerReading()
            throws Exception {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null)) {
            Ref<EntityStore> reference = store.createReference();
            NPCEntity npc = new NPCEntity();
            BondedCompanionProfileView profile = activeProfile(
                    UUID.randomUUID());
            AtomicBoolean read = new AtomicBoolean();
            BondedCompanionPanelFlightProjection projection =
                    new BondedCompanionPanelFlightProjection((ignoredNpc,
                            ignoredSettings) -> {
                        read.set(true);
                        return Optional.of(true);
                    });

            assertUnavailable(projection, profile, WORLD, reference, store, npc,
                    null, configuredSettings());
            assertUnavailable(projection, profile, WORLD, reference, store, npc,
                    ROLE_ID, null);
            assertUnavailable(projection, profile, WORLD, reference, store, npc,
                    ROLE_ID, disabledSettings());
            assertUnavailable(projection, profile, WORLD, reference, store, npc,
                    ROLE_ID, incompleteSettings());
            assertFalse(read.get(), "controller state must not grant capability");
        }
    }

    @Test
    void unknownControllerModeRemainsUnavailable() throws Exception {
        try (TestEntityComponentStore store =
                     new TestEntityComponentStore(null)) {
            Ref<EntityStore> reference = store.createReference();
            Optional<Boolean> result = new BondedCompanionPanelFlightProjection(
                    (npc, settings) -> Optional.empty()).read(
                    activeProfile(UUID.randomUUID()), WORLD, reference, store,
                    resolver(new NPCEntity(), ROLE_ID, configuredSettings()));

            assertTrue(result.isEmpty());
        }
    }

    private static BondedCompanionPanelFlightProjection projection(
            boolean airborne
    ) {
        return new BondedCompanionPanelFlightProjection(
                (npc, settings) -> Optional.of(airborne));
    }

    private static void assertUnavailable(
            BondedCompanionPanelFlightProjection projection,
            BondedCompanionProfileView profile,
            String world,
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            NPCEntity npc,
            String liveRoleId,
            TwCompanionFlightToggleSettings settings
    ) {
        assertTrue(projection.read(profile, world, reference, store,
                resolver(npc, liveRoleId, settings)).isEmpty());
    }

    private static BondedCompanionPanelFlightProjection.LiveResolver resolver(
            NPCEntity npc,
            String roleId,
            TwCompanionFlightToggleSettings settings
    ) {
        return resolver(npc, roleId, settings, null);
    }

    private static BondedCompanionPanelFlightProjection.LiveResolver resolver(
            NPCEntity npc,
            String roleId,
            TwCompanionFlightToggleSettings settings,
            AtomicBoolean invoked
    ) {
        return new BondedCompanionPanelFlightProjection.LiveResolver() {
            @Override
            public NPCEntity npc(Ref<EntityStore> reference,
                                 Store<EntityStore> store) {
                mark(invoked);
                return npc;
            }

            @Override
            public String roleId(Ref<EntityStore> reference,
                                 Store<EntityStore> store) {
                mark(invoked);
                return roleId;
            }

            @Override
            public TwCompanionFlightToggleSettings settings(String ignored) {
                mark(invoked);
                return settings;
            }
        };
    }

    private static void mark(AtomicBoolean invoked) {
        if (invoked != null) {
            invoked.set(true);
        }
    }

    private static BondedCompanionProfileView activeProfile(UUID liveUuid) {
        return BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.ACTIVE, liveUuid,
                Map.of());
    }

    private static BondedCompanionProfileView storedProfile() {
        return BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.STORED, null,
                Map.of());
    }

    private static BondedCompanionProfileView deadProfile() {
        return BondedPanelTestFixtures.profile(
                "profile-1", 9L, BondedCompanionStateView.DEAD, null,
                Map.of());
    }

    private static BondedCompanionProfileView profile(
            BondedCompanionStateView state,
            com.alechilles.alecstamework.api.BondedCompanionLeaseView lease
    ) {
        return new BondedCompanionProfileView(
                "profile-1", BondedPanelTestFixtures.OWNER,
                "hydragon:dragons", "hydragon:dragon", ROLE_ID, "Nimbus",
                "Miniwyvern", "Male", 9L, state, false, false, false,
                Map.of(), lease, 0L, null);
    }

    private static TwCompanionFlightToggleSettings configuredSettings() {
        return settings("""
                {"Enabled":true,"HookId":"HyDragon.Command.ToggleAirborneMode"}
                """);
    }

    private static TwCompanionFlightToggleSettings disabledSettings() {
        return settings("""
                {"Enabled":false,"HookId":"HyDragon.Command.ToggleAirborneMode"}
                """);
    }

    private static TwCompanionFlightToggleSettings incompleteSettings() {
        return settings("{\"Enabled\":true}");
    }

    private static TwCompanionFlightToggleSettings settings(String body) {
        TwCompanionConfig config = TwCompanionConfig.CODEC.decode(
                BsonDocument.parse("{\"Command\":{\"FlightToggle\":"
                        + body + "}}"), new ExtraInfo());
        return config.getCommand().getFlightToggle();
    }
}
