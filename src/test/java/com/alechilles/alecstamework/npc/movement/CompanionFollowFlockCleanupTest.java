package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class CompanionFollowFlockCleanupTest {
    @Test
    void restoredIdleAndFollowCloseMembershipsLeaveThePlayerFlock() throws Exception {
        assertInactiveStateLeaves("Idle.Default", FlockMembership.Type.MEMBER);
        assertInactiveStateLeaves("FollowClose.Default", FlockMembership.Type.MEMBER);
    }

    @Test
    void idleMembershipStillJoiningThePlayerFlockIsCancelled() throws Exception {
        assertInactiveStateLeaves("Idle.Default", FlockMembership.Type.JOINING);
    }

    @Test
    void activeAdventureFollowRetainsThePlayerFlockMembership() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockCleanupScope scope = FlockCleanupScope.install();
             FlockFixture fixture = new FlockFixture(scope, "Follow.Default")) {
            assertTrue(CompanionFollowFlockService.retainPlayerFlockMembership(
                    fixture.companionRef, fixture.store));
            assertTrue(fixture.membershipPresent());
        }
    }

    @Test
    void ordinaryNpcLedFlocksKeepTheirNativeMembership() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockCleanupScope scope = FlockCleanupScope.install();
             FlockFixture fixture = new FlockFixture(scope, "Idle.Default")) {
            fixture.group.setLeaderRef(fixture.interimLeaderRef);
            fixture.putLeaderMembership(fixture.interimLeaderRef, FlockMembership.Type.LEADER);

            assertFalse(CompanionFollowFlockService.retainPlayerFlockMembership(
                    fixture.companionRef, fixture.store));
            assertTrue(fixture.membershipPresent());
        }
    }

    @Test
    void unresolvedAndInterimLeaderMembershipsWaitForPlayerLeadershipBeforeCleanup()
            throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockCleanupScope scope = FlockCleanupScope.install();
             FlockFixture fixture = new FlockFixture(scope, "Idle.Default")) {
            fixture.membership.setFlockRef(null);
            assertTrue(CompanionFollowFlockService.retainPlayerFlockMembership(
                    fixture.companionRef, fixture.store));
            assertTrue(fixture.membershipPresent());

            fixture.membership.setFlockRef(fixture.flockRef);
            fixture.group.setLeaderRef(fixture.interimLeaderRef);
            fixture.putLeaderMembership(fixture.interimLeaderRef,
                    FlockMembership.Type.INTERIM_LEADER);
            assertTrue(CompanionFollowFlockService.retainPlayerFlockMembership(
                    fixture.companionRef, fixture.store));
            assertTrue(fixture.membershipPresent());

            fixture.group.setLeaderRef(fixture.playerRef);
            assertFalse(CompanionFollowFlockService.retainPlayerFlockMembership(
                    fixture.companionRef, fixture.store));
            assertNull(fixture.store.getComponent(fixture.companionRef,
                    FlockMembership.getComponentType()));
        }
    }

    private static void assertInactiveStateLeaves(String state, FlockMembership.Type membershipType)
            throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockCleanupScope scope = FlockCleanupScope.install();
             FlockFixture fixture = new FlockFixture(scope, state)) {
            fixture.membership.setMembershipType(membershipType);

            assertFalse(CompanionFollowFlockService.retainPlayerFlockMembership(
                    fixture.companionRef, fixture.store));
            assertNull(fixture.store.getComponent(fixture.companionRef,
                    FlockMembership.getComponentType()));
        }
    }

    private static final class FlockFixture implements AutoCloseable {
        private final FlockCleanupScope scope;
        private final TestEntityComponentStore store;
        private final Ref<EntityStore> companionRef;
        private final Ref<EntityStore> playerRef;
        private final Ref<EntityStore> interimLeaderRef;
        private final Ref<EntityStore> flockRef;
        private final EntityGroup group;
        private final FlockMembership membership;

        private FlockFixture(FlockCleanupScope scope, String state) throws Exception {
            this.scope = scope;
            store = new CleanupStore(allocate(EntityStore.class));
            companionRef = store.createReference();
            playerRef = store.createReference();
            interimLeaderRef = store.createReference();
            flockRef = store.createReference();

            group = new EntityGroup();
            group.setLeaderRef(playerRef);
            group.add(companionRef);
            group.add(playerRef);
            group.add(interimLeaderRef);
            store.put(flockRef, scope.entityGroupType, group);

            Player player = allocate(Player.class);
            setField(player, Player.class, "gameMode", GameMode.Adventure);
            store.put(playerRef, Player.getComponentType(), player);
            store.put(playerRef, UUIDComponent.getComponentType(), new UUIDComponent(UUID.randomUUID()));

            NPCEntity companion = new NPCEntity();
            companion.setRole(allocate(Role.class));
            store.put(companionRef, NPCEntity.getComponentType(), companion);
            store.put(companionRef, TameworkOwnerComponent.getComponentType(),
                    new TameworkOwnerComponent(store.getComponent(playerRef,
                            UUIDComponent.getComponentType()).getUuid(), "Owner"));
            store.put(companionRef, TameworkTamedComponent.getComponentType(),
                    new TameworkTamedComponent(true));
            StateNameSupport stateSupport = allocate(StateNameSupport.class);
            stateSupport.stateName = state;
            store.put(companionRef, scope.stateSupportType, stateSupport);

            membership = new FlockMembership();
            membership.setFlockRef(flockRef);
            membership.setMembershipType(FlockMembership.Type.MEMBER);
            store.put(companionRef, FlockMembership.getComponentType(), membership);
        }

        private boolean membershipPresent() {
            return store.getComponent(companionRef, FlockMembership.getComponentType()) != null;
        }

        private void putLeaderMembership(Ref<EntityStore> leader, FlockMembership.Type type) {
            FlockMembership leaderMembership = new FlockMembership();
            leaderMembership.setFlockRef(flockRef);
            leaderMembership.setMembershipType(type);
            store.put(leader, FlockMembership.getComponentType(), leaderMembership);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static final class CleanupStore extends TestEntityComponentStore {
        private CleanupStore(EntityStore externalData) {
            super(externalData);
        }

        @Override
        public <T extends Component<EntityStore>> void tryRemoveComponent(
                Ref<EntityStore> reference, ComponentType<EntityStore, T> type) {
            removeComponent(reference, type);
        }
    }
    private static final class StateNameSupport extends StateSupport {
        private String stateName;

        private StateNameSupport() {
            super(null, null);
        }

        @Override
        public String getStateName() {
            return stateName;
        }
    }

    private static final class FlockCleanupScope implements AutoCloseable {
        private final Object oldFlockPlugin;
        private final Object oldNpcPlugin;
        private final Object oldGroupType;
        private final Object oldUuidType;
        private final ComponentType<EntityStore, FlockMembership> membershipType = new ComponentType<>();
        private final ComponentType<EntityStore, EntityGroup> entityGroupType = new ComponentType<>();
        private final ComponentType<EntityStore, UUIDComponent> uuidType = new ComponentType<>();
        private final ComponentType<EntityStore, StateSupport> stateSupportType = new ComponentType<>();
        private final ComponentType<EntityStore, TameworkTamedComponent> tamedType = new ComponentType<>();

        private FlockCleanupScope(Object oldFlockPlugin, Object oldNpcPlugin,
                                  Object oldGroupType, Object oldUuidType) {
            this.oldFlockPlugin = oldFlockPlugin;
            this.oldNpcPlugin = oldNpcPlugin;
            this.oldGroupType = oldGroupType;
            this.oldUuidType = oldUuidType;
        }

        private static FlockCleanupScope install() throws Exception {
            Field flockInstance = staticField(FlockPlugin.class, "instance");
            Field npcInstance = staticField(NPCPlugin.class, "instance");
            EntityModule entityModule = EntityModule.get();
            FlockCleanupScope scope = new FlockCleanupScope(flockInstance.get(null),
                    npcInstance.get(null), getField(entityModule, EntityModule.class,
                    "entityGroupComponentType"), getField(entityModule, EntityModule.class,
                    "uuidComponentType"));

            FlockPlugin flockPlugin = allocate(FlockPlugin.class);
            setField(flockPlugin, FlockPlugin.class, "flockMembershipComponentType",
                    scope.membershipType);
            flockInstance.set(null, flockPlugin);

            NPCPlugin npcPlugin = allocate(NPCPlugin.class);
            setField(npcPlugin, NPCPlugin.class, "stateSupportComponentType",
                    scope.stateSupportType);
            npcInstance.set(null, npcPlugin);

            setField(entityModule, EntityModule.class, "entityGroupComponentType",
                    scope.entityGroupType);
            setField(entityModule, EntityModule.class, "uuidComponentType", scope.uuidType);
            setField(Tamework.getInstance(), Tamework.class, "tamedComponentType", scope.tamedType);
            return scope;
        }

        @Override
        public void close() throws Exception {
            staticField(FlockPlugin.class, "instance").set(null, oldFlockPlugin);
            staticField(NPCPlugin.class, "instance").set(null, oldNpcPlugin);
            EntityModule entityModule = EntityModule.get();
            setField(entityModule, EntityModule.class, "entityGroupComponentType", oldGroupType);
            setField(entityModule, EntityModule.class, "uuidComponentType", oldUuidType);
        }
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object getField(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return unsafe().getObject(target, unsafe().objectFieldOffset(field));
    }

    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(unsafe().allocateInstance(type));
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
