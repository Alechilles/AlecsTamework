package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.HytaleDirectLiveCoopScanner;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Coop schedule (spec 8.9), replacing the never-registered {@code CommandDirectLiveCoopSystem}.
 * Once a second per world it scans the loaded managed coops; outside a coop's roam hours each coop
 * that captures in range takes in the nearest accepted NPC into its first free slot. Morning
 * release and production arrive with the coop release work.
 *
 * <p>Why a tick: intake depends on the time of day and on NPCs wandering into range, for which
 * there is no event. Scope and cost match the old sweep: one scan of loaded coop blocks per world
 * per second, plus one NPC scan only when a loaded coop captures in range; at most one intake per
 * coop per sweep. Occupancy comes from each block's {@link TameworkCoopSlotsComponent}.
 *
 * <p>The tick only reads. The intake itself is queued to the world thread by
 * {@link HytaleCoopIntake}; the retired receipts component is stripped through the iteration's
 * command buffer. State is per store, since worlds tick this one instance concurrently.
 */
public final class CoopScheduleSystem extends TickingSystem<ChunkStore> {
    private static final long SWEEP_INTERVAL_MS = 1_000L;

    private final HytaleCoopIntake intake;
    private final HytaleDirectLiveCoopScanner scanner = new HytaleDirectLiveCoopScanner();
    private final StoreScopedState<TickState> tickStates = new StoreScopedState<>(TickState::new);

    public CoopScheduleSystem(@Nonnull HytaleCoopIntake intake) {
        this.intake = Objects.requireNonNull(intake, "intake");
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
        HytaleDirectLiveCoopScanner.Scan scan = scanner.scan(chunkStore);
        if (scan == null) {
            return;
        }
        Set<UUID> chosen = new HashSet<>();
        for (HytaleDirectLiveCoopScanner.LoadedCoop coop : scan.coops()) {
            TwCoopConfig config = coop.config();
            if (config == null || !config.getLifecycleRules().isCaptureWildNPCsInRange()
                    || roaming(scan.worldTime(), config)) {
                continue;
            }
            takeInNearest(scan, coop, config, chosen);
        }
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
    private static void stripRetiredReceipts(Store<ChunkStore> chunkStore) {
        ComponentType<ChunkStore, ? extends Component<ChunkStore>> receipts =
                TameworkCoopSlotsComponent.retiredReceiptsType();
        if (receipts != null) {
            strip(chunkStore, receipts);
        }
    }

    private static <T extends Component<ChunkStore>> void strip(Store<ChunkStore> chunkStore,
                                                                 ComponentType<ChunkStore, T> type) {
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
