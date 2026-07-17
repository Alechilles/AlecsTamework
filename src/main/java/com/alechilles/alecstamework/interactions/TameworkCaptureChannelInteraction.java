package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
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
import java.util.Locale;
import javax.annotation.Nonnull;

/** Drives the begin, cancel, and completion phases around a native Charging interaction. */
public final class TameworkCaptureChannelInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkCaptureChannelInteraction> CODEC = BuilderCodec.builder(
            TameworkCaptureChannelInteraction.class,
            TameworkCaptureChannelInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Runs one phase of a channeled Tamework spawner capture.")
            .<String>appendInherited(
                    new KeyedCodec<>("Phase", Codec.STRING),
                    (interaction, value) -> interaction.phase = value,
                    interaction -> interaction.phase,
                    (interaction, parent) -> interaction.phase = parent.phase
            )
            .add()
            .build();

    private String phase = Phase.COMPLETE.name();

    private enum Phase {
        BEGIN,
        CANCEL,
        COMPLETE
    }

    protected TameworkCaptureChannelInteraction() {
        super();
    }

    public TameworkCaptureChannelInteraction(String id) {
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
        Ref<EntityStore> targetRef = context.getTargetEntity();
        ItemStack heldItem = context.getHeldItem();
        Player player = commandBuffer == null || playerRef == null
                ? null
                : commandBuffer.getComponent(playerRef, Player.getComponentType());
        SpawnerFeatureHandler handler = Tamework.getInstance() == null
                ? null
                : Tamework.getInstance().getSpawnerFeatureHandler();
        Phase parsedPhase = parsePhase(phase);
        if (commandBuffer == null || player == null || targetRef == null || heldItem == null
                || heldItem.isEmpty() || handler == null || parsedPhase == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }

        if (parsedPhase != Phase.CANCEL && !handler.canCaptureInteraction(player, targetRef, heldItem)) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        switch (parsedPhase) {
            case BEGIN -> commandBuffer.run(store -> handler.beginCaptureChannel(player, targetRef, heldItem));
            case CANCEL -> commandBuffer.run(store -> handler.endCaptureChannel(player, targetRef, heldItem));
            case COMPLETE -> commandBuffer.run(store -> handler.completeCaptureChannel(player, targetRef, heldItem));
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
        super.simulateTick0(firstRun, time, type, context, cooldownHandler);
    }

    private void fail(InteractionContext context,
                      float time,
                      InteractionType type,
                      CooldownHandler cooldownHandler) {
        context.getState().state = InteractionState.Failed;
        super.tick0(true, time, type, context, cooldownHandler);
    }

    private static Phase parsePhase(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Phase.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
