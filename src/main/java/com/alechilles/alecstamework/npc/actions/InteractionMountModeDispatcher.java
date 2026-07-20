package com.alechilles.alecstamework.npc.actions;

import javax.annotation.Nonnull;

/** Routes a parsed mount mode to one focused implementation without re-parsing role strings. */
final class InteractionMountModeDispatcher {
    private final Handler nativeMount;
    private final Handler rideMount;
    private final Handler mountedGlide;
    private final Handler avatarFlight;

    InteractionMountModeDispatcher(@Nonnull Handler nativeMount,
                                   @Nonnull Handler rideMount,
                                   @Nonnull Handler mountedGlide,
                                   @Nonnull Handler avatarFlight) {
        this.nativeMount = nativeMount;
        this.rideMount = rideMount;
        this.mountedGlide = mountedGlide;
        this.avatarFlight = avatarFlight;
    }

    boolean dispatch(@Nonnull InteractionMountMode mode, @Nonnull InteractionMountRequest request) {
        return switch (mode) {
            case NATIVE -> nativeMount.apply(request);
            case TAMEWORK_RIDE -> rideMount.apply(request);
            case TAMEWORK_MOUNTED_GLIDE -> mountedGlide.apply(request);
            case TAMEWORK_AVATAR_FLIGHT -> avatarFlight.apply(request);
        };
    }

    @FunctionalInterface
    interface Handler {
        boolean apply(@Nonnull InteractionMountRequest request);
    }
}
