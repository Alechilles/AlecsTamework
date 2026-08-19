package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class CommandHudStoreLifecycleSystemTest {
    private static final UUID PLAYER_UUID =
            UUID.fromString("c5b0ce9e-75c0-41b0-a66d-5de54ebe5466");

    @Test
    void systemAdditionDoesNotScanExistingPlayers() throws Exception {
        RecordingSink sink = new RecordingSink();
        CommandHudStoreLifecycleSystem system = new CommandHudStoreLifecycleSystem(sink);

        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(null)) {
            addPlayer(store);

            system.onSystemAddedToStore(store);

            Assertions.assertTrue(
                    sink.recoveredPlayers.isEmpty(),
                    "Store registration must not scan players before the world tick."
            );
        }
    }

    @Test
    void firstWorldTickSeedsRecoveryForExistingPlayersOnce() throws Exception {
        RecordingSink sink = new RecordingSink();
        CommandHudStoreLifecycleSystem system = new CommandHudStoreLifecycleSystem(sink);

        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(null)) {
            addPlayer(store);

            system.onSystemAddedToStore(store);
            Assertions.assertTrue(sink.recoveredPlayers.isEmpty());

            system.tick(0.0f, 0, store);
            Assertions.assertEquals(List.of(PLAYER_UUID), sink.recoveredPlayers);

            system.tick(0.0f, 0, store);
            Assertions.assertEquals(
                    List.of(PLAYER_UUID),
                    sink.recoveredPlayers,
                    "The startup recovery scan must run once per store."
            );
        }
    }

    @Test
    void removalBeforeFirstWorldTickSkipsRecoveryAndCleansStore() throws Exception {
        RecordingSink sink = new RecordingSink();
        CommandHudStoreLifecycleSystem system = new CommandHudStoreLifecycleSystem(sink);

        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             TestEntityComponentStore store = new TestEntityComponentStore(null)) {
            addPlayer(store);

            system.onSystemAddedToStore(store);
            system.onSystemRemovedFromStore(store);
            system.tick(0.0f, 0, store);

            Assertions.assertTrue(sink.recoveredPlayers.isEmpty());
            Assertions.assertEquals(List.of(store), sink.removedStores);
        }
    }

    private static void addPlayer(TestEntityComponentStore store) throws Exception {
        Ref<EntityStore> playerReference = store.createReference();
        Player player = allocate(Player.class);
        player.setLegacyUUID(PLAYER_UUID);
        store.put(playerReference, Player.getComponentType(), player);
    }

    private static final class RecordingSink implements CommandHudDirtySink {
        private final List<UUID> recoveredPlayers = new ArrayList<>();
        private final List<Store<EntityStore>> removedStores = new ArrayList<>();

        @Override
        public void markDirty(UUID ignored) {
        }

        @Override
        public void markRecovery(Store<EntityStore> ignored, UUID playerUuid) {
            recoveredPlayers.add(playerUuid);
        }

        @Override
        public void removeStore(Store<EntityStore> store) {
            removedStores.add(store);
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
}
