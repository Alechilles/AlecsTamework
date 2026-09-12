package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
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
                        boolean flying, double clearance, Store<EntityStore> store) {
        UUIDComponent identity = store.getComponent(self, UUIDComponent.getComponentType());
        UUIDComponent masterIdentity = store.getComponent(master, UUIDComponent.getComponentType());
        if (identity == null || masterIdentity == null
                || !eligible(self, master, targetSlot, store)) return null;
        State state = states.get(store);
        UUID id = identity.getUuid();
        UUID ownerId = masterIdentity.getUuid();
        Member member = state.members.get(id);
        if (member == null) {
            member = new Member(ownerId, targetSlot, flying);
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
        Ref<EntityStore> flockRef = flockOf(self, store);
        EntityGroup group = flockRef == null ? null : store.getComponent(flockRef, EntityGroup.getComponentType());
        if (member.slot < 0 || group == null || !master.equals(group.getLeaderRef())
                || !group.isMember(self) || !sameFlock(flockRef, member.flockId, store)) {
            queue(store, state);
            return null;
        }
        double spacing = clearance;
        for (Member other : state.members.values()) {
            if (other.ownerId.equals(ownerId) && other.flying == flying && other.slot >= 0) {
                spacing = Math.max(spacing, other.clearance);
            }
        }
        return new Slot(member.slot, spacing);
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
            if (!flockId.equals(member.flockId)) member.slot = -1;
            member.flockId = flockId;
            if (member.slot < 0) member.slot = availableSlot(state, member);
        }
    }

    private static int availableSlot(State state, Member member) {
        int slot = 0;
        while (true) {
            boolean used = false;
            for (Member other : state.members.values()) {
                if (other != member && other.flying == member.flying
                        && other.ownerId.equals(member.ownerId) && other.slot == slot) {
                    used = true;
                    break;
                }
            }
            if (!used) return slot;
            slot++;
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

    public record Slot(int index, double spacing) { }

    private static final class State {
        final Map<UUID, Member> members = new HashMap<>();
        boolean queued;
        float elapsed;
    }

    private static final class Member {
        final UUID ownerId;
        final int targetSlot;
        final boolean flying;
        UUID flockId;
        int slot = -1;
        double clearance;
        long lastSeen;
        Member(UUID ownerId, int targetSlot, boolean flying) {
            this.ownerId = ownerId;
            this.targetSlot = targetSlot;
            this.flying = flying;
        }
    }
}
