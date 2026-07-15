package com.alechilles.alecstamework.items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the online command-inventory repair lifecycle and its ECS thread boundary. */
class CommandLinkedNpcInventoryCanonicalizationSystemArchitectureTest {
    private static final Path SYSTEM = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/",
            "CommandLinkedNpcInventoryCanonicalizationSystem.java"
    );
    private static final Path HANDLER = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java"
    );
    private static final Path WORLD_EVENTS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/CommandWorldChangeTravelEventHandler.java"
    );
    private static final Path PLUGIN = Path.of(
            "src/main/java/com/alechilles/alecstamework/Tamework.java"
    );

    @Test
    void inventoryMovementCoversCanonicalCompartmentsAndDefersIdentityReads() throws IOException {
        String source = Files.readString(SYSTEM);

        assertTrue(source.contains("extends EntityEventSystem<EntityStore, InventoryChangeEvent>"));
        assertTrue(source.contains("event.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Hotbar.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Storage.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Backpack.getComponentType()"));
        assertTrue(source.contains("TameworkMetadataKeys.COMMAND_LINKED_NPCS"));
        assertTrue(source.contains("world.execute(() -> canonicalizeQueuedPlayer(world, playerUuid))"));
        assertTrue(source.contains("featureHandler.canonicalizePlayerCommandInventory(world, playerUuid)"));

        int handle = source.indexOf("public void handle(");
        int queuedCallback = source.indexOf("private void canonicalizeQueuedPlayer(");
        int canonicalize = source.indexOf(
                "featureHandler.canonicalizePlayerCommandInventory(world, playerUuid)");
        assertTrue(handle >= 0 && queuedCallback > handle && canonicalize > queuedCallback,
                "Identity reads must remain in the queued world callback, not the ECS handler");
        assertFalse(source.contains("PlayerRef.getComponent("));
        assertFalse(source.contains("Universe.get()"));
        assertFalse(source.contains("store.putComponent("));
        assertFalse(source.contains("CompletableFuture"));
    }

    @Test
    void loginLoadRepairsInventoryAndMovementResolvesPlayerFromWorldStore() throws IOException {
        String source = Files.readString(HANDLER);
        String events = Files.readString(WORLD_EVENTS);

        assertTrue(events.contains("public void onAddPlayerToWorld("));
        assertTrue(events.contains(
                "commandItems.canonicalizePlayerCommandInventory(event.getHolder())"
        ));
        assertTrue(events.contains("if (!sessions.isWorldChange(playerUuid))"),
                "Initial login must remain distinct from later world-change travel");
        assertTrue(source.contains("world.getEntityRef(playerUuid)"));
        assertTrue(source.contains("store.getComponent(playerRef, Player.getComponentType())"));
        assertTrue(source.contains("inventoryRepairService.canonicalize(player)"));
        assertFalse(source.contains("PlayerRef.getComponent(Player"));
        assertFalse(source.contains("Universe.get().getPlayers"));
    }

    @Test
    void pluginRegistersInventoryMovementCanonicalizationAfterCreatingTheHandler()
            throws IOException {
        String source = Files.readString(PLUGIN);
        int handler = source.indexOf("commandItemFeatureHandler = new CommandItemFeatureHandler(");
        int registration = source.indexOf(
                "new CommandLinkedNpcInventoryCanonicalizationSystem(commandItemFeatureHandler)");

        assertTrue(handler >= 0 && registration > handler);
    }
}
