package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.coop.runtime.TameworkCoopCaptureReceiptsComponent;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.HytaleDirectLiveCoopScanner;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Coop schedule (spec 8.9), replacing the never-registered {@code CommandDirectLiveCoopSystem}.
 * Once a second per world it scans the loaded managed coops; outside a coop's roam hours each coop
 * that captures in range takes in the nearest accepted NPC into its first free slot. Inside its
 * roam hours each coop produces for its residents and releases one resident per sweep, as in 4.x
 * ({@link HytaleCoopResidents#roam}). A coop seen loaded for the first time also gets back the
 * residents imported from 3.x or 4.x ({@link HytaleCoopResidents#ensureImportedResidents}); that
 * adds no scan: it reads the same coop list and a map of waiting sites that empties as coops load.
 *
 * <p>Why a tick: intake depends on the time of day and on NPCs wandering into range, for which
 * there is no event. Scope and cost match the old sweep: one scan of loaded coop blocks per world
 * per second, plus one NPC scan only when a loaded coop captures in range outside its roam hours;
 * at most one intake per coop per sweep. Occupancy comes from each block's
 * {@link TameworkCoopSlotsComponent}.
 *
 * <p>The tick only reads. The intake itself is queued to the world thread by
 * {@link HytaleCoopIntake}; production, release and the produce-ready block state run in one
 * {@code world.execute} task per sweep; the retired receipts component is stripped through the
 * iteration's command buffer. State is per store, since worlds tick this one instance concurrently.
 */
public final class CoopScheduleSystem extends TickingSystem<ChunkStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long SWEEP_INTERVAL_MS = 1_000L;
    private static final long WARN_INTERVAL_MS = 60_000L;
    /** Above this many throttle entries, expired ones are dropped. */
    private static final int MAX_WARN_KEYS = 256;

    private final HytaleCoopIntake intake;
    private final HytaleCoopResidents residents;
    @Nullable private final ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent> retiredReceipts;
    private final HytaleDirectLiveCoopScanner scanner = new HytaleDirectLiveCoopScanner();
    private final StoreScopedState<TickState> tickStates = new StoreScopedState<>(TickState::new);
    /** Last WARN time per coop and step; touched only on world threads, which share this instance. */
    private final Map<String, Long> lastWarnAt = new ConcurrentHashMap<>();

    /** {@code retiredReceipts} is the registered retired receipts type, stripped from blocks. */
    public CoopScheduleSystem(@Nonnull HytaleCoopIntake intake, @Nonnull HytaleCoopResidents residents,
                              @Nullable ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent> retiredReceipts) {
        this.intake = Objects.requireNonNull(intake, "intake");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.retiredReceipts = retiredReceipts;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> chunkStore) {
        TickState state = tickStates.get(chunkStore);
        long now = System.currentTimeMillis();
        if (now < state.nextSweepAtMs) {
            return;
        }
        state.nextSweepAtMs = now + SWEEP_INTERVAL_MS;
        stripRetiredReceipts(chunkStore);
        intake.pruneSnapshotFailures();
        HytaleDirectLiveCoopScanner.Scan scan = scanner.scan(chunkStore, CoopScheduleSystem::takesInNow);
        if (scan == null) {
            return;
        }
        // Residents imported from 3.x or 4.x (plan 7 R20): a coop that still waits for them is
        // filled first and takes nobody in this sweep, so an intake cannot take their slots.
        boolean imports = residents.importsPendingIn(scan.world());
        List<HytaleDirectLiveCoopScanner.LoadedCoop> importCoops = new ArrayList<>();
        Set<UUID> chosen = new HashSet<>();
        List<HytaleDirectLiveCoopScanner.LoadedCoop> roamingCoops = new ArrayList<>();
        for (HytaleDirectLiveCoopScanner.LoadedCoop coop : scan.coops()) {
            if (imports && residents.importsPendingAt(scan.world(), coop)) {
                importCoops.add(coop);
            } else if (takesInNow(coop, scan.worldTime())) {
                takeInNearest(scan, coop, coop.config(), chosen);
            } else if (coop.config() != null && roaming(scan.worldTime(), coop.config())) {
                roamingCoops.add(coop);
            }
        }
        List<CoopImportedResidents.Site> goneSites = imports
                ? residents.importSitesWithoutCoop(scan.world(), scan.coops()) : List.of();
        queueResidentWork(scan.world(), roamingCoops, scan.coops(), importCoops, goneSites);
    }

    /**
     * Imported residents, then production and release for roaming coops, and the produce-ready
     * state of every coop, on the world thread.
     */
    private void queueResidentWork(World world, List<HytaleDirectLiveCoopScanner.LoadedCoop> roamingCoops,
                                   List<HytaleDirectLiveCoopScanner.LoadedCoop> coops,
                                   List<HytaleDirectLiveCoopScanner.LoadedCoop> importCoops,
                                   List<CoopImportedResidents.Site> goneSites) {
        if (coops.isEmpty() && goneSites.isEmpty()) {
            return;
        }
        try {
            world.execute(() -> {
                for (HytaleDirectLiveCoopScanner.LoadedCoop coop : importCoops) {
                    try {
                        residents.ensureImportedResidents(world, coop);
                    } catch (RuntimeException | LinkageError failure) {
                        warnThrottled(world, coop, "imported residents", failure);
                    }
                }
                for (CoopImportedResidents.Site site : goneSites) {
                    try {
                        residents.releaseImportedWithoutCoop(world, site);
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.at(Level.WARNING).withCause(failure).log(
                                "Could not move imported residents out of the missing coop at %s", site);
                    }
                }
                for (HytaleDirectLiveCoopScanner.LoadedCoop coop : roamingCoops) {
                    try {
                        residents.roam(world, coop);
                    } catch (RuntimeException | LinkageError failure) {
                        warnThrottled(world, coop, "resident sweep", failure);
                    }
                }
                for (HytaleDirectLiveCoopScanner.LoadedCoop coop : coops) {
                    try {
                        residents.syncInteractionState(world, coop);
                    } catch (RuntimeException | LinkageError failure) {
                        warnThrottled(world, coop, "produce-ready state", failure);
                    }
                }
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the next sweep retries.
        }
    }

    /** WARNs at most once per {@link #WARN_INTERVAL_MS} for each coop, since the sweep repeats every second. */
    private void warnThrottled(World world, HytaleDirectLiveCoopScanner.LoadedCoop coop, String what, Throwable failure) {
        String key = world.getName() + '|' + coop.block().x + '|' + coop.block().y + '|' + coop.block().z + '|' + what;
        long now = System.currentTimeMillis();
        Long last = lastWarnAt.get(key);
        if (last != null && now - last < WARN_INTERVAL_MS) {
            return;
        }
        if (lastWarnAt.size() >= MAX_WARN_KEYS) {
            lastWarnAt.values().removeIf(at -> now - at >= WARN_INTERVAL_MS);
        }
        lastWarnAt.put(key, now);
        LOGGER.at(Level.WARNING).withCause(failure).log("Coop %s failed at %s in world %s", what, coop.block(),
                world.getName());
    }

    /** A coop that captures in range and is outside its roam hours. */
    private static boolean takesInNow(HytaleDirectLiveCoopScanner.LoadedCoop coop, WorldTimeResource worldTime) {
        TwCoopConfig config = coop.config();
        return config != null && config.getLifecycleRules().isCaptureWildNPCsInRange() && !roaming(worldTime, config);
    }

    private void takeInNearest(HytaleDirectLiveCoopScanner.Scan scan, HytaleDirectLiveCoopScanner.LoadedCoop coop,
                               TwCoopConfig config, Set<UUID> chosen) {
        Vector3i block = coop.block();
        int slot = intake.firstFree(scan.world(), block.x, block.y, block.z,
                config.getLifecycleRules().getMaxResidents());
        if (slot < 0) {
            return;
        }
        HytaleDirectLiveCoopScanner.LiveNpc candidate = nearest(scan, coop, config, chosen);
        if (candidate == null) {
            return;
        }
        chosen.add(candidate.alias());
        intake.takeIn(scan.world(), new CoopIntakeFlow.Site(scan.world().getName(), block.x, block.y, block.z, slot,
                coop.coopId()), candidate.alias());
    }

    @Nullable
    private HytaleDirectLiveCoopScanner.LiveNpc nearest(HytaleDirectLiveCoopScanner.Scan scan,
                                                        HytaleDirectLiveCoopScanner.LoadedCoop coop,
                                                        TwCoopConfig config, Set<UUID> chosen) {
        double radius = config.getLifecycleRules().getWildCaptureRadius();
        if (radius <= 0.0) {
            return null;
        }
        Set<String> accepted = normalizedRoles(config.getLifecycleRules().getAcceptedRoleIds());
        boolean requireTamed = config.getCapturePolicy().isRequireTamed();
        double radiusSquared = radius * radius;
        double cx = coop.block().x + 0.5;
        double cy = coop.block().y + 0.5;
        double cz = coop.block().z + 0.5;
        HytaleDirectLiveCoopScanner.LiveNpc best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        // The scan is sorted by NPC UUID, so a tie keeps the lowest UUID.
        for (HytaleDirectLiveCoopScanner.LiveNpc candidate : scan.liveNpcs()) {
            if (chosen.contains(candidate.alias())
                    || !accepted.isEmpty() && !accepted.contains(candidate.roleId())
                    || requireTamed && !candidate.tamed()) {
                continue;
            }
            Vector3d at = candidate.position();
            double dx = at.x - cx;
            double dy = at.y - cy;
            double dz = at.z - cz;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= radiusSquared && distance < bestDistance
                    && intake.canTakeIn(candidate.reference(), scan.entityStore(), candidate.alias())) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Removes the retired receipts component from every loaded block that still has it. */
    private void stripRetiredReceipts(Store<ChunkStore> chunkStore) {
        ComponentType<ChunkStore, TameworkCoopCaptureReceiptsComponent> type = retiredReceipts;
        if (type == null) {
            return;
        }
        chunkStore.forEachChunk(type, (ArchetypeChunk<ChunkStore> chunk, CommandBuffer<ChunkStore> commands) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<ChunkStore> ref = chunk.getReferenceTo(i);
                if (ref != null && ref.isValid()) {
                    commands.tryRemoveComponent(ref, type);
                }
            }
        });
    }

    /** True inside the coop's roam hours (residents are out); start == end means always. */
    private static boolean roaming(WorldTimeResource worldTime, TwCoopConfig config) {
        Instant gameTime = worldTime.getGameTime();
        int hour = (gameTime == null ? Instant.now() : gameTime).atZone(ZoneOffset.UTC).getHour();
        int start = config.getLifecycleRules().getResidentRoamStartHour();
        int end = config.getLifecycleRules().getResidentRoamEndHour();
        if (start == end) {
            return true;
        }
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    private static Set<String> normalizedRoles(@Nullable String[] roles) {
        if (roles == null || roles.length == 0) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                normalized.add(role.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}
