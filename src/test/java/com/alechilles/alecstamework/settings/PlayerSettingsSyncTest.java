package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.api.NetworkSettingsDocument;
import com.alechilles.alecstamework.api.NetworkSettingsStore;
import com.alechilles.alecstamework.items.components.TameworkCompanionCardPlacementComponent;
import com.alechilles.alecstamework.items.components.TameworkCompanionCardPreferenceComponent;
import com.alechilles.alecstamework.items.components.TameworkCompanionGroupsComponent;
import com.alechilles.alecstamework.items.components.TameworkCompanionViewsComponent;
import com.alechilles.alecstamework.items.components.TameworkHudPlacementComponent;
import com.alechilles.alecstamework.items.components.TameworkInspectPreferencesComponent;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player preferences on servers whose engine player save is off: every join starts from a blank holder, as on
 * Runeteria, so only Tamework's own document brings them back.
 */
class PlayerSettingsSyncTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    @TempDir
    Path tempDir;

    private final ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
    private final List<Runnable> restore = new ArrayList<>();
    private final List<AutoCloseable> open = new ArrayList<>();
    private ComponentType<EntityStore, TameworkCompanionGroupsComponent> groups;

    @BeforeEach
    void registerPlayerComponents() throws Exception {
        groups = register(TameworkCompanionGroupsComponent.class, "TameworkCompanionGroups",
                TameworkCompanionGroupsComponent.CODEC);
        register(TameworkCompanionViewsComponent.class, "TameworkCompanionViews", TameworkCompanionViewsComponent.CODEC);
        register(TameworkHudPlacementComponent.class, "TameworkHudPlacement", TameworkHudPlacementComponent.CODEC);
        register(TameworkCompanionCardPreferenceComponent.class, "TameworkCompanionCardPreference",
                TameworkCompanionCardPreferenceComponent.CODEC);
        register(TameworkCompanionCardPlacementComponent.class, "TameworkCompanionCardPlacement",
                TameworkCompanionCardPlacementComponent.CODEC);
        register(TameworkInspectPreferencesComponent.class, "TameworkInspectPreferences",
                TameworkInspectPreferencesComponent.CODEC);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
        restore.forEach(Runnable::run);
    }

    @Test
    void groupsSavedOnOneVisitComeBackOnTheNextWithoutANetwork() throws Exception {
        Server server = server("solo", null);

        visit(server, holderWithGroups("Barn|#ffaa00"));
        Holder<EntityStore> rejoined = visit(server, registry.newHolder());

        assertEquals("Barn|#ffaa00", groupsOf(rejoined));
    }

    @Test
    void groupsFollowThePlayerToAnotherServerOfTheNetwork() throws Exception {
        FakeStore store = new FakeStore();
        Server hub = server("hub", store);
        Server smp = server("smp", store);

        visit(hub, holderWithGroups("Barn|#ffaa00"));
        Holder<EntityStore> arrived = visit(smp, registry.newHolder());

        assertEquals("Barn|#ffaa00", groupsOf(arrived));
    }

    @Test
    void aStoreThatIsDownAtJoinKeepsItsDocument() throws Exception {
        FakeStore store = new FakeStore();
        Server hub = server("hub", store);
        visit(hub, holderWithGroups("Barn|#ffaa00"));
        String saved = store.documents.get(PlayerSettingsSync.KEY_PREFIX + PLAYER).json();

        store.failing = true;
        Server smp = server("smp", store);
        Holder<EntityStore> blank = visit(smp, registry.newHolder());
        store.failing = false;
        smp.drain();

        assertNull(blank.getComponent(groups));
        assertEquals(saved, store.documents.get(PlayerSettingsSync.KEY_PREFIX + PLAYER).json());
    }

    @Test
    void aServerThatReadBeforeTheStoreCameUpDoesNotReplaceTheNetworkDocument() throws Exception {
        FakeStore store = new FakeStore();
        visit(server("hub", store), holderWithGroups("Barn|#ffaa00"));
        String saved = store.documents.get(PlayerSettingsSync.KEY_PREFIX + PLAYER).json();
        Server smp = server("smp", null);
        Store<EntityStore> world = registry.addStore(null, null);
        try {
            Ref<EntityStore> ref = world.addEntity(connect(smp, registry.newHolder()), AddReason.SPAWN);

            smp.sync.register(store);
            world.putComponent(ref, groups, groupsComponent("Pen|#00ff00"));
            smp.players.changed(PLAYER, ref, world, TameworkCompanionGroupsComponent.class);
            smp.drain();

            assertEquals(saved, store.documents.get(PlayerSettingsSync.KEY_PREFIX + PLAYER).json());
        } finally {
            registry.removeStore(world);
        }
    }

    @Test
    void aChangeIsSavedAndKeepsWhatANewerTameworkAddedToTheDocument() throws Exception {
        FakeStore store = new FakeStore();
        store.documents.put(PlayerSettingsSync.KEY_PREFIX + PLAYER, new NetworkSettingsDocument(
                "{\"Format\": 1, \"Components\": {\"TameworkCompanionGroups\": {\"Definitions\": \"Barn|#ffaa00\"},"
                        + " \"TameworkFutureThing\": {\"Kept\": true}}}", 4L));
        Server smp = server("smp", store);
        Store<EntityStore> world = registry.addStore(null, null);
        try {
            Ref<EntityStore> ref = world.addEntity(connect(smp, registry.newHolder()), AddReason.SPAWN);

            world.putComponent(ref, groups, groupsComponent("Pen|#00ff00"));
            smp.players.changed(PLAYER, ref, world, TameworkCompanionGroupsComponent.class);
            smp.drain();

            BsonDocument components = BsonDocument.parse(
                    store.documents.get(PlayerSettingsSync.KEY_PREFIX + PLAYER).json()).getDocument("Components");
            assertEquals("Pen|#00ff00",
                    components.getDocument("TameworkCompanionGroups").getString("Definitions").getValue());
            assertTrue(components.containsKey("TameworkFutureThing"));
        } finally {
            registry.removeStore(world);
        }
    }

    /** One connect and disconnect, waiting for the read and any save. Returns the holder as the login left it. */
    private Holder<EntityStore> visit(Server server, Holder<EntityStore> holder) throws Exception {
        connect(server, holder);
        server.players.playerDisconnected(PLAYER);
        return holder;
    }

    /** The connect the login waits for, then any save it queued. */
    private Holder<EntityStore> connect(Server server, Holder<EntityStore> holder) throws Exception {
        server.players.playerConnecting(PLAYER, holder).get(5, TimeUnit.SECONDS);
        server.drain();
        return holder;
    }

    private Server server(String name, NetworkSettingsStore store) throws Exception {
        Path root = tempDir.resolve(name);
        NetworkSettingsSync sync = new NetworkSettingsSync(Executors.newSingleThreadScheduledExecutor(),
                () -> root.resolve("network-cache.json"),
                new NetworkSettingsFiles(List.of(), null), () -> { }, null);
        open.add(sync);
        if (store != null) {
            sync.register(store);
        }
        PlayerSettingsSync players = new PlayerSettingsSync(sync, () -> root, null);
        open.add(0, players);
        return new Server(sync, players);
    }

    private Holder<EntityStore> holderWithGroups(String definitions) {
        Holder<EntityStore> holder = registry.newHolder();
        holder.putComponent(groups, groupsComponent(definitions));
        return holder;
    }

    private static TameworkCompanionGroupsComponent groupsComponent(String definitions) {
        TameworkCompanionGroupsComponent component = new TameworkCompanionGroupsComponent();
        component.definitions(definitions);
        return component;
    }

    private String groupsOf(Holder<EntityStore> holder) {
        TameworkCompanionGroupsComponent component = holder.getComponent(groups);
        assertNotNull(component, "the groups component was not restored");
        return component.definitions();
    }

    /** Registers a component in this test's registry and points the class's static type at it until tear-down. */
    private <C extends Component<EntityStore>> ComponentType<EntityStore, C> register(
            Class<C> type, String id, BuilderCodec<C> codec) throws Exception {
        ComponentType<EntityStore, C> registered = registry.registerComponent(type, id, codec);
        Field field = type.getDeclaredField("type");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, registered);
        restore.add(() -> {
            try {
                field.set(null, previous);
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        return registered;
    }

    private record Server(NetworkSettingsSync sync, PlayerSettingsSync players) {
        /** Waits until the settings thread ran everything queued so far, twice to cover work queued by that work. */
        void drain() throws Exception {
            for (int round = 0; round < 2; round++) {
                sync.run(store -> { }).get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class FakeStore implements NetworkSettingsStore {
        final Map<String, NetworkSettingsDocument> documents = new ConcurrentHashMap<>();
        volatile boolean failing;

        @Override
        public NetworkSettingsDocument load(String key) throws Exception {
            if (failing) {
                throw new IOException("store down");
            }
            return documents.get(key);
        }

        @Override
        public synchronized boolean save(String key, String json, long expectedRevision) throws Exception {
            if (failing) {
                throw new IOException("store down");
            }
            NetworkSettingsDocument current = documents.get(key);
            if ((current == null ? 0L : current.revision()) != expectedRevision) {
                return false;
            }
            documents.put(key, new NetworkSettingsDocument(json, expectedRevision + 1));
            return true;
        }
    }
}
