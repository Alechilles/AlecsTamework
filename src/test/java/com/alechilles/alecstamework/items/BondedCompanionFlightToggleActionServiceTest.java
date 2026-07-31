package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Executable authorization matrix for the flight-toggle hook boundary. */
class BondedCompanionFlightToggleActionServiceTest {
    private static final UUID OWNER = UUID.fromString("86000000-0000-0000-0000-000000000001");
    private static final UUID LIVE = UUID.fromString("86000000-0000-0000-0000-000000000002");

    @Test
    void rejectsEveryStaleOrUnavailableBoundaryWithoutDispatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertRejected(fixture, row(BondedCompanionStateView.STORED, available(LIVE)),
                    fixture.live, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, Map.of()),
                    fixture.live, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE,
                            Map.of("bonded.flightToggle.available", "true",
                                    "bonded.liveNpcUuid", "not-a-uuid")),
                    fixture.live, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE,
                            Map.of("bonded.flightToggle.available", "true")),
                    fixture.live, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    fixture.live, settings(true, "hydragon:toggle"), Optional.of(true), false);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    null, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    fixture.invalid, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    fixture.foreign, settings(true, "hydragon:toggle"), Optional.of(true), true);
            assertRoleMismatch(fixture);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    fixture.live, settings(true, "hydragon:toggle"), Optional.empty(), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    fixture.live, settings(false, "hydragon:toggle"), Optional.of(true), true);
            assertRejected(fixture, row(BondedCompanionStateView.ACTIVE, available(LIVE)),
                    fixture.live, settings(true, ""), Optional.of(true), true);
        }
    }

    private void assertRoleMismatch(Fixture fixture) throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        BondedCompanionFlightToggleActionService service = fixture.service(
                fixture.live, "other-role", settings(true, "hydragon:toggle"),
                Optional.of(true), true,
                (id, player, item, ref, store, target) -> {
                    dispatches.incrementAndGet(); return true;
                });
        assertFalse(service.toggle(OWNER, fixture.actor, fixture.store,
                "hydragon:horn", row(BondedCompanionStateView.ACTIVE,
                        available(LIVE))));
        assertEquals(0, dispatches.get());
    }

    @Test
    void dispatchesOnlyTheReResolvedConfiguredHookWithoutDirectMutation()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger dispatches = new AtomicInteger();
            String[] hook = new String[1];
            Ref<EntityStore>[] dispatchedRef = new Ref[1];
            BondedCompanionFlightToggleActionService service = fixture.service(
                    fixture.live, "role", settings(true, "hydragon:toggle"),
                    Optional.of(false), true,
                    (id, player, item, ref, store, target) -> {
                        dispatches.incrementAndGet(); hook[0] = id;
                        dispatchedRef[0] = ref; assertNull(target);
                        return true;
                    });

            assertTrue(service.toggle(OWNER, fixture.actor, fixture.store,
                    "hydragon:horn", row(BondedCompanionStateView.ACTIVE,
                            available(LIVE))));
            assertEquals(1, dispatches.get());
            assertEquals("hydragon:toggle", hook[0]);
            assertEquals(fixture.live, dispatchedRef[0]);
        }
    }

    private void assertRejected(Fixture fixture, BondedCompanionPanelPresentation row,
                                Ref<EntityStore> resolvedRef,
                                TwCompanionFlightToggleSettings settings,
                                Optional<Boolean> mode, boolean playerAuthority)
            throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        BondedCompanionFlightToggleActionService service = fixture.service(
                resolvedRef, "role", settings, mode, playerAuthority,
                (id, player, item, ref, store, target) -> {
                    dispatches.incrementAndGet(); return true;
                });
        assertFalse(service.toggle(OWNER, fixture.actor, fixture.store,
                "hydragon:horn", row));
        assertEquals(0, dispatches.get());
    }

    private static Map<String, String> available(UUID live) {
        return Map.of("bonded.flightToggle.available", "true",
                "bonded.liveNpcUuid", live.toString());
    }

    private static BondedCompanionPanelPresentation row(BondedCompanionStateView state,
                                                        Map<String, String> attributes) {
        return new BondedCompanionPanelPresentation("profile", "roster", "role",
                1L, null, null, null, null, attributes, Map.of(),
                new BondedCompanionStatusPresentation(state,
                        BondedCompanionStatusPresentation.Action.DISMISS,
                        true, null, 0L), null);
    }

    private static TwCompanionFlightToggleSettings settings(boolean enabled,
                                                             String hook) throws Exception {
        TwCompanionFlightToggleSettings settings = new TwCompanionFlightToggleSettings();
        Field enabledField = TwCompanionFlightToggleSettings.class.getDeclaredField("enabled");
        enabledField.setAccessible(true); enabledField.set(settings, enabled);
        Field hookField = TwCompanionFlightToggleSettings.class.getDeclaredField("hookId");
        hookField.setAccessible(true); hookField.set(settings, hook);
        return settings;
    }

    private static final class Fixture implements AutoCloseable {
        private final TestEntityComponentStore store;
        private final Ref<EntityStore> actor;
        private final Ref<EntityStore> live;
        private final Ref<EntityStore> invalid;
        private final Ref<EntityStore> foreign;
        private final Player player;
        private final NPCEntity npc;

        private Fixture() throws Exception {
            TestWorld world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            TestEntityStore entities = new TestEntityStore(world);
            store = new TestEntityComponentStore(entities); entities.store = store;
            actor = store.createReference(); live = store.createReference();
            invalid = (Ref<EntityStore>) unsafe().allocateInstance(Ref.class);
            foreign = new TestEntityComponentStore(entities).createReference();
            player = (Player) unsafe().allocateInstance(Player.class);
            npc = (NPCEntity) unsafe().allocateInstance(NPCEntity.class);
        }

        private BondedCompanionFlightToggleActionService service(
                Ref<EntityStore> ref, String role,
                TwCompanionFlightToggleSettings settings, Optional<Boolean> mode,
                boolean authority,
                BondedCompanionFlightToggleActionService.HookDispatcher dispatch) {
            return new BondedCompanionFlightToggleActionService(
                    new BondedCompanionFlightToggleActionService.LiveResolver() {
                        @Override public Ref<EntityStore> reference(Player ignored, UUID id) { return ref; }
                        @Override public NPCEntity npc(Ref<EntityStore> ignored, Store<EntityStore> current) { return npc; }
                        @Override public String roleId(Ref<EntityStore> ignored, Store<EntityStore> current) { return role; }
                        @Override public TwCompanionFlightToggleSettings settings(String ignored) { return settings; }
                    }, dispatch, (ignored, current) -> mode,
                    (owner, eventRef, eventStore) -> authority ? player : null);
        }

        @Override public void close() { store.close(); }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityComponentStore store;
        private TestEntityStore(World world) { super(world); }
        @Override public TestEntityComponentStore getStore() { return store; }
    }

    private static final class TestWorld extends World {
        private TestWorld() throws java.io.IOException {
            super("unused", Path.of("."), new com.hypixel.hytale.server.core.universe.world.WorldConfig());
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
