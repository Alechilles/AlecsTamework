package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
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
    private final ComponentType<EntityStore, Player> playerType;
    private final Query<EntityStore> query;
    private final Map<UUID, HudState> stateByPlayer = new HashMap<>();
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, AvatarFlightMovementSystem.class)
    );

    public AvatarFlightHudSystem(@Nonnull ComponentType<EntityStore, AvatarFlightComponent> flightType,
                                 @Nonnull ComponentType<EntityStore, Player> playerType) {
        this.flightType = flightType;
        this.playerType = playerType;
        this.query = Query.and(flightType, playerType);
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        AvatarFlightComponent flight = archetypeChunk.getComponent(index, flightType);
        Player player = archetypeChunk.getComponent(index, playerType);
        if (ref == null || flight == null || player == null) {
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
        AvatarFlightHudViewModel model = buildModel(flight, config);
        showOrRefresh(playerUuid, player, flight.getEnabledAtMs(), model, config.getVigour().getHudResendIntervalMs());
    }

    @Nonnull
    private static AvatarFlightHudViewModel buildModel(@Nonnull AvatarFlightComponent flight,
                                                       @Nonnull TwAvatarFlightConfig config) {
        double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
                flight.getVelocityX(),
                flight.getVelocityY(),
                flight.getVelocityZ()
        );
        double speedRatio = AvatarFlightSpeedMetrics.speedRatio(horizontalSpeed, config);
        double maxCharges = config.getVigour().getMaxCharges();
        boolean groundedAtFull = flight.getMode() == AvatarFlightMode.GROUNDED
                && fullVigour(flight.getVigourCharges(), maxCharges);
        return AvatarFlightHudViewModel.visible(
                speedRatio,
                flight.getVigourCharges(),
                maxCharges,
                groundedAtFull,
                flight.getVigourRechargeMode()
        );
    }

    private void showOrRefresh(@Nonnull UUID playerUuid,
                               @Nonnull Player player,
                               long enabledAtMs,
                               @Nonnull AvatarFlightHudViewModel model,
                               long resendIntervalMs) {
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || player.getHudManager() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        HudState previous = stateByPlayer.get(playerUuid);
        boolean newSession = previous == null || previous.enabledAtMs() != enabledAtMs;
        TameworkAvatarFlightHud hud = newSession ? null : previous.hud();
        if (hud == null) {
            hud = new TameworkAvatarFlightHud(playerRef, model);
            player.getHudManager().addCustomHud(playerRef, hud);
            stateByPlayer.put(playerUuid, new HudState(enabledAtMs, hud, model, now));
            return;
        }
        if (!shouldRefresh(previous, model, now, resendIntervalMs)) {
            return;
        }
        hud.refresh(model);
        stateByPlayer.put(playerUuid, new HudState(enabledAtMs, hud, model, now));
    }

    private void removeHud(@Nonnull UUID playerUuid, @Nonnull Player player) {
        TameworkAvatarFlightHud.removeFrom(player);
        stateByPlayer.remove(playerUuid);
    }

    private static boolean shouldRefresh(@Nullable HudState previous,
                                         @Nonnull AvatarFlightHudViewModel model,
                                         long now,
                                         long resendIntervalMs) {
        if (previous == null || !previous.model().equals(model)) {
            return true;
        }
        return now - previous.lastSentAtMs() >= Math.max(1L, resendIntervalMs);
    }

    private static boolean fullVigour(double charges, double maxCharges) {
        return Double.isFinite(maxCharges) && maxCharges > 0.0
                && Double.isFinite(charges)
                && charges >= maxCharges - 0.0001;
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
                            @Nonnull TameworkAvatarFlightHud hud,
                            @Nonnull AvatarFlightHudViewModel model,
                            long lastSentAtMs) {
    }
}
