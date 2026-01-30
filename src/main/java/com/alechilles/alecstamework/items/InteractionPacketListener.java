package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.ownership.OwnerStore;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import java.util.logging.Level;

public final class InteractionPacketListener implements PlayerPacketWatcher {
    private final ItemFeatureRegistry registry;
    private final HytaleLogger logger;
    private final SpawnerCaptureTracker captureTracker;
    private final SpawnerFeatureHandler spawnerFeatureHandler;

    public InteractionPacketListener(ItemFeatureRegistry registry, HytaleLogger logger, SpawnerCaptureTracker captureTracker, OwnerStore ownerStore) {
        this.registry = registry;
        this.logger = logger;
        this.captureTracker = captureTracker;
        this.spawnerFeatureHandler = new SpawnerFeatureHandler(logger, captureTracker, registry, ownerStore);
    }

    @Override
    public void accept(PlayerRef playerRef, Packet packet) {
        if (!(packet instanceof SyncInteractionChains)) {
            return;
        }
        SyncInteractionChains chains = (SyncInteractionChains) packet;
        if (chains.updates == null || chains.updates.length == 0) {
            return;
        }

        String username = playerRef != null ? playerRef.getUsername() : "<unknown>";
        String uuid = playerRef != null && playerRef.getUuid() != null ? playerRef.getUuid().toString() : "<unknown>";

        for (SyncInteractionChain chain : chains.updates) {
            if (chain == null) {
                continue;
            }
            String itemId = chain.itemInHandId;
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            ItemFeatureConfig config = registry.get(itemId);
            if (config == null) {
                continue;
            }

            InteractionChainData data = chain.data;
            int entityId = data != null ? data.entityId : -1;
            logger.at(Level.INFO).log(
                    "Packet interaction: player=" + username
                            + " (" + uuid + ")"
                            + " item=" + itemId
                            + " type=" + chain.interactionType
                            + " slot=" + chain.activeHotbarSlot
                            + " entityId=" + entityId
                            + " initial=" + chain.initial
            );

            if (playerRef == null) {
                logger.at(Level.INFO).log(
                        "Packet interaction: missing PlayerRef for item=" + itemId
                );
                continue;
            }
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid == null) {
                logger.at(Level.INFO).log(
                        "Packet interaction: missing world for player=" + username
                                + " item=" + itemId
                );
                continue;
            }
            World world = Universe.get().getWorld(worldUuid);
            if (world == null) {
                logger.at(Level.INFO).log(
                        "Packet interaction: world lookup failed for player=" + username
                                + " item=" + itemId
                );
                continue;
            }

            int activeSlot = chain.activeHotbarSlot;
            InteractionType interactionType = chain.interactionType;
            world.execute(() -> {
                Player player = playerRef.getComponent(Player.getComponentType());
                if (player == null) {
                    logger.at(Level.INFO).log(
                            "Packet interaction: player component missing for " + username
                                    + " item=" + itemId
                    );
                    return;
                }
                spawnerFeatureHandler.handlePacket(
                        player,
                        itemId,
                        activeSlot,
                        entityId,
                        interactionType,
                        config
                );
            });
        }
    }
}
