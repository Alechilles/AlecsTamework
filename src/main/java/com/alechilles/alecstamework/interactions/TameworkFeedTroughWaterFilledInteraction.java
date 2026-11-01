package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.TroughFillActivityView;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Reports who filled a trough with water. It sits on the {@code Next} path of a bucket's ChangeBlock,
 * which only continues when that block change happened, so it needs no check of its own and never fails.
 */
public final class TameworkFeedTroughWaterFilledInteraction extends SimpleInstantInteraction {
    public static final String TYPE_ID = "TameworkFeedTroughWaterFilled";
    public static final BuilderCodec<TameworkFeedTroughWaterFilledInteraction> CODEC = BuilderCodec.builder(
            TameworkFeedTroughWaterFilledInteraction.class,
            TameworkFeedTroughWaterFilledInteraction::new,
            SimpleInstantInteraction.CODEC
    ).documentation("Reports that the interacting player just filled a feed trough with water.").build();

    protected TameworkFeedTroughWaterFilledInteraction() {
        super();
    }

    public TameworkFeedTroughWaterFilledInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> actorRef = context.getEntity();
        if (commandBuffer == null || actorRef == null) {
            return;
        }
        UUIDComponent uuid = commandBuffer.getComponent(actorRef, UUIDComponent.getComponentType());
        if (uuid != null) {
            ActivityRuntime.publishTroughFill(uuid.getUuid(), TroughFillActivityView.WATER);
        }
    }
}
