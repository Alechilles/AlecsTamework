package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.items.CaptureChannelAnchorResolver;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileAnchor;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileSpawner;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileSpec;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionTarget;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Launches one harmless model-particle carrier that homes to a live entity anchor. */
public final class TameworkLaunchHomingVisualProjectileInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<TameworkLaunchHomingVisualProjectileInteraction> CODEC = BuilderCodec.builder(
            TameworkLaunchHomingVisualProjectileInteraction.class,
            TameworkLaunchHomingVisualProjectileInteraction::new,
            SimpleInstantInteraction.CODEC
    )
            .documentation("Launches one non-combat visual projectile that homes to a live entity anchor.")
            .<String>appendInherited(
                    new KeyedCodec<>("ModelId", Codec.STRING),
                    (interaction, value) -> interaction.modelId = value,
                    interaction -> interaction.modelId,
                    (interaction, parent) -> interaction.modelId = parent.modelId
            )
            .addValidator(Validators.nonNull())
            .addValidator(ModelAsset.VALIDATOR_CACHE.getValidator().late())
            .add()
            .<InteractionTarget>appendInherited(
                    new KeyedCodec<>("Source", InteractionTarget.CODEC),
                    (interaction, value) -> interaction.source = value,
                    interaction -> interaction.source,
                    (interaction, parent) -> interaction.source = parent.source
            )
            .documentation("Entity whose configured anchor supplies the one-time launch position.")
            .add()
            .<InteractionTarget>appendInherited(
                    new KeyedCodec<>("Target", InteractionTarget.CODEC),
                    (interaction, value) -> interaction.target = value,
                    interaction -> interaction.target,
                    (interaction, parent) -> interaction.target = parent.target
            )
            .documentation("Entity whose live anchor is recomputed every server tick.")
            .add()
            .<HomingVisualProjectileAnchor>appendInherited(
                    new KeyedCodec<>("SourceAnchor", new EnumCodec<>(HomingVisualProjectileAnchor.class)),
                    (interaction, value) -> interaction.sourceAnchor = value,
                    interaction -> interaction.sourceAnchor,
                    (interaction, parent) -> interaction.sourceAnchor = parent.sourceAnchor
            )
            .add()
            .<HomingVisualProjectileAnchor>appendInherited(
                    new KeyedCodec<>("TargetAnchor", new EnumCodec<>(HomingVisualProjectileAnchor.class)),
                    (interaction, value) -> interaction.targetAnchor = value,
                    interaction -> interaction.targetAnchor,
                    (interaction, parent) -> interaction.targetAnchor = parent.targetAnchor
            )
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("Speed", Codec.DOUBLE),
                    (interaction, value) -> interaction.speed = value,
                    interaction -> interaction.speed,
                    (interaction, parent) -> interaction.speed = parent.speed
            )
            .addValidator(Validators.greaterThan(0.0D))
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("TurnRateDegreesPerSecond", Codec.DOUBLE),
                    (interaction, value) -> interaction.turnRateDegreesPerSecond = value,
                    interaction -> interaction.turnRateDegreesPerSecond,
                    (interaction, parent) -> interaction.turnRateDegreesPerSecond = parent.turnRateDegreesPerSecond
            )
            .addValidator(Validators.min(0.0D))
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("ArrivalRadius", Codec.DOUBLE),
                    (interaction, value) -> interaction.arrivalRadius = value,
                    interaction -> interaction.arrivalRadius,
                    (interaction, parent) -> interaction.arrivalRadius = parent.arrivalRadius
            )
            .addValidator(Validators.greaterThan(0.0D))
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("LifetimeSeconds", Codec.DOUBLE),
                    (interaction, value) -> interaction.lifetimeSeconds = value,
                    interaction -> interaction.lifetimeSeconds,
                    (interaction, parent) -> interaction.lifetimeSeconds = parent.lifetimeSeconds
            )
            .addValidator(Validators.greaterThan(0.0D))
            .add()
            .build();

    private String modelId;
    private InteractionTarget source = InteractionTarget.USER;
    private InteractionTarget target = InteractionTarget.TARGET;
    private HomingVisualProjectileAnchor sourceAnchor = HomingVisualProjectileAnchor.BODY;
    private HomingVisualProjectileAnchor targetAnchor = HomingVisualProjectileAnchor.BODY;
    private double speed = 8.0D;
    private double turnRateDegreesPerSecond;
    private double arrivalRadius = 0.18D;
    private double lifetimeSeconds = 2.0D;

    protected TameworkLaunchHomingVisualProjectileInteraction() {
        super();
    }

    public TameworkLaunchHomingVisualProjectileInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type,
                            @Nonnull InteractionContext context,
                            @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> chainOwner = context.getEntity();
        if (commandBuffer == null || chainOwner == null || !chainOwner.isValid()) {
            fail(context);
            return;
        }

        Ref<EntityStore> sourceRef = resolve(source, context, chainOwner);
        Ref<EntityStore> targetRef = resolve(target, context, chainOwner);
        Vector3d origin = sourceRef == null
                ? null
                : CaptureChannelAnchorResolver.resolve(sourceRef, sourceAnchor, commandBuffer);
        UUID targetUuid = uuid(targetRef, commandBuffer);
        if (sourceRef == null || targetRef == null || origin == null || targetUuid == null) {
            fail(context);
            return;
        }

        HomingVisualProjectileSpec spec = new HomingVisualProjectileSpec(
                modelId,
                targetAnchor,
                speed,
                turnRateDegreesPerSecond,
                arrivalRadius,
                lifetimeSeconds
        );
        HomingVisualProjectileSpawner.SpawnResult result = HomingVisualProjectileSpawner.spawn(
                commandBuffer,
                origin,
                targetUuid,
                spec,
                null,
                uuid(sourceRef, commandBuffer),
                0L
        );
        if (result != HomingVisualProjectileSpawner.SpawnResult.SPAWNED) {
            fail(context);
        }
    }

    @Override
    protected void simulateFirstRun(@Nonnull InteractionType type,
                                    @Nonnull InteractionContext context,
                                    @Nonnull CooldownHandler cooldownHandler) {
    }

    @Nullable
    private static Ref<EntityStore> resolve(@Nullable InteractionTarget target,
                                            @Nonnull InteractionContext context,
                                            @Nonnull Ref<EntityStore> chainOwner) {
        InteractionTarget effective = target == null ? InteractionTarget.USER : target;
        Ref<EntityStore> ref = effective.getEntity(context, chainOwner);
        return ref != null && ref.isValid() ? ref : null;
    }

    @Nullable
    private static UUID uuid(@Nullable Ref<EntityStore> ref,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent component = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        return component == null ? null : component.getUuid();
    }

    private static void fail(@Nonnull InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }
}
