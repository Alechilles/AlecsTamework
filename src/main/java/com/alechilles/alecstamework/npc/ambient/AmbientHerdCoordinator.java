package com.alechilles.alecstamework.npc.ambient;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;

import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates the small amount of state needed for one optional native herd journey.
 *
 * <p>All state below is scoped to the current live store. Activity records retain UUIDs,
 * coordinates, and timers only. Every component, flock, transform, and route-controller read
 * happens in the caller's current world callback.
 */
public final class AmbientHerdCoordinator implements AutoCloseable {
    static final long OFFER_MIN_MS = 2_000L;
    static final long OFFER_MAX_MS = 4_000L;
    static final long INITIAL_DELAY_MIN_MS = 30_000L;
    static final long INITIAL_DELAY_MAX_MS = 90_000L;
    static final long SUCCESS_COOLDOWN_MIN_MS = 180_000L;
    static final long SUCCESS_COOLDOWN_MAX_MS = 300_000L;
    static final long FAILURE_COOLDOWN_MIN_MS = 60_000L;
    static final long FAILURE_COOLDOWN_MAX_MS = 120_000L;
    static final long DISCOVERY_TIMEOUT_MS = 20_000L;
    static final long GATHER_TIMEOUT_MS = 12_000L;
    static final long TRAVEL_TIMEOUT_MS = 90_000L;
    static final long ACTIVITY_TIMEOUT_MS = 240_000L;
    static final long RECORD_TTL_MS = 15_000L;
    static final long CACHE_TTL_MS = 180_000L;
    static final long FAILED_CACHE_TTL_MS = 120_000L;
    static final int MAX_RECORDS_PER_WORLD = 128;
    static final int MAX_CACHE_ENTRIES_PER_WORLD = 64;
    static final int MAX_VISITS_PER_TICK = 8;
    private static final double GATHER_RADIUS_SQUARED = 36.0;
    private static final double WAYPOINT_RADIUS_SQUARED = 2.25;

    /** Sensor-facing mode. READY does not publish a movement target. */
    public enum SensorPhase implements Supplier<String> {
        READY,
        ACTIVE,
        MOVE,
        DRINK,
        REST;

        @Override
        public String get() {
            return name();
        }
    }

    /** Reason ordinary native behavior resumes. */
    public enum Exit {
        COMPLETE,
        NO_WATER,
        NO_PATH,
        BLOCKED,
        THREAT,
        NIGHT,
        LEADER_CHANGED,
        MEMBER_CHANGED,
        DISABLED,
        UNLOADED,
        TIMEOUT
    }

    private final AmbientHerdWorkBudget budget = new AmbientHerdWorkBudget();
    private final AmbientHerdWaterBankSearch waterSearch = new AmbientHerdWaterBankSearch();
    private final AmbientHerdPathPlanner pathPlanner = new AmbientHerdPathPlanner();
    private final StoreScopedState<WorldState> statesByStore =
            new StoreScopedState<>(WorldState::new);
    private final Map<UUID, UUID> admittedActivities = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Records an eligible leader heartbeat. It is deliberately constant-time between offers and
     * never scans the world: only this leader's existing native flock is inspected.
     */
    public void observe(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Role role,
            boolean canLead,
            long nowMillis) {
        if (closed || !canLead || ref == null || !ref.isValid()) {
            return;
        }
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(role, "role");
        UUID leaderId = npcId(store, ref);
        NPCEntity leaderNpc = store.getComponent(ref, NPCEntity.getComponentType());
        if (leaderId == null
                || leaderNpc == null
                || !allowsAmbientRole(leaderNpc)
                || TamedStateResolver.isTamed(ref, store)) {
            return;
        }
        WorldState world = statesByStore.get(store);
        LeaderRecord record = world.leaders.get(leaderId);
        if (record == null) {
            if (world.leaders.size() >= MAX_RECORDS_PER_WORLD
                    || !budget.tryRegister(worldId(store), leaderId)) {
                return;
            }
            record = new LeaderRecord(leaderId, initialDue(leaderId, nowMillis));
            world.leaders.put(leaderId, record);
            world.leaderOrder.addLast(leaderId);
        }
        record.lastObservedAt = nowMillis;
        if (record.activityId != null || nowMillis < record.nextOfferAt) {
            return;
        }
        NativeFlock flock = resolveNativeFlock(store, ref);
        if (flock == null || !flock.leaderRef().equals(ref)) return;
        if (record.flockId != null && !record.flockId.equals(flock.flockId())) {
            record.nextOfferAt = nowMillis + interruptionDelay(leaderId);
            return;
        }
        record.flockId = flock.flockId();
        record.nextOfferAt = nowMillis + offerDelay(flock.leaderId());
        tryAdmit(store, world, record, flock, role, nowMillis);
    }

