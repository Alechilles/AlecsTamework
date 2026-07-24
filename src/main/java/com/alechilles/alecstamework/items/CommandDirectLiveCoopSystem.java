package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopAuthor;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopCompletionTracker;
import com.alechilles.alecstamework.items.coop.DirectLiveCoopProjectionView;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Released direct-live coop gameplay author over the replacement persistence facades.
 *
 * <p>This system scans configured loaded coops, submits only live-entity capture and canonical
 * coop release requests, and delegates all durable/live mutation ordering to the shared Hytale
 * boundaries. It has no captured-item intake path and no ledger or repository dependency.</p>
 */
public final class CommandDirectLiveCoopSystem
        extends TickingSystem<ChunkStore> {
    private static final long SWEEP_INTERVAL_MS = 1_000L;

    private final DirectLiveCoopAuthor author;
    private final DirectLiveCoopProjectionView projections;
    private final HytaleDirectLiveCoopScanner scanner;
    private final HytaleDirectLiveCoopEvidenceFactory evidence;
    private final DirectLiveCoopProduceService produce;
    private final DirectLiveCoopCompletionTracker completions =
            new DirectLiveCoopCompletionTracker();
    private final StoreScopedState<TickState> tickStates =
            new StoreScopedState<>(TickState::new);
    private final Map<String, DirectLiveCoopAuthor.LiveNpcSource>
            frozenCaptures = new ConcurrentHashMap<>();
    private final Map<String, CompanionSpawnPlacement> frozenReleases =
            new ConcurrentHashMap<>();
    private final Map<String, Boolean> previousRoaming =
            new ConcurrentHashMap<>();

    /** Creates the world system from its author and projection-only boundary. */
    public CommandDirectLiveCoopSystem(
            @Nonnull DirectLiveCoopAuthor author,
            @Nonnull DirectLiveCoopProjectionView projections
    ) {
        if (author == null || projections == null) {
            throw new IllegalArgumentException(
                    "Direct-live coop collaborators are required"
            );
        }
        this.author = author;
        this.projections = projections;
        scanner = new HytaleDirectLiveCoopScanner();
        evidence = new HytaleDirectLiveCoopEvidenceFactory();
        produce = new DirectLiveCoopProduceService();
    }

    @Override
    public synchronized void tick(
            float dt,
            int systemIndex,
            @Nonnull Store<ChunkStore> chunkStore
    ) {
        TickState tickState = tickStates.get(chunkStore);
        long now = System.currentTimeMillis();
        if (now < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = now + SWEEP_INTERVAL_MS;
        HytaleDirectLiveCoopScanner.Scan scan = scanner.scan(chunkStore);
        if (scan == null) {
            return;
        }
        Map<CoopSlotKey, CoopOccupancy> occupancies =
                projections.coopSnapshot();
        Map<ProfileId, CompanionProfileProjectionState> profiles =
                projections.profileSnapshot();
        DirectLiveCoopProfileIndex profileIndex =
                new DirectLiveCoopProfileIndex(profiles);
        registerLoadedSlots(scan.coops());
        pruneFrozenEvidence(scan, occupancies);

        HashSet<UUID> consumedAliases = new HashSet<>();
        HashSet<String> loadedPhysical = new HashSet<>();
        for (HytaleDirectLiveCoopScanner.LoadedCoop coop : scan.coops()) {
            loadedPhysical.add(coop.physicalKey());
            boolean roaming = roaming(scan.worldTime(), coop.config());
            boolean wasRoaming = previousRoaming.getOrDefault(
                    coop.physicalKey(), false
            );
            previousRoaming.put(coop.physicalKey(), roaming);
            if (roaming) {
                releaseFirstResident(scan, coop, occupancies, profiles);
                if (!wasRoaming) {
                    produce.produceOnRoamingStart(
                            coop, scan.worldTime(), occupancies, profiles
                    );
                }
            } else {
                captureNearest(
                        scan,
                        coop,
                        occupancies,
                        profileIndex,
                        consumedAliases,
                        now
                );
            }
            produce.syncInteractionState(scan.world(), coop);
        }
        previousRoaming.keySet().retainAll(loadedPhysical);
        releaseRemovedResidents(
                scan, loadedPhysical, occupancies, profiles
        );
    }

    private void registerLoadedSlots(
            List<HytaleDirectLiveCoopScanner.LoadedCoop> coops
    ) {
        ArrayList<CoopSlotKey> slots = new ArrayList<>();
        coops.forEach(coop -> slots.addAll(coop.slots()));
        slots.removeIf(completions::isRegistered);
        slots.sort(Comparator.naturalOrder());
        if (slots.isEmpty()) {
            return;
        }
        String batchKey = slots.toString();
        if (!completions.beginRegistration(batchKey)) {
            return;
        }
        completions.trackRegistration(
                batchKey, slots, author.registerLoadedSlots(slots)
        );
    }

    private void captureNearest(
            HytaleDirectLiveCoopScanner.Scan scan,
            HytaleDirectLiveCoopScanner.LoadedCoop coop,
            Map<CoopSlotKey, CoopOccupancy> occupancies,
            DirectLiveCoopProfileIndex profileIndex,
            Set<UUID> consumedAliases,
            long observedAtMs
    ) {
        TwCoopConfig.LifecycleRules rules =
                coop.config().getLifecycleRules();
        if (!rules.isCaptureWildNPCsInRange()) {
            return;
        }
        CoopSlotKey slot = coop.slots().stream()
                .filter(candidate -> !occupancies.containsKey(candidate))
                .findFirst()
                .orElse(null);
        if (slot == null) {
            return;
        }
        HytaleDirectLiveCoopScanner.LiveNpc candidate = nearest(
                coop, scan.liveNpcs(), profileIndex, consumedAliases
        );
        if (candidate == null) {
            return;
        }
        ProfileId profileId =
                profileIndex.captureProfileId(candidate.alias());
        if (profileId == null) {
            return;
        }
        String key = slot + "|" + candidate.alias();
        DirectLiveCoopAuthor.LiveNpcSource source =
                frozenCaptures.computeIfAbsent(
                        key,
                        ignored -> evidence.captureSource(
                                scan,
                                coop,
                                slot,
                                candidate,
                                profileId,
                                observedAtMs
                        )
                );
        if (source == null) {
            return;
        }
        if (!completions.beginCapture(key)) {
            return;
        }
        consumedAliases.add(candidate.alias());
        completions.trackCapture(
                key, author.captureLive(slot, source)
        );
    }

    @Nullable
    private HytaleDirectLiveCoopScanner.LiveNpc nearest(
            HytaleDirectLiveCoopScanner.LoadedCoop coop,
            List<HytaleDirectLiveCoopScanner.LiveNpc> candidates,
            DirectLiveCoopProfileIndex profileIndex,
            Set<UUID> consumed
    ) {
        Set<String> accepted = normalizedRoles(
                coop.config().getLifecycleRules().getAcceptedRoleIds()
        );
        boolean requireTamed =
                coop.config().getCapturePolicy().isRequireTamed();
        double radius = coop.config().getLifecycleRules()
                .getWildCaptureRadius();
        if (radius <= 0.0) {
            return null;
        }
        double radiusSquared = radius * radius;
        HytaleDirectLiveCoopScanner.LiveNpc best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (HytaleDirectLiveCoopScanner.LiveNpc candidate : candidates) {
            if (consumed.contains(candidate.alias())
                    || (!accepted.isEmpty()
                    && !accepted.contains(candidate.roleId()))
                    || (requireTamed && !candidate.tamed())
                    || profileIndex.captureProfileId(candidate.alias())
                    == null) {
                continue;
            }
            double distance = distanceSquared(
                    coop.block().x + 0.5,
                    coop.block().y + 0.5,
                    coop.block().z + 0.5,
                    candidate.position()
            );
            if (distance <= radiusSquared
                    && (distance < bestDistance
                    || (distance == bestDistance && aliasBefore(
                    candidate, best)))) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void releaseFirstResident(
            HytaleDirectLiveCoopScanner.Scan scan,
            HytaleDirectLiveCoopScanner.LoadedCoop coop,
            Map<CoopSlotKey, CoopOccupancy> occupancies,
            Map<ProfileId, CompanionProfileProjectionState> profiles
    ) {
        for (CoopSlotKey slot : coop.slots()) {
            CoopOccupancy occupancy = occupancies.get(slot);
            if (occupancy != null) {
                release(scan, coop, occupancy, profiles);
                return;
            }
        }
    }

    private void releaseRemovedResidents(
            HytaleDirectLiveCoopScanner.Scan scan,
            Set<String> loadedPhysical,
            Map<CoopSlotKey, CoopOccupancy> occupancies,
            Map<ProfileId, CompanionProfileProjectionState> profiles
    ) {
        occupancies.values().stream()
                .sorted(Comparator.comparing(
                        occupancy -> occupancy.slot().key()
                ))
                .filter(occupancy -> occupancy.slot().key().worldKey()
                        .equalsIgnoreCase(scan.world().getName()))
                .filter(occupancy -> !loadedPhysical.contains(
                        physicalKey(occupancy.slot().key())
                ))
                .filter(occupancy -> scanner.confirmedRemoved(
                        scan.world(), scan.chunkStore(),
                        occupancy.slot().key()
                ))
                .forEach(occupancy -> {
                    CoopSlotKey slot = occupancy.slot().key();
                    TwCoopConfig config = TwCoopConfig.resolveForCoop(
                            slot.coopId()
                    );
                    release(
                            scan,
                            new HytaleDirectLiveCoopScanner.LoadedCoop(
                                    slot.worldKey(),
                                    slot.coopId(),
                                    new org.joml.Vector3i(
                                            slot.x(), slot.y(), slot.z()
                                    ),
                                    0,
                                    config,
                                    null
                            ),
                            occupancy,
                            profiles
                    );
                });
    }

    private void release(
            HytaleDirectLiveCoopScanner.Scan scan,
            HytaleDirectLiveCoopScanner.LoadedCoop coop,
            CoopOccupancy occupancy,
            Map<ProfileId, CompanionProfileProjectionState> profiles
    ) {
        CoopSlotKey slot = occupancy.slot().key();
        String key = slot + "|" + occupancy.residency().snapshotId();
        CompanionProfileProjectionState profile =
                profiles.get(occupancy.residency().profileId());
        CompanionSpawnPlacement placement = frozenReleases.computeIfAbsent(
                key,
                ignored -> evidence.releasePlacement(
                        scan, coop, profile == null ? null : profile.roleId()
                )
        );
        if (placement == null) {
            return;
        }
        if (!completions.beginRelease(key)) {
            return;
        }
        completions.trackRelease(
                key, author.releaseOccupied(slot, placement)
        );
    }

    private void pruneFrozenEvidence(
            HytaleDirectLiveCoopScanner.Scan scan,
            Map<CoopSlotKey, CoopOccupancy> occupancies
    ) {
        Set<UUID> liveAliases = new HashSet<>();
        scan.liveNpcs().forEach(npc -> liveAliases.add(npc.alias()));
        frozenCaptures.entrySet().removeIf(entry ->
                !liveAliases.contains(entry.getValue().alias().value()));
        Set<String> activeReleases = new HashSet<>();
        occupancies.values().forEach(occupancy -> activeReleases.add(
                occupancy.slot().key() + "|"
                        + occupancy.residency().snapshotId()
        ));
        frozenReleases.keySet().retainAll(activeReleases);
    }

    private boolean roaming(
            WorldTimeResource worldTime,
            TwCoopConfig config
    ) {
        Instant gameTime = worldTime.getGameTime();
        int hour = (gameTime == null ? Instant.now() : gameTime)
                .atZone(ZoneOffset.UTC).getHour();
        int start = config.getLifecycleRules().getResidentRoamStartHour();
        int end = config.getLifecycleRules().getResidentRoamEndHour();
        if (start == end) {
            return true;
        }
        return start < end
                ? hour >= start && hour < end
                : hour >= start || hour < end;
    }

    private Set<String> normalizedRoles(@Nullable String[] roles) {
        if (roles == null || roles.length == 0) {
            return Set.of();
        }
        HashSet<String> normalized = new HashSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                normalized.add(role.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private double distanceSquared(
            double x,
            double y,
            double z,
            Vector3d target
    ) {
        double dx = target.x - x;
        double dy = target.y - y;
        double dz = target.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean aliasBefore(
            HytaleDirectLiveCoopScanner.LiveNpc candidate,
            @Nullable HytaleDirectLiveCoopScanner.LiveNpc current
    ) {
        return current == null || candidate.alias().toString()
                .compareTo(current.alias().toString()) < 0;
    }

    private String physicalKey(CoopSlotKey slot) {
        return slot.worldKey() + "|" + slot.coopId() + "|" + slot.x()
                + "|" + slot.y() + "|" + slot.z();
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}
