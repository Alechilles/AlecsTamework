package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.AvatarFlightApi;
import com.alechilles.alecstamework.api.AvatarFlightLoan;
import com.alechilles.alecstamework.api.AvatarFlightLoanEnded;
import com.alechilles.alecstamework.api.AvatarFlightLoanRequest;
import com.alechilles.alecstamework.api.AvatarFlightLoanResult;
import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightLoanListeners;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountLifecycleService;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountSessionComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountPreflight;
import com.alechilles.alecstamework.avatarflight.AvatarFlightVigourService;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationState;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Adapts {@link AvatarFlightMountLifecycleService} loans to the public {@link AvatarFlightApi}. */
final class AvatarFlightLoanApi implements AvatarFlightApi {
    private final AvatarFlightMountLifecycleService lifecycle = new AvatarFlightMountLifecycleService();

    /**
     * Only while the avatar-flight runtime runs: its systems are what end a loan on death,
     * disconnect, world change, restart and dismount.
     */
    @Override
    public boolean available() {
        Tamework plugin = Tamework.getInstance();
        TameworkRuntimeActivationState state = plugin == null ? null : plugin.getRuntimeActivationState();
        return state != null && state.isActive(TameworkRuntimeModule.AVATAR_FLIGHT);
    }

    @Nonnull
    @Override
    public AvatarFlightLoanResult lend(@Nonnull Store<EntityStore> store,
                                       @Nonnull Ref<EntityStore> player,
                                       @Nonnull AvatarFlightLoanRequest request) {
        if (!available()) {
            return result(AvatarFlightLoanResult.Status.UNAVAILABLE, "the avatar-flight runtime is not active");
        }
        UUID playerId = playerId(store, player);
        if (playerId == null) {
            return result(AvatarFlightLoanResult.Status.INVALID_PLAYER, "not a live player in this store");
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(request.configId());
        // A missing id resolves to the active config, so check that the one asked for was found.
        if (config.getId() == null || !config.getId().equalsIgnoreCase(request.configId()) || !config.isEnabled()) {
            return result(AvatarFlightLoanResult.Status.CONFIG_UNAVAILABLE, "no enabled config " + request.configId());
        }
        if (AvatarFlightMountPreflight.isPlayerBusy(store, player)) {
            return result(AvatarFlightLoanResult.Status.BUSY, "player is dead, flying or riding");
        }
        AvatarFlightMountLifecycleService.Result started = lifecycle.lend(store, player, playerId,
                request.lenderId(), config.getId(),
                request.returnX(), request.returnY(), request.returnZ(), request.returnYaw());
        return result(started.ok() ? AvatarFlightLoanResult.Status.STARTED : AvatarFlightLoanResult.Status.FAILED,
                started.message());
    }

    @Nonnull
    @Override
    public AvatarFlightLoanResult endLoan(@Nonnull Store<EntityStore> store,
                                          @Nonnull Ref<EntityStore> player,
                                          @Nonnull String lenderId) {
        UUID playerId = playerId(store, player);
        if (playerId == null) {
            return result(AvatarFlightLoanResult.Status.INVALID_PLAYER, "not a live player in this store");
        }
        if (session(store, player, lenderId) == null) {
            return result(AvatarFlightLoanResult.Status.NO_LOAN, "no loan from " + lenderId);
        }
        AvatarFlightMountLifecycleService.Result ended = lifecycle.end(
                store, player, playerId, AvatarFlightMountLifecycleService.EndReason.LENDER);
        return result(AvatarFlightLoanResult.Status.ENDED, ended.message());
    }

    @Nullable
    @Override
    public AvatarFlightLoan loan(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> player) {
        UUID playerId = playerId(store, player);
        AvatarFlightMountSessionComponent session = session(store, player, null);
        return playerId == null || session == null ? null
                : new AvatarFlightLoan(playerId, session.getLenderId(), session.getConfigId());
    }

    @Override
    public double addVigour(@Nonnull Store<EntityStore> store,
                            @Nonnull Ref<EntityStore> player,
                            @Nonnull String lenderId,
                            double charges) {
        ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
        AvatarFlightComponent flight = flightType == null || session(store, player, lenderId) == null
                ? null : store.getComponent(player, flightType);
        if (flight == null) {
            return 0.0;
        }
        AvatarFlightVigourService.State before = new AvatarFlightVigourService.State(
                flight.getVigourCharges(), flight.getLastVigourUpdateAtMs(), flight.getVigourRechargeBlockedUntilMs());
        // A loan has no companion, so its tuning is neutral, as the movement system resolves it.
        AvatarFlightVigourService.State after = AvatarFlightVigourService.grant(
                before, TwAvatarFlightConfig.resolve(flight.getConfigId()), null, charges);
        double added = after.charges() - before.charges();
        if (added <= 0.0) {
            return 0.0;
        }
        flight.setVigourCharges(after.charges());
        return added;
    }

    @Nonnull
    @Override
    public AutoCloseable onLoanEnded(@Nonnull Consumer<AvatarFlightLoanEnded> listener) {
        return AvatarFlightLoanListeners.register(listener);
    }

    void close() {
        AvatarFlightLoanListeners.clear();
    }

    /** The player's loan session, from this lender when one is given. */
    @Nullable
    private static AvatarFlightMountSessionComponent session(Store<EntityStore> store,
                                                             Ref<EntityStore> player,
                                                             @Nullable String lenderId) {
        ComponentType<EntityStore, AvatarFlightMountSessionComponent> sessionType =
                AvatarFlightMountSessionComponent.getComponentType();
        AvatarFlightMountSessionComponent session = sessionType == null || !player.isValid()
                ? null : store.getComponent(player, sessionType);
        if (session == null || !session.isLoan()) return null;
        return lenderId == null || session.getLenderId().equals(lenderId.trim()) ? session : null;
    }

    @Nullable
    private static UUID playerId(Store<EntityStore> store, Ref<EntityStore> player) {
        if (!player.isValid() || player.getStore() != store
                || store.getComponent(player, Player.getComponentType()) == null) {
            return null;
        }
        UUIDComponent uuid = store.getComponent(player, UUIDComponent.getComponentType());
        return uuid == null ? null : uuid.getUuid();
    }

    private static AvatarFlightLoanResult result(AvatarFlightLoanResult.Status status, String detail) {
        return new AvatarFlightLoanResult(status, detail);
    }
}