    /** Publishes the current ambient target only when the requested asset branch owns it. */
    public boolean read(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull SensorPhase sensorPhase,
            @Nonnull TameworkTargetPositionInfo target) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sensorPhase, "sensorPhase");
        Objects.requireNonNull(target, "target");
        target.clear();
        if (closed || ref == null || !ref.isValid()) {
            return false;
        }
        UUID npcId = npcId(store, ref);
        if (npcId == null) {
            return false;
        }
        Activity activity = statesByStore.get(store).activityFor(npcId);
        if (activity == null
                || !activity.accepts(sensorPhase)
                || (sensorPhase == SensorPhase.DRINK && !activity.isDrinking(npcId))) {
            return false;
        }
        AmbientHerdPoint point = activity.targetFor(npcId);
        if (point == null) {
            return sensorPhase == SensorPhase.READY || sensorPhase == SensorPhase.ACTIVE;
        }
        target.setTarget(point.x(), point.y(), point.z());
        return true;
    }

    /** Cancels the source member's scene, including a leader-owned activity when applicable. */
    public void cancel(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Exit reason) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(reason, "reason");
        UUID npcId = npcId(store, ref);
        if (npcId == null) {
            return;
        }
        WorldState world = statesByStore.get(store);
        Activity activity = world.activityFor(npcId);
        if (activity != null) {
            cancelActivity(store, world, activity.id, reason, monotonicMillis());
        }
    }

    /** Advances at most eight due records for this world. Call this on the owning world thread. */
    public void tick(@Nonnull Store<EntityStore> store, long nowMillis) {
        Objects.requireNonNull(store, "store");
        if (closed) {
            return;
        }
        WorldState world = statesByStore.get(store);
        if (!isTickDue(world.lastTickAt, nowMillis)) return;
        world.lastTickAt = nowMillis;
        expire(world, store, nowMillis);
        int visits = 0;
        for (UUID activityId : world.nextActivities(MAX_VISITS_PER_TICK)) {
            Activity activity = world.activities.get(activityId);
            if (activity != null) advance(store, world, activity, nowMillis);
        }
    }

    /** Removes one world's retained state and releases all of its budget reservations. */
    public void clear(@Nonnull Store<EntityStore> store) {
        Objects.requireNonNull(store, "store");
        WorldState world = statesByStore.get(store);
        for (Activity activity : List.copyOf(world.activities.values())) {
            closeActivity(worldId(store), world, activity, Exit.UNLOADED, monotonicMillis());
        }
        for (CacheKey key : world.cache.keySet()) {
            budget.releaseCached(worldId(store), key.toString());
        }
        world.cache.clear();
        for (UUID leaderId : world.leaders.keySet()) {
            budget.releaseRegistered(worldId(store), leaderId);
        }
        budget.removeWorld(worldId(store));
        statesByStore.remove(store);
    }

    /**
     * Lifecycle fallback for a world that is already stopping. It releases scalar reservations
     * only; the normal world-thread clear remains responsible for borrowed path nodes.
     */
    public void forgetWorld(@Nonnull UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        for (Map.Entry<UUID, UUID> entry : admittedActivities.entrySet()) {
            if (worldId.equals(entry.getValue())
                    && admittedActivities.remove(entry.getKey(), worldId)) {
                budget.releaseActivity(worldId, entry.getKey());
            }
        }
        budget.removeWorld(worldId);
    }

    /** Stops admission and frees global reservations without retaining any live store reference. */
    @Override
    public void close() {
        closed = true;
        for (Map.Entry<UUID, UUID> entry : admittedActivities.entrySet()) {
            budget.releaseActivity(entry.getValue(), entry.getKey());
        }
        admittedActivities.clear();
    }

    private void tryAdmit(
            Store<EntityStore> store,
            WorldState world,
            LeaderRecord record,
            NativeFlock flock,
            Role leaderRole,
            long now) {
        List<UUID> roster = snapshotRoster(store, flock);
        if (roster.size() < 2 || roster.size() > 16) {
            record.nextOfferAt = now + failureDelay(record.leaderId);
            return;
        }
        AmbientHerdPoint origin = point(store, flock.leaderRef());
        if (origin == null) {
            record.nextOfferAt = now + failureDelay(record.leaderId);
            return;
        }
        Dimensions dimensions = rosterDimensions(store, roster, leaderRole);
        if (!Double.isFinite(dimensions.width)
                || !Double.isFinite(dimensions.height)
                || dimensions.width <= 0.0
                || dimensions.height <= 0.0
                || dimensions.width > 4.0
                || dimensions.height > 4.0) {
            record.nextOfferAt = now + failureDelay(record.leaderId);
            return;
        }
        UUID activityId =
                UUID.nameUUIDFromBytes(
                        (record.flockId + ":" + record.leaderId + ':' + now)
                                .getBytes(StandardCharsets.UTF_8));
        UUID worldId = worldId(store);
        if (!budget.tryAdmitActivity(worldId, activityId, roster.size())) {
            record.nextOfferAt = now + failureDelay(record.leaderId);
            return;
        }
        Activity activity =
                new Activity(
                        activityId,
                        record.flockId,
                        record.leaderId,
                        roster,
                        origin,
                        dimensions.width,
                        dimensions.height,
                        now);
        world.activities.put(activityId, activity);
        world.activityOrder.addLast(activityId);
        world.activityByMember.putAll(activity.members());
        record.activityId = activityId;
        admittedActivities.put(activityId, worldId);
    }

    private void advance(Store<EntityStore> store, WorldState world, Activity activity, long now) {
        activity.currentNow = now;
        if (now >= activity.totalDeadline) {
            cancelActivity(store, world, activity.id, Exit.TIMEOUT, now);
            return;
        }
        NativeFlock flock = resolveNativeFlockByLeader(store, activity.leaderId);
        if (flock == null
                || !flock.flockId().equals(activity.flockId)
                || !activity.leaderId.equals(flock.leaderId())) {
            cancelActivity(store, world, activity.id, Exit.LEADER_CHANGED, now);
            return;
        }
        LeaderRecord record = world.leaders.get(activity.leaderId);
        if (record != null) {
            record.lastObservedAt = now;
        }
        if (!refreshRoster(store, activity)) {
            cancelActivity(store, world, activity.id, Exit.MEMBER_CHANGED, now);
            return;
        }
        Ref<EntityStore> leaderRef = flock.leaderRef();
        NPCEntity leader = store.getComponent(leaderRef, NPCEntity.getComponentType());
        Role role = leader == null ? null : leader.getRole();
        AmbientHerdPoint leaderPoint = point(store, leaderRef);
        if (role == null || leaderPoint == null || TamedStateResolver.isTamed(leaderRef, store)) {
            cancelActivity(store, world, activity.id, Exit.UNLOADED, now);
            return;
        }
        switch (activity.phase) {
            case DISCOVER -> advanceDiscovery(store, world, activity, leaderPoint, now);
            case PLAN_OUTBOUND, PLAN_RETURN ->
                    advancePlan(store, world, activity, leaderRef, role, leaderPoint, now);
            case GATHER -> advanceGather(store, world, activity, leaderPoint, now);
            case TRAVEL, RETURN -> advanceTravel(store, world, activity, leaderPoint, now);
            case APPROACH -> advanceApproach(store, world, activity, now);
            case DRINK -> {
                if (now >= activity.phaseDeadline) {
                    if (activity.nextCohort()) activity.startApproach(now);
                    else activity.enterRest(now);
                }
            }
            case REST -> {
                if (now >= activity.phaseDeadline) activity.startReturnPlan(leaderPoint, now);
            }
        }
    }

    private void advanceDiscovery(
            Store<EntityStore> store,
            WorldState world,
            Activity activity,
            AmbientHerdPoint leaderPoint,
            long now) {
        if (now >= activity.discoveryDeadline) {
            cancelActivity(store, world, activity.id, Exit.TIMEOUT, now);
            return;
        }
        CacheKey cacheKey = CacheKey.of(leaderPoint, activity.width, activity.height);
        CacheEntry cached = world.cache.get(cacheKey);
        if (cached != null && cached.expiresAt > now) {
            if (cached.patch != null) {
                if (activity.validationCursor == null)
                    activity.validationCursor =
                            waterSearch.newValidationCursor(
                                    cached.patch, activity.width, activity.height);
                AmbientHerdWaterBankSearch.StepResult validation =
                        waterSearch.step(
                                activity.validationCursor, store, budget, worldId(store), now);
                if (validation.status() == AmbientHerdWaterBankSearch.SearchStatus.PENDING) return;
                if (validation.status() == AmbientHerdWaterBankSearch.SearchStatus.FOUND
                        && validation.patch() != null) {
                    activity.startOutboundPlan(validation.patch(), now);
                } else {
                    world.cache.remove(cacheKey);
                    budget.releaseCached(worldId(store), cacheKey.toString());
                    putCache(
                            store,
                            world,
                            cacheKey,
                            new CacheEntry(null, now + FAILED_CACHE_TTL_MS));
                    cancelActivity(store, world, activity.id, Exit.NO_WATER, now);
                }
            } else {
                cancelActivity(store, world, activity.id, Exit.NO_WATER, now);
            }
            return;
        }
        AmbientHerdWaterBankSearch.SearchCursor cursor = activity.waterCursor;
        AmbientHerdWaterBankSearch.StepResult result =
                waterSearch.step(cursor, store, budget, worldId(store), now);
        if (result.status() == AmbientHerdWaterBankSearch.SearchStatus.PENDING) {
            return;
        }
        if (result.status() == AmbientHerdWaterBankSearch.SearchStatus.MISS
                || result.patch() == null) {
            putCache(store, world, cacheKey, new CacheEntry(null, now + FAILED_CACHE_TTL_MS));
            cancelActivity(store, world, activity.id, Exit.NO_WATER, now);
            return;
        }
        putCache(store, world, cacheKey, new CacheEntry(result.patch(), now + CACHE_TTL_MS));
        activity.startOutboundPlan(result.patch(), now);
    }

    private void advancePlan(
            Store<EntityStore> store,
            WorldState world,
            Activity activity,
            Ref<EntityStore> leaderRef,
            Role role,
            AmbientHerdPoint leaderPoint,
            long now) {
        if (now >= activity.phaseDeadline) {
            cancelActivity(store, world, activity.id, Exit.TIMEOUT, now);
            return;
        }
        AmbientHerdPathPlanner.StepResult result =
                pathPlanner.step(
                        activity.pathJob, leaderRef, role, store, budget, worldId(store), now);
        if (result.status() == AmbientHerdPathPlanner.PathStatus.PENDING) {
            return;
        }
        if (result.status() == AmbientHerdPathPlanner.PathStatus.MISS || result.route().isEmpty()) {
            cancelActivity(store, world, activity.id, Exit.NO_PATH, now);
            return;
        }
        if (activity.phase == Phase.PLAN_OUTBOUND) {
            activity.startGather(result.route(), now);
        } else {
            activity.startReturn(result.route(), now);
        }
    }

    private void advanceGather(
            Store<EntityStore> store,
            WorldState world,
            Activity activity,
            AmbientHerdPoint leaderPoint,
            long now) {
        int gathered = 0;
        for (UUID member : activity.roster) {
            AmbientHerdPoint memberPoint = point(store, resolveRef(store, member));
            if (memberPoint != null
                    && distanceSquared(memberPoint, activity.origin) <= GATHER_RADIUS_SQUARED) {
                gathered++;
            }
        }
        if (gathered >= 2 && gathered * 4 >= activity.roster.size() * 3) {
            activity.startTravel(now);
        } else if (now >= activity.phaseDeadline) {
            cancelActivity(store, world, activity.id, Exit.BLOCKED, now);
        }
    }

    private void advanceTravel(
            Store<EntityStore> store,
            WorldState world,
            Activity activity,
            AmbientHerdPoint leaderPoint,
            long now) {
        if (now >= activity.phaseDeadline) {
            cancelActivity(store, world, activity.id, Exit.TIMEOUT, now);
            return;
        }
        AmbientHerdPoint waypoint = activity.leaderWaypoint();
        if (waypoint == null) {
            if (!activity.membersReachedRouteEnd(store, now)) {
                if (activity.leaderWaitAt == 0L) activity.leaderWaitAt = now;
                else if (now - activity.leaderWaitAt >= 6_000L)
                    cancelActivity(store, world, activity.id, Exit.BLOCKED, now);
                return;
            }
            activity.leaderWaitAt = 0L;
            if (activity.phase == Phase.TRAVEL) validateNextApproach(store, world, activity, now);
            else if (allAt(store, activity.roster, activity.origin))
                cancelActivity(store, world, activity.id, Exit.COMPLETE, now);
            else cancelActivity(store, world, activity.id, Exit.NO_PATH, now);
            return;
        }
        activity.advanceFollowers(store, now);
        if (distanceSquared(leaderPoint, waypoint) <= WAYPOINT_RADIUS_SQUARED) {
            activity.advanceWaypoint(now);
        }
    }

    private void advanceApproach(
            Store<EntityStore> store, WorldState world, Activity activity, long now) {
        if (now >= activity.phaseDeadline) {
            cancelActivity(store, world, activity.id, Exit.BLOCKED, now);
            return;
        }
        if (activity.approachValidationIndex < activity.roster.size()) {
            UUID member = activity.roster.get(activity.approachValidationIndex);
            Ref<EntityStore> ref = resolveRef(store, member);
            NPCEntity npc =
                    ref == null ? null : store.getComponent(ref, NPCEntity.getComponentType());
            AmbientHerdPathPlanner.ValidationStatus status =
                    pathPlanner.validateLocalSegment(
                            ref,
                            npc == null ? null : npc.getRole(),
                            store,
                            point(store, ref),
                            activity.approachTarget(member),
                            budget,
                            worldId(store),
                            now);
            if (status == AmbientHerdPathPlanner.ValidationStatus.PENDING) return;
            if (status == AmbientHerdPathPlanner.ValidationStatus.BLOCKED) {
                cancelActivity(store, world, activity.id, Exit.BLOCKED, now);
                return;
            }
            activity.approveApproach(member);
            activity.approachValidationIndex++;
        }
        if (activity.approachValidationIndex == activity.roster.size()
                && activity.cohortAtSlots(store)) {
            activity.startDrink(now);
        }
    }

    private static boolean allAt(
            Store<EntityStore> store, List<UUID> members, AmbientHerdPoint target) {
        for (UUID member : members) {
            AmbientHerdPoint point = point(store, resolveRef(store, member));
            if (point == null || distanceSquared(point, target) > GATHER_RADIUS_SQUARED)
                return false;
        }
        return !members.isEmpty();
    }

    private void validateNextApproach(
            Store<EntityStore> store, WorldState world, Activity activity, long now) {
        if (activity.patch == null || activity.approachValidationIndex >= activity.roster.size()) {
            activity.startApproach(now);
            return;
        }
        int index = activity.approachValidationIndex;
        Ref<EntityStore> memberRef = resolveRef(store, activity.roster.get(index));
        NPCEntity npc =
                memberRef == null
                        ? null
                        : store.getComponent(memberRef, NPCEntity.getComponentType());
        Role role = npc == null ? null : npc.getRole();
        if (memberRef == null || role == null) {
            cancelActivity(store, world, activity.id, Exit.MEMBER_CHANGED, now);
            return;
        }
        AmbientHerdPoint from =
                activity.stagingValidated ? activity.stagingFor(index) : point(store, memberRef);
        AmbientHerdPoint to =
                activity.stagingValidated ? activity.slotFor(index) : activity.stagingFor(index);
        if (from == null) {
            cancelActivity(store, world, activity.id, Exit.MEMBER_CHANGED, now);
            return;
        }
        AmbientHerdPathPlanner.ValidationStatus validation =
                pathPlanner.validateLocalSegment(
                        memberRef, role, store, from, to, budget, worldId(store), now);
        if (validation == AmbientHerdPathPlanner.ValidationStatus.PENDING) return;
        if (validation == AmbientHerdPathPlanner.ValidationStatus.BLOCKED) {
            cancelActivity(store, world, activity.id, Exit.BLOCKED, now);
            return;
        }
        activity.approachValidationIndex++;
        if (activity.approachValidationIndex >= activity.roster.size()) {
            if (!activity.stagingValidated) {
                activity.stagingValidated = true;
                activity.approachValidationIndex = 0;
            } else activity.startApproach(now);
        }
    }

    private void cancelActivity(
            Store<EntityStore> store,
            WorldState world,
            @Nullable UUID activityId,
            Exit reason,
            long now) {
        if (activityId == null) return;
        Activity activity = world.activities.get(activityId);
        if (activity != null) closeActivity(worldId(store), world, activity, reason, now);
    }

    private void closeActivity(
            UUID worldId, WorldState world, Activity activity, Exit reason, long now) {
        if (world.activities.remove(activity.id) == null) return;
        world.activityOrder.remove(activity.id);
        if (activity.pathJob != null) pathPlanner.clear(activity.pathJob);
        for (UUID member : activity.roster) world.activityByMember.remove(member, activity);
        LeaderRecord record = world.leaders.get(activity.leaderId);
        if (record != null) {
            record.activityId = null;
            record.nextOfferAt =
                    now
                            + (reason == Exit.COMPLETE
                                    ? successDelay(record.leaderId)
                                    : failureDelay(record.leaderId));
        }
        admittedActivities.remove(activity.id);
        budget.releaseActivity(worldId, activity.id);
        activity.exit = reason;
    }

    private void expire(WorldState world, Store<EntityStore> store, long now) {
        for (int visited = 0;
                visited < MAX_VISITS_PER_TICK && !world.leaderOrder.isEmpty();
                visited++) {
            UUID leaderId = world.leaderOrder.removeFirst();
            LeaderRecord record = world.leaders.get(leaderId);
            if (record == null) continue;
            if (record.activityId == null && now - record.lastObservedAt >= RECORD_TTL_MS) {
                budget.releaseRegistered(worldId(store), leaderId);
                world.leaders.remove(leaderId);
            } else {
                world.leaderOrder.addLast(leaderId);
            }
        }
        Iterator<Map.Entry<CacheKey, CacheEntry>> cache = world.cache.entrySet().iterator();
        while (cache.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> entry = cache.next();
            if (entry.getValue().expiresAt <= now) {
                budget.releaseCached(worldId(store), entry.getKey().toString());
                cache.remove();
            }
        }
    }

    private void putCache(
            Store<EntityStore> store, WorldState world, CacheKey key, CacheEntry entry) {
        CacheEntry previous = world.cache.put(key, entry);
        if (previous == null) {
            if (!budget.tryCache(worldId(store), key.toString())) {
                world.cache.remove(key);
                return;
            }
        }
        while (world.cache.size() > MAX_CACHE_ENTRIES_PER_WORLD) {
            Iterator<CacheKey> entries = world.cache.keySet().iterator();
            if (!entries.hasNext()) return;
            CacheKey evicted = entries.next();
            entries.remove();
            budget.releaseCached(worldId(store), evicted.toString());
        }
    }

    @Nullable
    private static NativeFlock resolveNativeFlock(
            Store<EntityStore> store, Ref<EntityStore> memberRef) {
        FlockMembership membership =
                store.getComponent(memberRef, FlockMembership.getComponentType());
        Ref<EntityStore> flockRef = membership == null ? null : membership.getFlockRef();
        UUID flockId = membership == null ? null : membership.getFlockId();
        EntityGroup group =
                flockRef == null || !flockRef.isValid()
                        ? null
                        : store.getComponent(flockRef, EntityGroup.getComponentType());
        Ref<EntityStore> leaderRef =
                group == null || group.isDissolved() ? null : group.getLeaderRef();
        UUID leaderId = leaderRef == null ? null : npcId(store, leaderRef);
        return flockId == null || leaderRef == null || !leaderRef.isValid() || leaderId == null
                ? null
                : new NativeFlock(flockId, leaderId, leaderRef, group);
    }

    @Nullable
    private static NativeFlock resolveNativeFlockByLeader(Store<EntityStore> store, UUID leaderId) {
        Ref<EntityStore> ref = resolveRef(store, leaderId);
        return ref == null ? null : resolveNativeFlock(store, ref);
    }

    @Nullable
    private static Ref<EntityStore> resolveRef(Store<EntityStore> store, UUID id) {
        EntityStore entityStore = store.getExternalData();
        Ref<EntityStore> ref = entityStore == null ? null : entityStore.getRefFromUUID(id);
        return ref != null && ref.isValid() ? ref : null;
    }

    @Nullable
    private static UUID npcId(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        UUIDComponent identity = store.getComponent(ref, UUIDComponent.getComponentType());
        return identity == null ? null : identity.getUuid();
    }

    @Nullable
    private static AmbientHerdPoint point(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        TransformComponent transform =
                store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d value = transform == null ? null : transform.getPosition();
        return value == null ? null : new AmbientHerdPoint(value.x, value.y, value.z);
    }

    private static List<UUID> snapshotRoster(Store<EntityStore> store, NativeFlock flock) {
        Set<UUID> roster = new LinkedHashSet<>();
        if (flock.group().size() < 2 || flock.group().size() > 16) return List.of();
        for (Ref<EntityStore> member : flock.group().getMemberList()) {
            UUID id = npcId(store, member);
            NPCEntity npc =
                    member == null
                            ? null
                            : store.getComponent(member, NPCEntity.getComponentType());
            if (id != null
                    && npc != null
                    && allowsAmbientRole(npc)
                    && !TamedStateResolver.isTamed(member, store)) roster.add(id);
            if (roster.size() > 16) return List.of();
        }
        return List.copyOf(roster);
    }

    private static boolean refreshRoster(Store<EntityStore> store, Activity activity) {
        int valid = 0;
        for (UUID member : activity.roster) {
            Ref<EntityStore> ref = resolveRef(store, member);
            NPCEntity npc =
                    ref == null ? null : store.getComponent(ref, NPCEntity.getComponentType());
            FlockMembership membership =
                    ref == null
                            ? null
                            : store.getComponent(ref, FlockMembership.getComponentType());
            if (ref != null
                    && npc != null
                    && allowsAmbientRole(npc)
                    && nativeStateAllowsAmbient(store, ref)
                    && !TamedStateResolver.isTamed(ref, store)
                    && point(store, ref) != null
                    && membership != null
                    && activity.flockId.equals(membership.getFlockId())) valid++;
        }
        return valid == activity.roster.size() && valid >= 2;
    }

    private static boolean nativeStateAllowsAmbient(
            Store<EntityStore> store, Ref<EntityStore> ref) {
        StateSupport state = store.getComponent(ref, StateSupport.getComponentType());
        return state != null
                && (state.inState(state.getStateHelper().getStateIndex("Idle"))
                        || state.inState(state.getStateHelper().getStateIndex("Ambient")));
    }

    private static Dimensions rosterDimensions(
            Store<EntityStore> store, List<UUID> roster, Role fallback) {
        double width = 0.5, height = 1.0;
        for (UUID member : roster) {
            Ref<EntityStore> ref = resolveRef(store, member);
            NPCEntity npc =
                    ref == null ? null : store.getComponent(ref, NPCEntity.getComponentType());
            Role role = npc == null ? fallback : npc.getRole();
            if (role == null
                    || role.getActiveMotionController() == null
                    || role.getActiveMotionController().getCollisionBoundingBox() == null) continue;
            var box = role.getActiveMotionController().getCollisionBoundingBox();
            width = Math.max(width, Math.max(box.width(), box.depth()));
            height = Math.max(height, box.height());
        }
        return new Dimensions(width, height);
    }

    /** Stable scalar key for budget/lifecycle calls concerning this store's owning world. */
    public static UUID worldIdFor(@Nonnull Store<EntityStore> store) {
        Objects.requireNonNull(store, "store");
        EntityStore entityStore = store.getExternalData();
        if (entityStore != null
                && entityStore.getWorld() != null
                && entityStore.getWorld().getWorldConfig() != null
                && entityStore.getWorld().getWorldConfig().getUuid() != null) {
            return entityStore.getWorld().getWorldConfig().getUuid();
        }
        return UUID.nameUUIDFromBytes(
                ("ambient:store:" + store.getStoreIndex()).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID worldId(Store<EntityStore> store) {
        return worldIdFor(store);
    }

    private static boolean allowsAmbientRole(NPCEntity npc) {
        return TwGlobalConfig.resolveActive().allowsAmbientHerdRole(npc.getRoleName());
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    static boolean isTickDue(long previousTick, long nowMillis) {
        return previousTick == Long.MIN_VALUE || nowMillis - previousTick >= 250L;
    }

    private static double distanceSquared(AmbientHerdPoint left, AmbientHerdPoint right) {
        double x = left.x() - right.x(), y = left.y() - right.y(), z = left.z() - right.z();
        return x * x + y * y + z * z;
    }

    private static long stable(UUID id, long low, long high, long salt) {
        return low
                + Math.floorMod(
                        id.getLeastSignificantBits() ^ id.getMostSignificantBits() ^ salt,
                        high - low + 1L);
    }

    private static long initialDue(UUID id, long now) {
        return now + stable(id, INITIAL_DELAY_MIN_MS, INITIAL_DELAY_MAX_MS, 3L);
    }

    private static long offerDelay(UUID id) {
        return stable(id, OFFER_MIN_MS, OFFER_MAX_MS, 5L);
    }

    private static long successDelay(UUID id) {
        return stable(id, SUCCESS_COOLDOWN_MIN_MS, SUCCESS_COOLDOWN_MAX_MS, 7L);
    }

    private static long failureDelay(UUID id) {
        return stable(id, FAILURE_COOLDOWN_MIN_MS, FAILURE_COOLDOWN_MAX_MS, 11L);
    }

    private static long interruptionDelay(UUID id) {
        return Math.max(FAILURE_COOLDOWN_MIN_MS, failureDelay(id));
    }

    private enum Phase {
        DISCOVER,
        PLAN_OUTBOUND,
        GATHER,
        TRAVEL,
        APPROACH,
        DRINK,
        REST,
        PLAN_RETURN,
        RETURN
    }

    private record NativeFlock(
            UUID flockId, UUID leaderId, Ref<EntityStore> leaderRef, EntityGroup group) {}

    private record Dimensions(double width, double height) {}

    private record CacheKey(int x, int y, int z, long width, long height) {
        static CacheKey of(AmbientHerdPoint point, double width, double height) {
            return new CacheKey(
                    Math.floorDiv(point.blockX(), 16),
                    point.blockY(),
                    Math.floorDiv(point.blockZ(), 16),
                    Math.round(width * 100),
                    Math.round(height * 100));
        }
    }

    private record CacheEntry(
            @Nullable AmbientHerdWaterBankSearch.BankPatch patch, long expiresAt) {}

    private static final class LeaderRecord {
        final UUID leaderId;
        @Nullable UUID flockId;
        long nextOfferAt, lastObservedAt;
        @Nullable UUID activityId;

        LeaderRecord(UUID leaderId, long nextOfferAt) {
            this.leaderId = leaderId;
            this.nextOfferAt = nextOfferAt;
        }
    }

    private static final class WorldState {
        final Map<UUID, LeaderRecord> leaders = new LinkedHashMap<>();
        final java.util.ArrayDeque<UUID> leaderOrder = new java.util.ArrayDeque<>();
        final Map<UUID, Activity> activities = new LinkedHashMap<>();
        final Map<UUID, Activity> activityByMember = new LinkedHashMap<>();
        final LinkedHashMap<CacheKey, CacheEntry> cache = new LinkedHashMap<>();
        final java.util.ArrayDeque<UUID> activityOrder = new java.util.ArrayDeque<>();
        long lastTickAt = Long.MIN_VALUE;

        @Nullable
        Activity activityFor(UUID member) {
            return activityByMember.get(member);
        }

        List<UUID> nextActivities(int maximum) {
            List<UUID> due = new ArrayList<>(maximum);
            for (int i = 0, count = Math.min(maximum, activityOrder.size());
                    i < count && !activityOrder.isEmpty();
                    i++) {
                UUID id = activityOrder.removeFirst();
                if (activities.containsKey(id)) {
                    due.add(id);
                    activityOrder.addLast(id);
                }
            }
            return due;
        }
    }

    /** Small pure sequence used by production activity state and focused tests. */
    static final class Activity {
        final UUID id, flockId, leaderId;
        final List<UUID> roster;
        final AmbientHerdPoint origin;
        final double width, height;
        final long startedAt, discoveryDeadline, totalDeadline;
        long travelStartedAt, leaderWaitAt;
        final Map<UUID, Activity> memberIndex = new LinkedHashMap<>();
        final Map<UUID, Integer> followerWaypoint = new LinkedHashMap<>();
        final Set<UUID> approvedApproaches = new LinkedHashSet<>();
        Phase phase = Phase.DISCOVER;
        long phaseDeadline, currentNow;
        int waypointIndex, approachValidationIndex, cohort;
        boolean stagingValidated;
        @Nullable Exit exit;
        @Nullable AmbientHerdWaterBankSearch.SearchCursor waterCursor, validationCursor;
        @Nullable AmbientHerdWaterBankSearch.BankPatch patch;
        @Nullable AmbientHerdPathPlanner.SearchJob pathJob;
        List<AmbientHerdPoint> route = List.of();

        Activity(
                UUID id,
                UUID flockId,
                UUID leaderId,
                List<UUID> roster,
                AmbientHerdPoint origin,
                double width,
                double height,
                long now) {
            this.id = id;
            this.flockId = flockId;
            this.leaderId = leaderId;
            this.roster = List.copyOf(roster);
            this.origin = origin;
            this.width = width;
            this.height = height;
            startedAt = now;
            discoveryDeadline = now + DISCOVERY_TIMEOUT_MS;
            totalDeadline = now + ACTIVITY_TIMEOUT_MS;
            phaseDeadline = discoveryDeadline;
            waterCursor =
                    new AmbientHerdWaterBankSearch()
                            .newCursor(origin, leaderId.getLeastSignificantBits(), width, height);
            for (UUID member : roster) {
                memberIndex.put(member, this);
                if (!member.equals(leaderId)) followerWaypoint.put(member, 0);
            }
        }

        Map<UUID, Activity> members() {
            return memberIndex;
        }

        boolean accepts(SensorPhase requested) {
            return switch (requested) {
                case READY, ACTIVE -> phase != Phase.DISCOVER && phase != Phase.PLAN_OUTBOUND;
                case MOVE ->
                        phase == Phase.GATHER
                                || phase == Phase.TRAVEL
                                || phase == Phase.APPROACH
                                || phase == Phase.RETURN;
                case DRINK -> phase == Phase.DRINK;
                case REST -> phase == Phase.REST;
            };
        }

        boolean isDrinking(UUID member) {
            return phase == Phase.DRINK && currentCohort().contains(member);
        }

        @Nullable
        AmbientHerdPoint targetFor(UUID member) {
            if (phase == Phase.PLAN_RETURN) return null;
            if (phase == Phase.GATHER) return origin;
            if (patch == null) return leaderWaypoint();
            if (phase == Phase.DRINK) return patch.water();
            if (phase == Phase.REST)
                return patch.staging()
                        .get(Math.floorMod(roster.indexOf(member), patch.staging().size()));
            if (phase == Phase.APPROACH)
                return approvedApproaches.contains(member) ? approachTarget(member) : null;
            if ((phase == Phase.TRAVEL || phase == Phase.RETURN) && !member.equals(leaderId)) {
                if (currentNow - travelStartedAt < stable(member, 300L, 2_000L, 19L)) return null;
                int index = followerWaypoint.getOrDefault(member, 0);
                return index < route.size()
                        ? route.get(index)
                        : route.isEmpty() ? null : route.getLast();
            }
            return leaderWaypoint();
        }

        @Nullable
        AmbientHerdPoint leaderWaypoint() {
            return waypointIndex < route.size() ? route.get(waypointIndex) : null;
        }

        void startOutboundPlan(AmbientHerdWaterBankSearch.BankPatch patch, long now) {
            this.patch = patch;
            phase = Phase.PLAN_OUTBOUND;
            phaseDeadline = discoveryDeadline;
            pathJob = new AmbientHerdPathPlanner().newJob(origin, patch.staging().getFirst());
        }

        void startGather(List<AmbientHerdPoint> route, long now) {
            this.route = List.copyOf(route);
            waypointIndex = 0;
            phase = Phase.GATHER;
            phaseDeadline = Math.min(totalDeadline, now + GATHER_TIMEOUT_MS);
        }

        void startTravel(long now) {
            phase = Phase.TRAVEL;
            travelStartedAt = now;
            leaderWaitAt = 0L;
            phaseDeadline = Math.min(totalDeadline, now + TRAVEL_TIMEOUT_MS);
            resetFollowerWaypoints();
        }

        void advanceWaypoint(long now) {
            waypointIndex++;
        }

        void approveApproach(UUID member) {
            approvedApproaches.add(member);
        }

        AmbientHerdPoint approachTarget(UUID member) {
            return currentCohort().contains(member)
                    ? slotFor(roster.indexOf(member))
                    : stagingFor(roster.indexOf(member));
        }

        void startApproach(long now) {
            approvedApproaches.clear();
            approachValidationIndex = 0;
            phase = Phase.APPROACH;
            phaseDeadline = Math.min(totalDeadline, now + TRAVEL_TIMEOUT_MS);
        }

        void startDrink(long now) {
            phase = Phase.DRINK;
            phaseDeadline = Math.min(totalDeadline, now + stable(leaderId, 6_000L, 10_000L, 13L));
        }

        List<UUID> currentCohort() {
            int size = patch == null ? 1 : patch.slots().size();
            int start = cohort * size;
            return start >= roster.size()
                    ? List.of()
                    : roster.subList(start, Math.min(roster.size(), start + size));
        }

        AmbientHerdPoint slotFor(int rosterIndex) {
            return patch.slots().get(Math.floorMod(rosterIndex, patch.slots().size()));
        }

        AmbientHerdPoint stagingFor(int rosterIndex) {
            return patch.staging().get(Math.floorMod(rosterIndex, patch.staging().size()));
        }

        boolean cohortAtSlots(Store<EntityStore> store) {
            for (UUID member : currentCohort()) {
                AmbientHerdPoint value = point(store, resolveRef(store, member));
                if (value == null
                        || distanceSquared(value, slotFor(roster.indexOf(member)))
                                > WAYPOINT_RADIUS_SQUARED) return false;
            }
            return !currentCohort().isEmpty();
        }

        boolean nextCohort() {
            cohort++;
            return patch != null && cohort * patch.slots().size() < roster.size();
        }

        void enterRest(long now) {
            phase = Phase.REST;
            phaseDeadline = Math.min(totalDeadline, now + stable(leaderId, 15_000L, 30_000L, 17L));
        }

        void startReturnPlan(AmbientHerdPoint currentLeaderPoint, long now) {
            phase = Phase.PLAN_RETURN;
            phaseDeadline = Math.min(totalDeadline, now + TRAVEL_TIMEOUT_MS);
            pathJob = new AmbientHerdPathPlanner().newJob(currentLeaderPoint, origin);
        }

        void startReturn(List<AmbientHerdPoint> route, long now) {
            this.route = List.copyOf(route);
            waypointIndex = 0;
            phase = Phase.RETURN;
            travelStartedAt = now;
            leaderWaitAt = 0L;
            phaseDeadline = Math.min(totalDeadline, now + TRAVEL_TIMEOUT_MS);
            resetFollowerWaypoints();
        }

        void advanceFollowers(Store<EntityStore> store, long now) {
            for (UUID member : followerWaypoint.keySet()) {
                long delay = stable(member, 300L, 2_000L, 19L);
                if (now - travelStartedAt < delay) continue;
                int index = followerWaypoint.get(member);
                if (index >= route.size()) continue;
                AmbientHerdPoint position = point(store, resolveRef(store, member));
                if (position != null
                        && distanceSquared(position, route.get(index)) <= WAYPOINT_RADIUS_SQUARED)
                    followerWaypoint.put(member, index + 1);
            }
        }

        boolean membersReachedRouteEnd(Store<EntityStore> store, long now) {
            advanceFollowers(store, now);
            for (int index : followerWaypoint.values()) if (index < route.size()) return false;
            return true;
        }

        private void resetFollowerWaypoints() {
            for (UUID member : followerWaypoint.keySet()) followerWaypoint.put(member, 0);
        }
    }
}
