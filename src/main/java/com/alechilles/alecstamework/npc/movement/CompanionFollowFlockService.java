package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Runtime-only follow intents. Native flocks remain the membership authority.
 * All access is on the owning world thread; values contain IDs, never live refs/components.
 */
public final class CompanionFollowFlockService {
    private static final CompanionFollowFlockService INSTANCE = new CompanionFollowFlockService();
    private static final long LEASE_MS = 2000;
    private final StoreScopedState<State> states = new StoreScopedState<>(State::new);

    public static CompanionFollowFlockService get() { return INSTANCE; }

    /** Called by the follow sensor. Structural writes are deferred out of sensor evaluation. */
    @Nullable
    public Slot request(Ref<EntityStore> self, Ref<EntityStore> master, int targetSlot,
                        boolean flying, double clearance, double range, double altitude, Store<EntityStore> store) {
        UUIDComponent identity = store.getComponent(self, UUIDComponent.getComponentType());
        UUIDComponent masterIdentity = store.getComponent(master, UUIDComponent.getComponentType());
        if (identity == null || masterIdentity == null
                || !eligible(self, master, targetSlot, store)) return null;
        var selfTransform = store.getComponent(self, TransformComponent.getComponentType());
        var ownerTransform = store.getComponent(master, TransformComponent.getComponentType());
        if (selfTransform == null || ownerTransform == null) return null;
        State state = states.get(store);
        UUID id = identity.getUuid();
        UUID ownerId = masterIdentity.getUuid();
        Member member = state.members.get(id);
        if (member == null) {
            member = new Member(ownerId, targetSlot, flying);
            member.group = state.groups.computeIfAbsent(new GroupKey(ownerId, flying), ignored -> new FollowGroup());
            state.members.put(id, member);
            queue(store, state);
        }
        if (!member.ownerId.equals(ownerId) || member.flying != flying) {
            member.lastSeen = 0;
            queue(store, state);
            return null;
        }
        member.lastSeen = System.currentTimeMillis();
        member.clearance = clearance;
        member.range = range;
        member.position.set(selfTransform.getPosition());
        int index = member.group.assignments.claim(id, 0, member.lastSeen);
        Ref<EntityStore> flockRef = flockOf(self, store);
        EntityGroup group = flockRef == null ? null : store.getComponent(flockRef, EntityGroup.getComponentType());
        if (!member.group.ready || index > member.group.maxSlot || group == null || !master.equals(group.getLeaderRef())
                || !group.isMember(self) || !sameFlock(flockRef, member.flockId, store)) {
            queue(store, state);
            return null;
        }
        FollowGroup follow = member.group;
        follow.formation.target(ownerTransform.getPosition(), index, follow.target);
        double height = flying ? ownerTransform.getPosition().y + altitude + (index % 3) * 1.5 : 0;
        follow.target.y = height;
        follow.position.set(member.position);
        if (!flying) follow.position.y = 0;
        follow.assignments.report(id, follow.position, follow.target, follow.spacing, member.lastSeen);
        // A swap is consumed on the next sensor update; no slot can have two owners.
        follow.assignments.rebalance(member.lastSeen);
        return new Slot(index, follow.spacing, follow.target.x, follow.target.z);

    }

    /** Only visits active follow intents, twice per second; idle worlds do no entity scan. */
    public void tick(float dt, Store<EntityStore> store) {
        State state = states.get(store);
        state.elapsed += dt;
        if (state.elapsed < 0.5f || state.members.isEmpty()) return;
        state.elapsed = 0;
        queue(store, state);
    }

    private void queue(Store<EntityStore> store, State state) {
        if (state.queued) return;
        state.queued = true;
        World world = store.getExternalData().getWorld();
        // TickingSystem and sensor callbacks have no CommandBuffer. Resolve current entities
        // after ECS processing, using IDs and the world which owns this callback.
        world.execute(() -> reconcile(world));
    }

