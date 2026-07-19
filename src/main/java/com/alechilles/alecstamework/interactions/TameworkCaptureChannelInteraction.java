package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.alechilles.alecstamework.items.CaptureChannelVfxSystem;
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
            .<String>appendInherited(
                    new KeyedCodec<>("BeamParticleSystem", Codec.STRING),
                    (interaction, value) -> interaction.beamParticleSystem = value,
                    interaction -> interaction.beamParticleSystem,
                    (interaction, parent) -> interaction.beamParticleSystem = parent.beamParticleSystem
            )
            .documentation("Optional world particle system emitted between the player and locked target while channeling.")
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("BeamNativeLength", Codec.DOUBLE),
                    (interaction, value) -> interaction.beamNativeLength = value,
                    interaction -> interaction.beamNativeLength,
                    (interaction, parent) -> interaction.beamNativeLength = parent.beamNativeLength
            )
            .documentation("Authored forward length of the beam particle system, used to scale it to the target distance.")
            .add()
            .<Boolean>appendInherited(
                    new KeyedCodec<>("ScaleBeamToTarget", Codec.BOOLEAN),
                    (interaction, value) -> interaction.scaleBeamToTarget = value,
                    interaction -> interaction.scaleBeamToTarget,
                    (interaction, parent) -> interaction.scaleBeamToTarget = parent.scaleBeamToTarget
            )
            .documentation("Whether to scale the whole particle system to the target distance. Disable for fixed-size traveling particles.")
            .add()
            .<String>appendInherited(
                    new KeyedCodec<>("CaptureBurstParticleSystem", Codec.STRING),
                    (interaction, value) -> interaction.captureBurstParticleSystem = value,
                    interaction -> interaction.captureBurstParticleSystem,
                    (interaction, parent) -> interaction.captureBurstParticleSystem = parent.captureBurstParticleSystem
            )
            .documentation("Optional one-shot world particle system emitted at the target after capture succeeds.")
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("ChannelDurationSeconds", Codec.DOUBLE),
                    (interaction, value) -> interaction.channelDurationSeconds = value,
                    interaction -> interaction.channelDurationSeconds,
                    (interaction, parent) -> interaction.channelDurationSeconds = parent.channelDurationSeconds
            )
            .documentation("Maximum lifetime of the server-tracked channel visuals.")
            .add()
            .build();

    private String phase = Phase.COMPLETE.name();
    private String beamParticleSystem;
    private double beamNativeLength = 50.0D;
    private boolean scaleBeamToTarget = true;
    private String captureBurstParticleSystem;
    private double channelDurationSeconds = 3.0D;

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
        ItemStack heldItem = context.getHeldItem();
        Player player = commandBuffer == null || playerRef == null
                ? null
                : commandBuffer.getComponent(playerRef, Player.getComponentType());
        SpawnerFeatureHandler handler = Tamework.getInstance() == null
                ? null
                : Tamework.getInstance().getSpawnerFeatureHandler();
        Phase parsedPhase = parsePhase(phase);
        Ref<EntityStore> targetRef = resolveTarget(parsedPhase, context, playerRef, player);
        if (commandBuffer == null || player == null || heldItem == null
                || heldItem.isEmpty() || handler == null || parsedPhase == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }

        if (parsedPhase != Phase.CANCEL && targetRef == null) {
            fail(context, time, type, cooldownHandler);
            return;
        }

        boolean captureAllowed = switch (parsedPhase) {
            case BEGIN -> handler.canBeginCaptureChannelInteraction(player, targetRef, heldItem);
            case COMPLETE -> handler.canCaptureInteraction(player, targetRef, heldItem);
            case CANCEL -> true;
        };
        if (!captureAllowed) {
            fail(context, time, type, cooldownHandler);
            return;
        }
        switch (parsedPhase) {
            case BEGIN -> commandBuffer.run(store -> handler.beginCaptureChannel(
                    player,
                    targetRef,
                    heldItem,
                    beamParticleSystem,
                    beamNativeLength,
                    scaleBeamToTarget,
                    channelDurationSeconds
            ));
            case CANCEL -> commandBuffer.run(store -> handler.endCaptureChannel(player, targetRef, heldItem));
            case COMPLETE -> commandBuffer.run(store -> handler.completeCaptureChannel(
                    player,
                    targetRef,
                    heldItem,
                    captureBurstParticleSystem
            ));
        }
        context.setHeldItem(heldItem);
        super.tick0(true, time, type, context, cooldownHandler);
    }

    private static Ref<EntityStore> resolveTarget(Phase phase,
                                                   InteractionContext context,
                                                   Ref<EntityStore> playerRef,
                                                   Player player) {
        if (phase == null || player == null) {
            return null;
        }
        World world = player.getWorld();
        if (phase != Phase.BEGIN && world != null && world.getEntityStore() != null) {
            Store<EntityStore> store = world.getEntityStore().getStore();
            UUIDComponent playerUuid = store.getComponent(playerRef, UUIDComponent.getComponentType());
            if (playerUuid != null && playerUuid.getUuid() != null) {
                Ref<EntityStore> locked = CaptureChannelVfxSystem.resolveTarget(playerUuid.getUuid(), world);
                if (locked != null) {
                    return locked;
                }
            }
        }
        Ref<EntityStore> explicit = context.getTargetEntity();
        if (explicit != null && explicit.isValid()) {
            return explicit;
        }
        if (phase == Phase.CANCEL || world == null || world.getEntityStore() == null
                || playerRef == null || !playerRef.isValid()) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> raycast = TargetUtil.getTargetEntity(playerRef, 32.0F, store);
        return raycast != null && raycast.isValid() ? raycast : null;
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
