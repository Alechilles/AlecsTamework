package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.alechilles.alecstamework.ui.TameworkAvatarFlightHud;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sends the compact avatar-flight vigour HUD for players with active flight state.
 */
public final class AvatarFlightHudSystem extends EntityTickingSystem<EntityStore> {
    private final ComponentType<EntityStore, AvatarFlightComponent> flightType;
    private final ComponentType<EntityStore, AvatarFlightInputComponent> inputType;
    private final ComponentType<EntityStore, AvatarFlightMountSessionComponent> mountSessionType;
    private final ComponentType<EntityStore, AvatarFlightSourceComponent> mountSourceType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final ComponentType<EntityStore, Player> playerType;
    private final Query<EntityStore> query;
    // Command and mount activators are not owned by this system, so the main-thread connection cache is shared.
    private static final Map<UUID, HudState> STATE_BY_PLAYER = new HashMap<>();
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, AvatarFlightMovementSystem.class)
    );

    public AvatarFlightHudSystem(@Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
                                 @Nonnull ComponentType<EntityStore, AvatarFlightInputComponent> inputType,
                                 @Nonnull ComponentType<EntityStore, AvatarFlightMountSessionComponent> mountSessionType,
                                 @Nonnull ComponentType<EntityStore, AvatarFlightSourceComponent> mountSourceType,
                                 @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType,
                                 @Nonnull ComponentType<EntityStore, Player> playerType) {
        this.flightType = flightType;
        this.inputType = inputType;
        this.mountSessionType = mountSessionType;
        this.mountSourceType = mountSourceType;
        this.uuidType = uuidType;
        this.playerType = playerType;
        this.query = Query.and(flightType, inputType, playerType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        AvatarFlightComponent flight = archetypeChunk.getComponent(index, flightType);
        AvatarFlightInputComponent input = archetypeChunk.getComponent(index, inputType);
        Player player = archetypeChunk.getComponent(index, playerType);
        if (ref == null || flight == null || input == null || player == null) {
            return;
        }
        UUID playerUuid = playerUuid(player);
        if (playerUuid == null) {
            return;
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        if (!config.getVigour().isHudEnabled()) {
            removeHud(playerUuid, player);
            return;
        }
        long now = System.currentTimeMillis();
        AvatarFlightProgressionTuning tuning = resolveProgressionTuning(ref, store, commandBuffer);
        AvatarFlightHudViewModel model = buildModel(flight, input, config, tuning, now);
        showOrRefresh(playerUuid, player, flight.getEnabledAtMs(), model,
                config.getVigour().getHudResendIntervalMs(), now);
    }

    @Nonnull
    static AvatarFlightHudViewModel buildModel(@Nonnull AvatarFlightComponent flight,
                                               @Nonnull AvatarFlightInputComponent input,
                                               @Nonnull TwAvatarFlightConfig config,
                                               @Nonnull AvatarFlightProgressionTuning tuning,
                                               long nowMs) {
        double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
                flight.getVelocityX(),
                flight.getVelocityY(),
                flight.getVelocityZ()
        );
        double speedRatio = AvatarFlightSpeedMetrics.speedRatio(horizontalSpeed, config);
        double maxCharges = config.getVigour().getMaxCharges() * tuning.vigourCapacityMultiplier();
        boolean groundedAtFull = flight.getMode() == AvatarFlightMode.GROUNDED
                && fullVigour(flight.getVigourCharges(), maxCharges);
        long maxChargeMs = config.getLaunch().getMaxChargeMs();
        boolean launchChargeVisible = config.getLaunch().isEnabled()
                && maxChargeMs > 0L
                && input.isLaunchCharging()
                && input.isOnGround();
        double launchChargeRatio = launchChargeVisible
                ? ratio(nowMs - input.getLaunchChargeStartedAtMs(), maxChargeMs)
                : 0.0;
        double launchMinChargeRatio = launchChargeVisible
                ? ratio(config.getLaunch().getMinChargeMs(), maxChargeMs)
                : 0.0;
        return AvatarFlightHudViewModel.visible(
                speedRatio,
                flight.getHudTargetSpeedRatio(),
                flight.getHudPitchRadians(),
                flight.getVigourCharges(),
                maxCharges,
                groundedAtFull,
                flight.getVigourRechargeMode(),
                launchChargeVisible,
                launchChargeRatio,
                launchMinChargeRatio,
                AvatarFlightHudViewModel.CombatGlyph.from(
                        config.getCombatAbility(AvatarFlightCombatAbilitySlot.ABILITY_2),
                        AvatarFlightCombatAbilitySlot.ABILITY_2,
                        nowMs,
                        flight.getNextAbility2CombatAtMs()),
                AvatarFlightHudViewModel.CombatGlyph.from(
                        config.getCombatAbility(AvatarFlightCombatAbilitySlot.ABILITY_3),
                        AvatarFlightCombatAbilitySlot.ABILITY_3,
                        nowMs,
                        flight.getNextAbility3CombatAtMs())
        );
    }

    @Nonnull
    private AvatarFlightProgressionTuning resolveProgressionTuning(
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        AvatarFlightMountSessionComponent session = commandBuffer.getComponent(playerRef, mountSessionType);
        Ref<EntityStore> sourceRef = resolveSourceRef(store, session);
        AvatarFlightSourceComponent source = sourceRef == null ? null : store.getComponent(sourceRef, mountSourceType);
        UUIDComponent playerUuid = commandBuffer.getComponent(playerRef, uuidType);
        AvatarFlightMovementSystem.FlightXpSourceResolution sourceResolution = sourceRef == null || !sourceRef.isValid()
                ? null : AvatarFlightMovementSystem.resolveValidatedFlightXpSource(
                session,
                sourceRef,
                source,
                playerUuid == null || playerUuid.getUuid() == null ? null : playerUuid.getUuid().toString(),
                store.getExternalData().getWorld().getName()
        );
        return sourceResolution == null
                ? AvatarFlightProgressionTuning.neutral()
                : AvatarFlightProgressionTuning.resolve(sourceResolution.recipient(), store);
    }

    @Nullable
    private static Ref<EntityStore> resolveSourceRef(@Nonnull Store<EntityStore> store,
                                                      @Nullable AvatarFlightMountSessionComponent session) {
        String activeWorld = store.getExternalData().getWorld().getName();
        if (session == null || activeWorld == null || !activeWorld.equals(session.getSourceWorld())
                || session.getSourceNpcUuid().isBlank()) {
            return null;
        }
        try {
            Ref<EntityStore> sourceRef = store.getExternalData().getWorld().getEntityRef(
                    UUID.fromString(session.getSourceNpcUuid()));
            return sourceRef != null && sourceRef.isValid() ? sourceRef : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void showOrRefresh(@Nonnull UUID playerUuid,
                               @Nonnull Player player,
                               long enabledAtMs,
                               @Nonnull AvatarFlightHudViewModel model,
                               long resendIntervalMs,
                               long now) {
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || player.getHudManager() == null) {
            return;
        }
        HudState previous = STATE_BY_PLAYER.get(playerUuid);
        boolean newSession = previous == null || previous.enabledAtMs() != enabledAtMs;
        TameworkAvatarFlightHud hud = previous != null && previous.playerRef() == playerRef
                ? previous.hud()
                : null;
        if (hud == null) {
            hud = new TameworkAvatarFlightHud(playerRef, model);
            player.getHudManager().addCustomHud(playerRef, hud);
            STATE_BY_PLAYER.put(playerUuid, new HudState(enabledAtMs, playerRef, hud, model, now));
            return;
        }
        if (newSession) {
            hud.refresh(model);
            STATE_BY_PLAYER.put(playerUuid, new HudState(enabledAtMs, playerRef, hud, model, now));
            return;
        }
        long effectiveResendIntervalMs = hudRefreshIntervalMs(previous.model(), model, resendIntervalMs);
        if (!shouldRefresh(previous.model(), previous.lastSentAtMs(), model, now, effectiveResendIntervalMs)) {
            return;
        }
        hud.refresh(model);
        STATE_BY_PLAYER.put(playerUuid, new HudState(enabledAtMs, playerRef, hud, model, now));
    }

    private void removeHud(@Nonnull UUID playerUuid, @Nonnull Player player) {
        TameworkAvatarFlightHud.removeFrom(player);
        STATE_BY_PLAYER.remove(playerUuid);
    }

    static void hideHud(@Nonnull UUID playerUuid, @Nullable Player player) {
        HudState previous = STATE_BY_PLAYER.get(playerUuid);
        PlayerRef playerRef = player == null ? null : player.getPlayerRef();
        if (previous == null || previous.playerRef() != playerRef) {
            return;
        }
        AvatarFlightHudViewModel hidden = AvatarFlightHudViewModel.hidden();
        previous.hud().refresh(hidden);
        STATE_BY_PLAYER.put(playerUuid, new HudState(
                previous.enabledAtMs(), playerRef, previous.hud(), hidden, System.currentTimeMillis()));
    }

    static void forgetHud(@Nonnull UUID playerUuid) {
        STATE_BY_PLAYER.remove(playerUuid);
    }

    static boolean shouldRefresh(@Nullable AvatarFlightHudViewModel previousModel,
                                 long lastSentAtMs,
                                 @Nonnull AvatarFlightHudViewModel model,
                                 long now,
                                 long resendIntervalMs) {
        return previousModel != null
                && !previousModel.equals(model)
                && now - lastSentAtMs >= Math.max(1L, resendIntervalMs);
    }

    static long hudRefreshIntervalMs(@Nonnull AvatarFlightHudViewModel previousModel,
                                     @Nonnull AvatarFlightHudViewModel model,
                                     long configuredIntervalMs) {
        long normalizedIntervalMs = Math.max(1L, configuredIntervalMs);
        return previousModel.hasActiveCombatCooldown() || model.hasActiveCombatCooldown()
                ? Math.min(1_000L, normalizedIntervalMs)
                : normalizedIntervalMs;
    }

    private static boolean fullVigour(double charges, double maxCharges) {
        return Double.isFinite(maxCharges) && maxCharges > 0.0
                && Double.isFinite(charges)
                && charges >= maxCharges - 0.0001;
    }

    private static double ratio(long value, long max) {
        if (max <= 0L) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) value / (double) max));
    }

    @Nullable
    private static UUID playerUuid(@Nonnull Player player) {
        PlayerRef playerRef = player.getPlayerRef();
        return playerRef == null ? null : playerRef.getUuid();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    private record HudState(long enabledAtMs,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull TameworkAvatarFlightHud hud,
                            @Nonnull AvatarFlightHudViewModel model,
                            long lastSentAtMs) {
    }
}
