package com.alechilles.alecstamework;

import com.alechilles.alecstamework.config.ItemFeatureConfigLoader;
import com.alechilles.alecstamework.config.ModItemFeatureConfigDiscovery;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.items.InteractionPacketListener;
import com.alechilles.alecstamework.items.ItemInteractionListener;
import com.alechilles.alecstamework.items.OwnerInteractionListener;
import com.alechilles.alecstamework.items.SpawnerCaptureInventoryListener;
import com.alechilles.alecstamework.items.SpawnerCaptureTracker;
import com.alechilles.alecstamework.ownership.OwnerStore;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Main entry point for the .Alec's Tamework! plugin.
 */
public class Tamework extends JavaPlugin {

    private static final String ITEM_FEATURE_CONFIG_PATH =
            "config/tamework-item-features.json";

    private ItemFeatureRegistry itemFeatureRegistry;
    private PacketFilter interactionPacketFilter;

    public Tamework(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        itemFeatureRegistry = new ItemFeatureRegistry();
        itemFeatureRegistry.registerDefaults();

        ItemFeatureConfigLoader loader = new ItemFeatureConfigLoader();
        int loaded = 0;
        loaded += loader.loadFromResource(
                ITEM_FEATURE_CONFIG_PATH,
                itemFeatureRegistry,
                getLogger()
        );
        loaded += ModItemFeatureConfigDiscovery.loadAll(
                loader,
                itemFeatureRegistry,
                getLogger()
        );

        SpawnerCaptureTracker captureTracker = new SpawnerCaptureTracker();
        OwnerStore ownerStore = new OwnerStore(getDataDirectory(), getLogger());

        ItemInteractionListener itemInteractionListener =
                new ItemInteractionListener(itemFeatureRegistry, getLogger(), captureTracker, ownerStore);
        getEventRegistry().registerGlobal(
                PlayerInteractEvent.class,
                itemInteractionListener::onPlayerInteract
        );

        OwnerInteractionListener ownerInteractionListener =
                new OwnerInteractionListener(ownerStore, getLogger());
        getEventRegistry().registerGlobal(
                PlayerInteractEvent.class,
                ownerInteractionListener::onPlayerInteract
        );
        getEventRegistry().registerGlobal(
                PlayerMouseButtonEvent.class,
                itemInteractionListener::onMouseButton
        );

        SpawnerCaptureInventoryListener captureInventoryListener =
                new SpawnerCaptureInventoryListener(itemFeatureRegistry, captureTracker, getLogger());
        getEventRegistry().registerGlobal(
                LivingEntityInventoryChangeEvent.class,
                captureInventoryListener::onInventoryChange
        );

        InteractionPacketListener packetListener =
                new InteractionPacketListener(itemFeatureRegistry, getLogger(), captureTracker, ownerStore);
        interactionPacketFilter = PacketAdapters.registerInbound(packetListener);

        getLogger().at(Level.INFO).log(
                "Tamework item feature configs loaded: " + loaded
                        + " (total: " + itemFeatureRegistry.snapshot().size() + ")"
        );
    }

    @Override
    protected void start() {
        // Called when the plugin is enabled
        getLogger().at(Level.INFO).log(".Alec's Tamework! has been enabled!");
    }

    @Override
    protected void shutdown() {
        if (interactionPacketFilter != null) {
            PacketAdapters.deregisterInbound(interactionPacketFilter);
            interactionPacketFilter = null;
        }
        // Called when the plugin is disabled
        getLogger().at(Level.INFO).log(".Alec's Tamework! has been disabled!");
    }

    public ItemFeatureRegistry getItemFeatureRegistry() {
        return itemFeatureRegistry;
    }
}
