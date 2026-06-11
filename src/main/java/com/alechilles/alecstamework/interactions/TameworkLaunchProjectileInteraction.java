package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileComponent;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.asset.type.projectile.config.Projectile;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionTarget;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Custom interaction that launches a projectile using a solved high-angle ballistic arc.
 */
public class TameworkLaunchProjectileInteraction extends SimpleInstantInteraction {
    private static final double MIN_HORIZONTAL_DISTANCE = 1.0e-4;
    private static final double MIN_POSITIVE_VALUE = 1.0e-6;

    public static final BuilderCodec<TameworkLaunchProjectileInteraction> CODEC = BuilderCodec.builder(
            TameworkLaunchProjectileInteraction.class,
            TameworkLaunchProjectileInteraction::new,
            SimpleInstantInteraction.CODEC
    )
            .documentation("Launches a projectile using a solved high-angle ballistic arc.")
            .<String>appendInherited(
                    new KeyedCodec<>("ProjectileId", Codec.STRING),
                    (interaction, value) -> interaction.projectileId = value,
                    interaction -> interaction.projectileId,
                    (interaction, parent) -> interaction.projectileId = parent.projectileId
            )
            .addValidator(Validators.nonNull())
            .addValidator(Projectile.VALIDATOR_CACHE.getValidator().late())
            .add()
            .<InteractionTarget>appendInherited(
                    new KeyedCodec<>("Target", InteractionTarget.CODEC),
                    (interaction, value) -> interaction.target = value,
                    interaction -> interaction.target,
                    (interaction, parent) -> interaction.target = parent.target
            )
            .add()
            .<String>appendInherited(
                    new KeyedCodec<>("TargetSlot", Codec.STRING),
                    (interaction, value) -> interaction.targetSlot = value,
                    interaction -> interaction.targetSlot,
                    (interaction, parent) -> interaction.targetSlot = parent.targetSlot
            )
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("YawSpreadDegrees", Codec.DOUBLE),
                    (interaction, value) -> interaction.yawSpreadDegrees = value,
                    interaction -> interaction.yawSpreadDegrees,
                    (interaction, parent) -> interaction.yawSpreadDegrees = parent.yawSpreadDegrees
            )
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("PitchSpreadDegrees", Codec.DOUBLE),
                    (interaction, value) -> interaction.pitchSpreadDegrees = value,
                    interaction -> interaction.pitchSpreadDegrees,
                    (interaction, parent) -> interaction.pitchSpreadDegrees = parent.pitchSpreadDegrees
            )
            .add()
            .<Boolean>appendInherited(
                    new KeyedCodec<>("FailIfNoSolution", Codec.BOOLEAN),
                    (interaction, value) -> interaction.failIfNoSolution = value,
                    interaction -> interaction.failIfNoSolution,
                    (interaction, parent) -> interaction.failIfNoSolution = parent.failIfNoSolution
            )
            .add()
            .<TrajectoryMode>appendInherited(
                    new KeyedCodec<>("TrajectoryMode", new EnumCodec<>(TrajectoryMode.class)),
                    (interaction, value) -> interaction.trajectoryMode = value,
                    interaction -> interaction.trajectoryMode,
                    (interaction, parent) -> interaction.trajectoryMode = parent.trajectoryMode
            )
            .add()
            .<LaunchPositionOffsetSettings>appendInherited(
                    new KeyedCodec<>("LaunchPositionOffset", LaunchPositionOffsetSettings.CODEC),
                    (interaction, value) -> interaction.launchPositionOffset = value,
                    interaction -> interaction.launchPositionOffset,
                    (interaction, parent) -> interaction.launchPositionOffset = parent.launchPositionOffset
            )
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("RandomAroundSourceMinRadius", Codec.DOUBLE),
                    (interaction, value) -> interaction.randomAroundSourceMinRadius = value,
                    interaction -> interaction.randomAroundSourceMinRadius,
                    (interaction, parent) -> interaction.randomAroundSourceMinRadius = parent.randomAroundSourceMinRadius
            )
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("RandomAroundSourceMaxRadius", Codec.DOUBLE),
                    (interaction, value) -> interaction.randomAroundSourceMaxRadius = value,
                    interaction -> interaction.randomAroundSourceMaxRadius,
                    (interaction, parent) -> interaction.randomAroundSourceMaxRadius = parent.randomAroundSourceMaxRadius
            )
            .add()
            .<Double>appendInherited(
                    new KeyedCodec<>("RandomAroundSourceVerticalOffset", Codec.DOUBLE),
                    (interaction, value) -> interaction.randomAroundSourceVerticalOffset = value,
                    interaction -> interaction.randomAroundSourceVerticalOffset,
                    (interaction, parent) -> interaction.randomAroundSourceVerticalOffset = parent.randomAroundSourceVerticalOffset
            )
            .add()
            .<ImpactEffectSettings>appendInherited(
                    new KeyedCodec<>("ImpactEffect", ImpactEffectSettings.CODEC),
                    (interaction, value) -> interaction.impactEffect = value,
                    interaction -> interaction.impactEffect,
                    (interaction, parent) -> interaction.impactEffect = parent.impactEffect
            )
            .add()
            .<LingeringHazardSettings>appendInherited(
                    new KeyedCodec<>("LingeringHazard", LingeringHazardSettings.CODEC),
                    (interaction, value) -> interaction.lingeringHazard = value,
                    interaction -> interaction.lingeringHazard,
                    (interaction, parent) -> interaction.lingeringHazard = parent.lingeringHazard
            )
            .add()
            .build();

    private String projectileId;
    private InteractionTarget target = InteractionTarget.TARGET;
    @Nullable
    private String targetSlot;
    private double yawSpreadDegrees = 0.0;
    private double pitchSpreadDegrees = 0.0;
    private boolean failIfNoSolution = true;
    private TrajectoryMode trajectoryMode = TrajectoryMode.HIGH_ANGLE;
    @Nullable
    private LaunchPositionOffsetSettings launchPositionOffset;
    private double randomAroundSourceMinRadius = 0.0;
    private double randomAroundSourceMaxRadius = 0.0;
    private double randomAroundSourceVerticalOffset = 0.0;
    @Nullable
    private ImpactEffectSettings impactEffect;
    @Nullable
    private LingeringHazardSettings lingeringHazard;

    protected TameworkLaunchProjectileInteraction() {
        super();
    }

    public TameworkLaunchProjectileInteraction(String id) {
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
        Ref<EntityStore> sourceRef = context.getEntity();
        if (commandBuffer == null || sourceRef == null || !sourceRef.isValid()) {
            fail(context);
            return;
        }

        Projectile projectile = Projectile.getAssetMap().getAsset(this.projectileId);
        if (projectile == null) {
            fail(context);
            return;
        }

        double muzzleVelocity = projectile.getMuzzleVelocity();
        double gravity = projectile.getGravity();
        if (muzzleVelocity <= MIN_POSITIVE_VALUE || gravity <= MIN_POSITIVE_VALUE) {
            fail(context);
            return;
        }

        Transform sourceLook = TargetUtil.getLook(sourceRef, commandBuffer);
        Vector3d launchPosition = resolveLaunchPosition(sourceLook);
        Vector3d targetPosition = resolveTargetPosition(context, sourceRef, commandBuffer, sourceLook);
        if (targetPosition == null) {
            fail(context);
            return;
        }

        float yaw = PhysicsMath.headingFromDirection(
                targetPosition.x - launchPosition.x,
                targetPosition.z - launchPosition.z
        );
        Float pitch = this.trajectoryMode == TrajectoryMode.DIRECT
                ? solveDirectPitch(launchPosition, targetPosition)
                : solveHighAnglePitch(launchPosition, targetPosition, muzzleVelocity, gravity);
        if (pitch == null) {
            fail(context);
            return;
        }

        yaw += randomSpreadRadians(this.yawSpreadDegrees);
        pitch += randomSpreadRadians(this.pitchSpreadDegrees);

        UUIDComponent sourceUuidComponent = commandBuffer.getComponent(sourceRef, UUIDComponent.getComponentType());
        if (sourceUuidComponent == null) {
            fail(context);
            return;
        }

        UUID sourceUuid = sourceUuidComponent.getUuid();
        TimeResource timeResource = commandBuffer.getResource(TimeResource.getResourceType());
        Rotation3f lookRotation = new Rotation3f(pitch, yaw, sourceLook.getRotation().roll());
        Holder<EntityStore> holder = ProjectileComponent.assembleDefaultProjectile(timeResource, this.projectileId, launchPosition, lookRotation);
        ProjectileComponent projectileComponent = holder.getComponent(ProjectileComponent.getComponentType());
        if (projectileComponent == null) {
            fail(context);
            return;
        }

        holder.ensureComponent(Intangible.getComponentType());
        if (projectileComponent.getProjectile() == null) {
            projectileComponent.initialize();
            if (projectileComponent.getProjectile() == null) {
                fail(context);
                return;
            }
        }

        projectileComponent.shoot(
                holder,
                sourceUuid,
                launchPosition.x,
                launchPosition.y,
                launchPosition.z,
                yaw,
                pitch
        );
        TameworkProjectileImpactEffectComponent impactEffectComponent = buildImpactEffectComponent(sourceUuid);
        if (impactEffectComponent != null && TameworkProjectileImpactEffectComponent.getComponentType() != null) {
            holder.putComponent(TameworkProjectileImpactEffectComponent.getComponentType(), impactEffectComponent);
        }
        TameworkLingeringHazardProjectileComponent lingeringHazardComponent = buildLingeringHazardComponent(sourceUuid);
        if (lingeringHazardComponent != null && TameworkLingeringHazardProjectileComponent.getComponentType() != null) {
            holder.putComponent(TameworkLingeringHazardProjectileComponent.getComponentType(), lingeringHazardComponent);
        }
        commandBuffer.addEntity(holder, AddReason.SPAWN);
    }

    @Override
    protected void simulateFirstRun(@Nonnull InteractionType type,
                                    @Nonnull InteractionContext context,
                                    @Nonnull CooldownHandler cooldownHandler) {
    }

    private Vector3d resolveLaunchPosition(@Nonnull Transform sourceLook) {
        if (this.launchPositionOffset == null || this.launchPositionOffset.isZero()) {
            return sourceLook.getPosition();
        }
        return TameworkLaunchOriginService.applyOffset(
                sourceLook.getPosition(),
                sourceLook.getRotation().yaw(),
                this.launchPositionOffset.getX(),
                this.launchPositionOffset.getY(),
                this.launchPositionOffset.getZ()
        );
    }

    @Nullable
    private Vector3d resolveTargetPosition(@Nonnull InteractionContext context,
                                           @Nonnull Ref<EntityStore> sourceRef,
                                           @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                           @Nonnull Transform sourceLook) {
        if (this.randomAroundSourceMaxRadius > 0.0) {
            return resolveRandomAroundSourceTarget(sourceRef, commandBuffer, sourceLook);
        }

        Ref<EntityStore> targetRef = resolveTargetRef(context, sourceRef, commandBuffer);
        if (targetRef == null || !targetRef.isValid()) {
            return null;
        }
        return resolveAimPosition(targetRef, commandBuffer);
    }

    @Nullable
    private Ref<EntityStore> resolveTargetRef(@Nonnull InteractionContext context,
                                              @Nonnull Ref<EntityStore> sourceRef,
                                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (this.targetSlot != null) {
            if (EntityUtils.getEntity(sourceRef, commandBuffer) instanceof NPCEntity npcEntity && npcEntity.getRole() != null) {
                Ref<EntityStore> markedTarget = npcEntity.getRole().getMarkedEntitySupport().getMarkedEntityRef(this.targetSlot);
                if (markedTarget != null && markedTarget.isValid()) {
                    return markedTarget;
                }
            }
        }

        InteractionTarget effectiveTarget = this.target != null ? this.target : InteractionTarget.TARGET;
        Ref<EntityStore> targetRef = effectiveTarget.getEntity(context, sourceRef);
        return targetRef != null && targetRef.isValid() ? targetRef : null;
    }

    @Nullable
    private Vector3d resolveRandomAroundSourceTarget(@Nonnull Ref<EntityStore> sourceRef,
                                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                     @Nonnull Transform sourceLook) {
        TransformComponent transformComponent = commandBuffer.getComponent(sourceRef, TransformComponent.getComponentType());
        Vector3d center = transformComponent != null ? transformComponent.getPosition() : sourceLook.getPosition();

        double maxRadius = this.randomAroundSourceMaxRadius;
        if (maxRadius <= 0.0) {
            return null;
        }

        double minRadius = Math.max(0.0, this.randomAroundSourceMinRadius);
        if (minRadius > maxRadius) {
            return null;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0.0, Math.PI * 2.0);
        double minRadiusSquared = minRadius * minRadius;
        double maxRadiusSquared = maxRadius * maxRadius;
        double radius = Math.sqrt(random.nextDouble(minRadiusSquared, maxRadiusSquared));
        return new Vector3d(
                center.x + Math.cos(angle) * radius,
                center.y + this.randomAroundSourceVerticalOffset,
                center.z + Math.sin(angle) * radius
        );
    }

    @Nullable
    private Vector3d resolveAimPosition(@Nonnull Ref<EntityStore> targetRef,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TransformComponent transformComponent = commandBuffer.getComponent(targetRef, TransformComponent.getComponentType());
        if (transformComponent == null) {
            return null;
        }

        double eyeHeight = 0.0;
        ModelComponent modelComponent = commandBuffer.getComponent(targetRef, ModelComponent.getComponentType());
        if (modelComponent != null) {
            eyeHeight = modelComponent.getModel().getEyeHeight(targetRef, commandBuffer);
        }

        Vector3d position = transformComponent.getPosition();
        return new Vector3d(position.x, position.y + eyeHeight, position.z);
    }

    @Nullable
    private Float solveHighAnglePitch(@Nonnull Vector3d sourcePosition,
                                      @Nonnull Vector3d targetPosition,
                                      double muzzleVelocity,
                                      double gravity) {
        double dx = targetPosition.x - sourcePosition.x;
        double dz = targetPosition.z - sourcePosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDelta = targetPosition.y - sourcePosition.y;

        if (horizontalDistance <= MIN_HORIZONTAL_DISTANCE) {
            if (verticalDelta <= 0.0) {
                return null;
            }
            double maxHeight = (muzzleVelocity * muzzleVelocity) / (2.0 * gravity);
            return maxHeight + MIN_POSITIVE_VALUE >= verticalDelta ? (float) (Math.PI / 2.0) : null;
        }

        double speedSquared = muzzleVelocity * muzzleVelocity;
        double discriminant = speedSquared * speedSquared
                - gravity * (gravity * horizontalDistance * horizontalDistance + 2.0 * verticalDelta * speedSquared);
        if (discriminant < 0.0) {
            return null;
        }

        double tangent = (speedSquared + Math.sqrt(discriminant)) / (gravity * horizontalDistance);
        if (!Double.isFinite(tangent)) {
            return null;
        }

        float pitch = (float) Math.atan(tangent);
        return Float.isFinite(pitch) ? pitch : null;
    }

    @Nullable
    private Float solveDirectPitch(@Nonnull Vector3d sourcePosition, @Nonnull Vector3d targetPosition) {
        double dx = targetPosition.x - sourcePosition.x;
        double dz = targetPosition.z - sourcePosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDelta = targetPosition.y - sourcePosition.y;
        if (horizontalDistance <= MIN_HORIZONTAL_DISTANCE && Math.abs(verticalDelta) <= MIN_POSITIVE_VALUE) {
            return 0.0F;
        }
        float pitch = (float) Math.atan2(verticalDelta, Math.max(horizontalDistance, MIN_HORIZONTAL_DISTANCE));
        return Float.isFinite(pitch) ? pitch : null;
    }

    private float randomSpreadRadians(double spreadDegrees) {
        if (spreadDegrees <= 0.0) {
            return 0.0F;
        }
        double spreadRadians = Math.toRadians(spreadDegrees);
        return (float) ThreadLocalRandom.current().nextDouble(-spreadRadians, spreadRadians);
    }

    @Nullable
    private TameworkProjectileImpactEffectComponent buildImpactEffectComponent(@Nonnull UUID sourceUuid) {
        if (this.impactEffect == null || !this.impactEffect.isEnabled()) {
            return null;
        }
        return new TameworkProjectileImpactEffectComponent(
                this.impactEffect.getRadius(),
                this.impactEffect.getEffectId(),
                this.impactEffect.isExcludeSource(),
                sourceUuid.toString()
        );
    }

    @Nullable
    private TameworkLingeringHazardProjectileComponent buildLingeringHazardComponent(@Nonnull UUID sourceUuid) {
        if (this.lingeringHazard == null || !this.lingeringHazard.isEnabled()) {
            return null;
        }
        return new TameworkLingeringHazardProjectileComponent(
                this.lingeringHazard.getRadius(),
                this.lingeringHazard.getDurationSeconds(),
                this.lingeringHazard.getTickIntervalSeconds(),
                this.lingeringHazard.getDamagePerTick(),
                this.lingeringHazard.isExcludeSource(),
                this.lingeringHazard.getSourceTypeId(),
                this.lingeringHazard.getEffectId(),
                sourceUuid.toString()
        );
    }

    private void fail(@Nonnull InteractionContext context) {
        if (this.failIfNoSolution) {
            context.getState().state = InteractionState.Failed;
        }
    }

    public enum TrajectoryMode {
        DIRECT,
        HIGH_ANGLE
    }

    public static final class LaunchPositionOffsetSettings {
        public static final BuilderCodec<LaunchPositionOffsetSettings> CODEC = BuilderCodec.builder(
                LaunchPositionOffsetSettings.class,
                LaunchPositionOffsetSettings::new
        )
                .<Double>append(
                        new KeyedCodec<>("X", Codec.DOUBLE),
                        (settings, value) -> settings.x = value == null ? 0.0 : value,
                        settings -> settings.x
                )
                .add()
                .<Double>append(
                        new KeyedCodec<>("Y", Codec.DOUBLE),
                        (settings, value) -> settings.y = value == null ? 0.0 : value,
                        settings -> settings.y
                )
                .add()
                .<Double>append(
                        new KeyedCodec<>("Z", Codec.DOUBLE),
                        (settings, value) -> settings.z = value == null ? 0.0 : value,
                        settings -> settings.z
                )
                .add()
                .build();

        private double x;
        private double y;
        private double z;

        public double getX() {
            return this.x;
        }

        public double getY() {
            return this.y;
        }

        public double getZ() {
            return this.z;
        }

        private boolean isZero() {
            return this.x == 0.0 && this.y == 0.0 && this.z == 0.0;
        }
    }

    public static final class ImpactEffectSettings {
        public static final BuilderCodec<ImpactEffectSettings> CODEC = BuilderCodec.builder(
                ImpactEffectSettings.class,
                ImpactEffectSettings::new
        )
                .<String>append(
                        new KeyedCodec<>("EffectId", Codec.STRING),
                        (settings, value) -> settings.effectId = value,
                        settings -> settings.effectId
                )
                .add()
                .<Double>append(
                        new KeyedCodec<>("Radius", Codec.DOUBLE),
                        (settings, value) -> settings.radius = value == null ? 0.0 : value,
                        settings -> settings.radius
                )
                .add()
                .<Boolean>append(
                        new KeyedCodec<>("ExcludeSource", Codec.BOOLEAN),
                        (settings, value) -> settings.excludeSource = value == null || value,
                        settings -> settings.excludeSource
                )
                .add()
                .build();

        private String effectId;
        private double radius;
        private boolean excludeSource = true;

        public ImpactEffectSettings() {
        }

        public boolean isEnabled() {
            return effectId != null && !effectId.isBlank() && getRadius() > 0.0;
        }

        public String getEffectId() {
            return effectId;
        }

        public double getRadius() {
            return sanitizePositive(radius, 0.0);
        }

        public boolean isExcludeSource() {
            return excludeSource;
        }

        private static double sanitizePositive(double value, double fallback) {
            if (!Double.isFinite(value) || value <= 0.0) {
                return fallback;
            }
            return value;
        }
    }

    public static final class LingeringHazardSettings {
        public static final BuilderCodec<LingeringHazardSettings> CODEC = BuilderCodec.builder(
                LingeringHazardSettings.class,
                LingeringHazardSettings::new
        )
                .<Double>append(
                        new KeyedCodec<>("Radius", Codec.DOUBLE),
                        (settings, value) -> settings.radius = value == null ? 0.0 : value,
                        settings -> settings.radius
                )
                .add()
                .<Double>append(
                        new KeyedCodec<>("DurationSeconds", Codec.DOUBLE),
                        (settings, value) -> settings.durationSeconds = value == null ? 0.0 : value,
                        settings -> settings.durationSeconds
                )
                .add()
                .<Double>append(
                        new KeyedCodec<>("TickIntervalSeconds", Codec.DOUBLE),
                        (settings, value) -> settings.tickIntervalSeconds = value == null ? 0.0 : value,
                        settings -> settings.tickIntervalSeconds
                )
                .add()
                .<Double>append(
                        new KeyedCodec<>("DamagePerTick", Codec.DOUBLE),
                        (settings, value) -> settings.damagePerTick = value == null ? 0.0 : value,
                        settings -> settings.damagePerTick
                )
                .add()
                .<Boolean>append(
                        new KeyedCodec<>("ExcludeSource", Codec.BOOLEAN),
                        (settings, value) -> settings.excludeSource = value == null || value,
                        settings -> settings.excludeSource
                )
                .add()
                .<String>append(
                        new KeyedCodec<>("SourceTypeId", Codec.STRING),
                        (settings, value) -> settings.sourceTypeId = value,
                        settings -> settings.sourceTypeId
                )
                .add()
                .<String>append(
                        new KeyedCodec<>("EffectId", Codec.STRING),
                        (settings, value) -> settings.effectId = value,
                        settings -> settings.effectId
                )
                .add()
                .build();

        private double radius;
        private double durationSeconds;
        private double tickIntervalSeconds;
        private double damagePerTick;
        private boolean excludeSource = true;
        private String sourceTypeId = "tamework.lingering_hazard";
        private String effectId;

        public LingeringHazardSettings() {
        }

        public boolean isEnabled() {
            return getRadius() > 0.0 && getDurationSeconds() > 0.0 && getTickIntervalSeconds() > 0.0 && getDamagePerTick() > 0.0;
        }

        public double getRadius() {
            return sanitizePositive(radius, 0.0);
        }

        public double getDurationSeconds() {
            return sanitizePositive(durationSeconds, 0.0);
        }

        public double getTickIntervalSeconds() {
            return sanitizePositive(tickIntervalSeconds, 0.0);
        }

        public double getDamagePerTick() {
            return sanitizePositive(damagePerTick, 0.0);
        }

        public boolean isExcludeSource() {
            return excludeSource;
        }

        public String getSourceTypeId() {
            return sourceTypeId == null || sourceTypeId.isBlank() ? "tamework.lingering_hazard" : sourceTypeId;
        }

        public String getEffectId() {
            return effectId;
        }

        private static double sanitizePositive(double value, double fallback) {
            if (!Double.isFinite(value) || value <= 0.0) {
                return fallback;
            }
            return value;
        }
    }
}
