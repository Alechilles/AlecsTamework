package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Custom interaction handler used by command items.
 */
public class TameworkCommandInteraction extends SimpleInteraction {
    private static final long SLOW_INTERACTION_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(20L);

    public static final BuilderCodec<TameworkCommandInteraction> CODEC = BuilderCodec.builder(
            TameworkCommandInteraction.class,
            TameworkCommandInteraction::new,
            SimpleInteraction.CODEC
    )
        .documentation("Dispatches Tamework command-item actions and link toggles.")
        .<String>appendInherited(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            (interaction, value) -> interaction.configId = value,
            interaction -> interaction.configId,
            (interaction, parent) -> interaction.configId = parent.configId
        )
        .add()
        .<String>appendInherited(
            new KeyedCodec<>("CommandId", Codec.STRING),
            (interaction, value) -> interaction.commandId = value,
            interaction -> interaction.commandId,
            (interaction, parent) -> interaction.commandId = parent.commandId
        )
        .add()
        .build();

    private String configId;
    private String commandId;

    protected TameworkCommandInteraction() {
        super();
    }

    public TameworkCommandInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void tick0(boolean firstRun,
                         float time,
                         @Nonnull InteractionType type,
                         @Nonnull InteractionContext context,
                         @Nonnull CooldownHandler cooldownHandler) {
        if (!firstRun) {
            super.tick0(false, time, type, context, cooldownHandler);
            return;
        }
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        if (commandBuffer == null || playerRef == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Player player = commandBuffer.getComponent(playerRef, Player.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null || heldItem.isEmpty()) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Tamework plugin = Tamework.getInstance();
        CommandItemFeatureHandler handler = plugin != null ? plugin.getCommandItemFeatureHandler() : null;
        if (handler == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Ref<EntityStore> targetEntity = context.getTargetEntity();
        boolean debugLag = plugin != null && plugin.isDebugLagEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        commandBuffer.run(store -> handler.handleUse(
                player,
                heldItem,
                targetEntity,
                configId,
                commandId
        ));
        if (debugLag) {
            logSlowInteraction(plugin, startedNs, player, heldItem, targetEntity);
        }
        context.setHeldItem(heldItem);
        super.tick0(true, time, type, context, cooldownHandler);
    }

    @Override
    protected void simulateTick0(boolean firstRun,
                                 float time,
                                 @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context,
                                 @Nonnull CooldownHandler cooldownHandler) {
        if (context.getServerState() != null && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    private void logSlowInteraction(Tamework plugin,
                                    long startedNs,
                                    Player player,
                                    ItemStack heldItem,
                                    Ref<EntityStore> targetEntity) {
        if (plugin == null || startedNs <= 0L || plugin.getLogger() == null) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedNs;
        if (elapsedNs < SLOW_INTERACTION_THRESHOLD_NS) {
            return;
        }
        double elapsedMs = elapsedNs / 1_000_000.0;
        String playerName = player != null ? player.getDisplayName() : "<unknown>";
        String itemId = heldItem != null ? heldItem.getItemId() : "<none>";
        plugin.getLogger().at(Level.WARNING).log(
                "Tamework lag probe: command interaction took "
                        + elapsedMs
                        + "ms (player="
                        + playerName
                        + ", item="
                        + itemId
                        + ", configId="
                        + configId
                        + ", commandId="
                        + commandId
                        + ", target="
                        + targetEntity
                        + ")."
        );
    }
}
