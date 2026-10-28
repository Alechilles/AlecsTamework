package com.alechilles.alecstamework.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lent avatar flight: another plugin turns a player into a flyer from a flight config, with no
 * companion and no held item, and gets them back on the ground at a point it chose. Advertised by
 * {@link TameworkApiCapability#AVATAR_FLIGHT_LOANS} while Tamework's avatar-flight runtime is
 * active, which it is when the server has an enabled flight config besides Tamework's default (a
 * lender that ships its own config qualifies).
 *
 * <p>Every method runs on the player's world thread, with that world's store, and not from inside an
 * ECS system or interaction callback: use {@code world.execute}. A loan is never
 * restored: a restart, disconnect, death, world change or the player's own dismount ends it and tells
 * {@link #onLoanEnded} listeners. Every ending except death and a world change puts the player at
 * the return point. Tamework's flight HUD,
 * Vigour and controls apply as in any flight; companion flight XP does not, since there is no
 * companion.</p>
 */
public interface AvatarFlightApi {
    /** Whether loans can be made. */
    boolean available();

    /** Starts a loan. Fails when the player is dead, flying or riding, or the config is not loaded. */
    @Nonnull
    AvatarFlightLoanResult lend(@Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> player,
                                @Nonnull AvatarFlightLoanRequest request);

    /** Ends this lender's loan and puts the player at its return point. */
    @Nonnull
    AvatarFlightLoanResult endLoan(@Nonnull Store<EntityStore> store,
                                   @Nonnull Ref<EntityStore> player,
                                   @Nonnull String lenderId);

    /** The player's current loan, from any lender, or null when they are not on one. */
    @Nullable
    AvatarFlightLoan loan(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> player);

    /**
     * Adds Vigour charges to this lender's loan, up to the config's maximum. Returns the charges
     * actually added: 0 when there is no such loan, the flight is full, or Vigour is not limited.
     */
    double addVigour(@Nonnull Store<EntityStore> store,
                     @Nonnull Ref<EntityStore> player,
                     @Nonnull String lenderId,
                     double charges);

    /**
     * Calls the listener on the world thread after each loan ends, for every reason including
     * {@link #endLoan}. Returns a handle that unregisters it.
     */
    @Nonnull
    AutoCloseable onLoanEnded(@Nonnull Consumer<AvatarFlightLoanEnded> listener);

    /** Returns a shared unavailable facade for older and degraded API compositions. */
    @Nonnull
    static AvatarFlightApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Shared fail-closed implementation. */
    final class UnavailableHolder {
        private static final AvatarFlightLoanResult UNAVAILABLE = new AvatarFlightLoanResult(
                AvatarFlightLoanResult.Status.UNAVAILABLE, "avatar flight loans are not available");
        private static final AvatarFlightApi INSTANCE = new AvatarFlightApi() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public AvatarFlightLoanResult lend(Store<EntityStore> store, Ref<EntityStore> player,
                                               AvatarFlightLoanRequest request) {
                return UNAVAILABLE;
            }

            @Override
            public AvatarFlightLoanResult endLoan(Store<EntityStore> store, Ref<EntityStore> player,
                                                  String lenderId) {
                return UNAVAILABLE;
            }

            @Override
            public AvatarFlightLoan loan(Store<EntityStore> store, Ref<EntityStore> player) {
                return null;
            }

            @Override
            public double addVigour(Store<EntityStore> store, Ref<EntityStore> player,
                                    String lenderId, double charges) {
                return 0.0;
            }

            @Override
            public AutoCloseable onLoanEnded(Consumer<AvatarFlightLoanEnded> listener) {
                Objects.requireNonNull(listener, "listener");
                return () -> { };
            }
        };

        private UnavailableHolder() {
        }
    }
}
