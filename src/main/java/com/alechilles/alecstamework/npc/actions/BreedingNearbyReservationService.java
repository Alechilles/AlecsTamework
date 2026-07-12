package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Shared exact nearby/family reservations used by manual and passive breeding. */
final class BreedingNearbyReservationService {
    private static final long LEASE_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final BreedingNearbyReservationService SHARED =
            new BreedingNearbyReservationService(new BreedingPopulationTypeService());

    private final ReentrantLock lock = new ReentrantLock();
    private final BreedingPopulationTypeService populationTypeService;
    private final Map<UUID, MutableReservation> reservations = new HashMap<>();

    BreedingNearbyReservationService(BreedingPopulationTypeService populationTypeService) {
        this.populationTypeService = populationTypeService;
    }

    @Nonnull
    static BreedingNearbyReservationService shared() {
        return SHARED;
    }

    @Nonnull
    Reservation reserve(@Nonnull Store<EntityStore> store,
                        @Nonnull String worldName,
                        @Nonnull Vector3d center,
                        @Nullable TwBreedingConfig config,
                        @Nullable String sourceRoleId,
                        @Nonnull List<BreedingBirthPlan.PlannedChild> plannedChildren) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(center, "center");
        List<BreedingBirthPlan.PlannedChild> children = List.copyOf(plannedChildren);
        TwBreedingConfig.PairingSettings pairing = config == null
                ? null
                : config.resolvePairing(sourceRoleId);
        int cap = pairing == null ? 0 : pairing.resolveMaxNearbySameType(sourceRoleId);
        double radius = pairing == null ? 10.0 : sanitizeRadius(pairing.getBreedRadius());
        Map<String, Integer> liveByType = cap <= 0
                ? Map.of()
                : liveCounts(store, center, radius, config, children);
        List<String> types = children.stream()
                .map(BreedingBirthPlan.PlannedChild::populationType)
                .toList();
        return reserveEvaluated(worldName, center, radius, cap, types, liveByType);
    }

    @Nonnull
    Reservation reserveEvaluated(@Nonnull String worldName,
                                  @Nonnull Vector3d center,
                                  double radius,
                                  int cap,
                                  @Nonnull List<String> populationTypes,
                                  @Nonnull Map<String, Integer> liveByType) {
        UUID tokenId = UUID.randomUUID();
        lock.lock();
        try {
            pruneExpired(System.nanoTime());
            List<ReservedUnit> admitted = new ArrayList<>();
            Map<String, Integer> localByType = new HashMap<>();
            for (String populationType : populationTypes) {
                String type = normalizeType(populationType);
                int live = liveByType.getOrDefault(type, 0);
                int pending = countPending(worldName, center, radius, type);
                int local = localByType.getOrDefault(type, 0);
                if (cap > 0 && live + pending + local >= cap) {
                    break;
                }
                admitted.add(new ReservedUnit(type, true));
                localByType.merge(type, 1, Integer::sum);
            }
            MutableReservation reservation = new MutableReservation(
                    tokenId,
                    worldName,
                    new Vector3d(center),
                    radius,
                    System.nanoTime() + LEASE_NANOS,
                    admitted
            );
            if (!admitted.isEmpty()) {
                reservations.put(tokenId, reservation);
            }
            return new Reservation(
                    tokenId, populationTypes.size(), admitted.size(), cap > 0
            );
        } finally {
            lock.unlock();
        }
    }

    /** Rechecks the current nearby rule and claims one still-live reservation for spawn. */
    boolean claimForSpawn(@Nonnull Reservation reservation,
                          int unitIndex,
                          @Nonnull Store<EntityStore> store,
                          @Nonnull String worldName,
                          @Nonnull Vector3d center,
                          @Nullable TwBreedingConfig config,
                          @Nullable String sourceRoleId,
                          @Nonnull BreedingBirthPlan.PlannedChild child) {
        TwBreedingConfig.PairingSettings pairing = config == null
                ? null
                : config.resolvePairing(sourceRoleId);
        int cap = pairing == null ? 0 : pairing.resolveMaxNearbySameType(sourceRoleId);
        double radius = pairing == null ? 10.0 : sanitizeRadius(pairing.getBreedRadius());
        String type = normalizeType(child.populationType());
        int live = cap <= 0
                ? 0
                : populationTypeService.countNearbyOfType(store, center, radius, config, type);
        return claimEvaluated(
                reservation, unitIndex, worldName, center, radius, type, cap, live
        );
    }

    boolean claimEvaluated(@Nonnull Reservation reservation,
                           int unitIndex,
                           @Nonnull String worldName,
                           @Nonnull Vector3d center,
                           double radius,
                           @Nonnull String populationType,
                           int cap,
                           int liveCount) {
        lock.lock();
        try {
            pruneExpired(System.nanoTime());
            MutableReservation mutable = reservations.get(reservation.tokenId());
            if (mutable == null || unitIndex < 0 || unitIndex >= mutable.units.size()) {
                return false;
            }
            ReservedUnit unit = mutable.units.get(unitIndex);
            String type = normalizeType(populationType);
            if (!unit.active || unit.claimed || !unit.type.equals(type)) {
                return false;
            }
            int otherPending = countPendingExcluding(
                    worldName, center, radius, type, reservation.tokenId()
            );
            if (cap > 0 && liveCount + otherPending >= cap) {
                unit.active = false;
                removeIfEmpty(mutable);
                return false;
            }
            unit.claimed = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    void releaseUnit(@Nonnull Reservation reservation, int unitIndex) {
        lock.lock();
        try {
            pruneExpired(System.nanoTime());
            MutableReservation mutable = reservations.get(reservation.tokenId());
            if (mutable == null || unitIndex < 0 || unitIndex >= mutable.units.size()) {
                return;
            }
            mutable.units.get(unitIndex).active = false;
            removeIfEmpty(mutable);
        } finally {
            lock.unlock();
        }
    }

    void releaseFrom(@Nonnull Reservation reservation, int firstUnitIndex) {
        lock.lock();
        try {
            pruneExpired(System.nanoTime());
            MutableReservation mutable = reservations.get(reservation.tokenId());
            if (mutable == null) {
                return;
            }
            int start = Math.max(0, firstUnitIndex);
            for (int index = start; index < mutable.units.size(); index++) {
                mutable.units.get(index).active = false;
            }
            removeIfEmpty(mutable);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Integer> liveCounts(Store<EntityStore> store,
                                            Vector3d center,
                                            double radius,
                                            TwBreedingConfig config,
                                            List<BreedingBirthPlan.PlannedChild> children) {
        Map<String, Integer> live = new HashMap<>();
        for (BreedingBirthPlan.PlannedChild child : children) {
            String type = normalizeType(child.populationType());
            live.computeIfAbsent(type, ignored -> populationTypeService.countNearbyOfType(
                    store,
                    center,
                    radius,
                    config,
                    type
            ));
        }
        return live;
    }

    private int countPending(String worldName, Vector3d center, double radius, String type) {
        return countPendingExcluding(worldName, center, radius, type, null);
    }

    private int countPendingExcluding(String worldName,
                                      Vector3d center,
                                      double radius,
                                      String type,
                                      @Nullable UUID excludedToken) {
        double radiusSquared = radius * radius;
        int count = 0;
        for (MutableReservation reservation : reservations.values()) {
            if (!worldName.equals(reservation.worldName)
                    || reservation.center.distanceSquared(center) > radiusSquared) {
                continue;
            }
            for (int index = 0; index < reservation.units.size(); index++) {
                if (Objects.equals(excludedToken, reservation.tokenId)) {
                    continue;
                }
                ReservedUnit unit = reservation.units.get(index);
                if (unit.active && type.equals(unit.type)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void removeIfEmpty(MutableReservation reservation) {
        for (ReservedUnit unit : reservation.units) {
            if (unit.active) {
                return;
            }
        }
        reservations.remove(reservation.tokenId, reservation);
    }

    private void pruneExpired(long nowNanos) {
        reservations.values().removeIf(reservation -> nowNanos >= reservation.expiresAtMonotonicNanos);
    }

    private static double sanitizeRadius(double radius) {
        return Double.isFinite(radius) && radius > 0.0 ? radius : 10.0;
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    record Reservation(@Nullable UUID tokenId,
                       int requestedCount,
                       int admittedCount,
                       boolean constrained) {
    }

    private static final class MutableReservation {
        private final UUID tokenId;
        private final String worldName;
        private final Vector3d center;
        private final double radius;
        private final long expiresAtMonotonicNanos;
        private final List<ReservedUnit> units;

        private MutableReservation(UUID tokenId,
                                   String worldName,
                                   Vector3d center,
                                   double radius,
                                   long expiresAtMonotonicNanos,
                                   List<ReservedUnit> units) {
            this.tokenId = tokenId;
            this.worldName = worldName;
            this.center = center;
            this.radius = radius;
            this.expiresAtMonotonicNanos = expiresAtMonotonicNanos;
            this.units = units;
        }
    }

    private static final class ReservedUnit {
        private final String type;
        private boolean active;
        private boolean claimed;

        private ReservedUnit(String type, boolean active) {
            this.type = type;
            this.active = active;
        }
    }
}