    private void reconcile(World world) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        State state = states.get(store);
        state.queued = false;
        long now = System.currentTimeMillis();
        var iterator = state.members.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Member member = entry.getValue();
            Ref<EntityStore> self = world.getEntityRef(entry.getKey());
            Ref<EntityStore> owner = world.getEntityRef(member.ownerId);
            if (now - member.lastSeen > LEASE_MS || !valid(self) || !valid(owner)
                    || !eligible(self, owner, member.targetSlot, store)) {
                leave(self, member, store);
                iterator.remove();
                continue;
            }
            Ref<EntityStore> flockRef = flockOf(owner, store);
            if (flockRef == null) {
                flockRef = FlockPlugin.createFlock(store, null, new String[0]);
                FlockMembershipSystems.join(owner, flockRef, store);
            }
            EntityGroup group = store.getComponent(flockRef, EntityGroup.getComponentType());
            if (group == null || group.isDissolved() || !owner.equals(group.getLeaderRef())) continue;
            UUID flockId = store.getComponent(flockRef, UUIDComponent.getComponentType()).getUuid();
            Ref<EntityStore> current = flockOf(self, store);
            if (!flockRef.equals(current)) {
                // Explicit tame/owner/Follow validation replaces wild-flock role/size admission.
                // join handles leaving the former herd and defers rejoining when needed.
                FlockMembershipSystems.join(self, flockRef, store);
            }
            member.flockId = flockId;

        }
        refreshGroups(state);
    }

    /** Refresh group geometry from active intent snapshots, never by scanning world entities. */
    private static void refreshGroups(State state) {
        for (FollowGroup group : state.groups.values()) {
            group.count = 0;
            group.maxSlot = -1;
            group.spacing = 0;
            group.range = Double.MAX_VALUE;
            group.centroid.zero();
        }
        for (var entry : state.members.entrySet()) {
            Member member = entry.getValue();
            FollowGroup group = member.group;
            int slot = group.assignments.slot(entry.getKey());
            if (slot < 0) continue;
            group.count++;
            group.maxSlot = Math.max(group.maxSlot, slot);
            group.spacing = Math.max(group.spacing, member.clearance);
            group.range = Math.min(group.range, member.range);
            group.centroid.add(member.position);
        }
        var iterator = state.groups.values().iterator();
        while (iterator.hasNext()) {
            FollowGroup group = iterator.next();
            if (group.count == 0) {
                iterator.remove();
                continue;
            }
            group.centroid.div(group.count);
            group.formation.configure(group.centroid, group.maxSlot + 1, group.spacing, group.range);
            group.ready = true;
        }
    }

    private static void leave(Ref<EntityStore> self, Member member, Store<EntityStore> store) {
        if (valid(self) && sameFlock(flockOf(self, store), member.flockId, store)) {
            store.tryRemoveComponent(self, FlockMembership.getComponentType());
        }
    }

    private static boolean eligible(Ref<EntityStore> self, Ref<EntityStore> owner,
                                   int targetSlot, Store<EntityStore> store) {
        if (!valid(self) || !valid(owner)) return false;
        var ownerType = TameworkOwnerComponent.getComponentType();
        var tamedType = TameworkTamedComponent.getComponentType();
        if (ownerType == null || tamedType == null) return false;
        var ownership = store.getComponent(self, ownerType);
        var tame = store.getComponent(self, tamedType);
        var player = store.getComponent(owner, Player.getComponentType());
        var identity = store.getComponent(owner, UUIDComponent.getComponentType());
        var npc = store.getComponent(self, NPCEntity.getComponentType());
        if (ownership == null || tame == null || player == null || identity == null
                || npc == null || npc.getRole() == null) return false;
        var role = npc.getRole();
        var state = NpcSupportAccess.state(role, self, store);
        var targets = NpcSupportAccess.markedEntity(role, self, store);
        return state != null && targets != null && hasFollowAuthority(tame.isTamed(), ownership.getOwnerId(),
                identity.getUuid(), player.getGameMode(), state.getStateName(),
                owner.equals(targets.getMarkedEntityRef(targetSlot)));
    }

    static boolean hasFollowAuthority(boolean tamed, UUID ownerId, UUID playerId,
                                      GameMode mode, String state, boolean targetMatches) {
        return tamed && ownerId != null && ownerId.equals(playerId) && mode == GameMode.Adventure
                && targetMatches && state != null && (state.equals("Follow") || state.startsWith("Follow."));
    }

    @Nullable
    private static Ref<EntityStore> flockOf(Ref<EntityStore> ref, Store<EntityStore> store) {
        var membership = store.getComponent(ref, FlockMembership.getComponentType());
        var flock = membership == null ? null : membership.getFlockRef();
        return valid(flock) ? flock : null;
    }

    private static boolean sameFlock(Ref<EntityStore> ref, UUID id, Store<EntityStore> store) {
        if (!valid(ref) || id == null) return false;
        var identity = store.getComponent(ref, UUIDComponent.getComponentType());
        return identity != null && id.equals(identity.getUuid());
    }

    private static boolean valid(Ref<EntityStore> ref) { return ref != null && ref.isValid(); }

    public record Slot(int index, double spacing, double x, double z) { }

    private record GroupKey(UUID owner, boolean flying) { }

    private static final class FollowGroup {
        final FormationSlotAssignments assignments = new FormationSlotAssignments();
        final CompanionFollowFormation formation = new CompanionFollowFormation();
        final Vector3d centroid = new Vector3d();
        final Vector3d target = new Vector3d();
        final Vector3d position = new Vector3d();
        int count;
        int maxSlot;
        double spacing;
        double range;
        boolean ready;
    }

    private static final class State {
        final Map<UUID, Member> members = new HashMap<>();
        final Map<GroupKey, FollowGroup> groups = new HashMap<>();
        boolean queued;
        float elapsed;
    }

    private static final class Member {
        final UUID ownerId;
        final int targetSlot;
        final boolean flying;
        UUID flockId;
        FollowGroup group;
        final Vector3d position = new Vector3d();
        double range;
        double clearance;
        long lastSeen;
        Member(UUID ownerId, int targetSlot, boolean flying) {
            this.ownerId = ownerId;
            this.targetSlot = targetSlot;
            this.flying = flying;
        }
    }
}
