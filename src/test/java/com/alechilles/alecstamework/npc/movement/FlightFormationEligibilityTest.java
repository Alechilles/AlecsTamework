package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.BuilderValidationHelper;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerBase;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.RoleStats;
import com.hypixel.hytale.server.npc.util.PositionProbeAir;
import com.hypixel.hytale.server.npc.util.PositionProbeBase;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;
import sun.misc.Unsafe;

class FlightFormationEligibilityTest {
    @Test
    void flyingFollowerResolvesItsNativeFlockLeaderAndTracksLeaderReplacement() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install()) {
            try (FlightFixture fixture = new FlightFixture(scope)) {

                assertEquals(fixture.leaderRef, BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));

                fixture.group.setLeaderRef(fixture.replacementLeaderRef);

                assertEquals(fixture.replacementLeaderRef, BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));
            }
        }
    }

    @Test
    void missingFlockSelfLeaderGroundedAndMountedFollowersAreIneligible() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install()) {
            try (FlightFixture fixture = new FlightFixture(scope)) {

                fixture.store.put(fixture.followerRef, scope.flockMembershipType, null);
                assertNull(BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));

                fixture.store.put(fixture.followerRef, scope.flockMembershipType, fixture.membership());
                fixture.group.setLeaderRef(fixture.followerRef);
                assertNull(BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));

                fixture.group.setLeaderRef(fixture.leaderRef);
                fixture.setFollowerGrounded(true);
                assertNull(BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));

                fixture.setFollowerGrounded(false);
                fixture.setLeaderGrounded(true);
                assertNull(BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));

                fixture.setLeaderGrounded(false);
                fixture.store.put(fixture.followerRef, scope.nativeMountType, new NPCMountComponent());
                assertNull(BodyMotionTameworkFlightFormation.findFlyingLeader(
                        fixture.followerRef, fixture.followerRole, fixture.store));
            }
        }
    }

    @Test
    void formationFlyUsesHorizontalSpeedForMatchingAndCatchupWhileRetainingFlyIdentity() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install()) {
            BuilderMotionControllerTameworkFormationFly enabledBuilder =
                    formationFlyBuilder();
            assertTrue(enabledBuilder.isFormationEnabled(formationSupport(enabledBuilder, "Chevron")));
            assertFalse(enabledBuilder.isFormationEnabled(formationSupport(enabledBuilder, "None")));

            MotionControllerTameworkFormationFly controller = formationFlyController(4.0, 10.0);
            assertEquals(MotionControllerFly.TYPE, controller.getType());

            double speedScale = controller.getSteeringSpeedScale();
            Vector3d matchedTranslation = FlightFormationSteering.resolveTranslation(
                    new Vector3d(), new Vector3d(), new Vector3d(2.0, 0.0, 0.0),
                    speedScale, 0.8, 0.6, 0.05, new Vector3d());
            assertEquals(2.0, matchedTranslation.length() * speedScale, 1.0E-9);

            Vector3d catchupTranslation = FlightFormationSteering.resolveTranslation(
                    new Vector3d(), new Vector3d(100.0, 0.0, 0.0), new Vector3d(2.0, 0.0, 0.0),
                    speedScale, 0.8, 0.6, 0.05, new Vector3d());
            double catchupSpeed = catchupTranslation.length() * speedScale;
            assertTrue(catchupSpeed > 2.0);
            assertTrue(catchupSpeed <= 4.0);
        }
    }

    private static final class FlightFixture implements AutoCloseable {
        private final FlockFixtureScope scope;
        private final TestEntityComponentStore store;
        private final Ref<EntityStore> followerRef;
        private final Ref<EntityStore> leaderRef;
        private final Ref<EntityStore> replacementLeaderRef;
        private final Ref<EntityStore> flockRef;
        private final EntityGroup group = new EntityGroup();
        private final Role followerRole = formationFlyingRole(false);
        private final Role leaderRole = flyingRole(false);
        private final Role replacementLeaderRole = flyingRole(false);

        private FlightFixture(@Nonnull FlockFixtureScope scope) throws Exception {
            this.scope = scope;
            store = new TestEntityComponentStore((EntityStore) unsafe().allocateInstance(EntityStore.class));
            followerRef = store.createReference();
            leaderRef = store.createReference();
            replacementLeaderRef = store.createReference();
            flockRef = store.createReference();

            group.setLeaderRef(leaderRef);
            group.add(followerRef);
            group.add(leaderRef);
            group.add(replacementLeaderRef);
            store.put(flockRef, scope.entityGroupType, group);
            store.put(followerRef, scope.flockMembershipType, membership());
            putFlyingNpc(leaderRef, leaderRole);
            putFlyingNpc(replacementLeaderRef, replacementLeaderRole);
        }

        private FlockMembership membership() {
            FlockMembership membership = new FlockMembership();
            membership.setFlockRef(flockRef);
            membership.setMembershipType(FlockMembership.Type.MEMBER);
            return membership;
        }

        private void putFlyingNpc(@Nonnull Ref<EntityStore> ref, @Nonnull Role role) {
            NPCEntity npc = new NPCEntity();
            npc.setRole(role);
            store.put(ref, NPCEntity.getComponentType(), npc);
            store.put(ref, TransformComponent.getComponentType(), new TransformComponent());
        }

        private void setFollowerGrounded(boolean grounded) throws Exception {
            setGrounded(followerRole, grounded);
        }

        private void setLeaderGrounded(boolean grounded) throws Exception {
            setGrounded(leaderRole, grounded);
        }

        private void setGrounded(@Nonnull Role role, boolean grounded) throws Exception {
            PositionProbeAir probe = (PositionProbeAir) unsafe().allocateInstance(PositionProbeAir.class);
            setField(PositionProbeBase.class, probe, "onGround", grounded);
            MotionControllerFly fly = (MotionControllerFly) getField(Role.class, role, "activeMotionController");
            setField(MotionControllerFly.class, fly, "moveProbe", probe);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static final class FlockFixtureScope implements AutoCloseable {
        private final Object oldFlockPlugin;
        private final Object oldMountPlugin;
        private final Object oldNpcPlugin;
        private final Object oldEntityGroupType;
        private final ComponentType<EntityStore, FlockMembership> flockMembershipType = new ComponentType<>();
        private final ComponentType<EntityStore, EntityGroup> entityGroupType = new ComponentType<>();
        private final ComponentType<EntityStore, NPCMountComponent> nativeMountType = new ComponentType<>();

        private FlockFixtureScope(Object oldFlockPlugin, Object oldMountPlugin, Object oldNpcPlugin,
                                  Object oldEntityGroupType) {
            this.oldFlockPlugin = oldFlockPlugin;
            this.oldMountPlugin = oldMountPlugin;
            this.oldNpcPlugin = oldNpcPlugin;
            this.oldEntityGroupType = oldEntityGroupType;
        }

        @Nonnull
        private static FlockFixtureScope install() throws Exception {
            Field flockInstance = staticField(FlockPlugin.class, "instance");
            Field mountInstance = staticField(MountPlugin.class, "instance");
            Field npcInstance = staticField(NPCPlugin.class, "instance");
            EntityModule entityModule = EntityModule.get();
            Object previousGroupType = getField(EntityModule.class, entityModule, "entityGroupComponentType");
            FlockFixtureScope scope = new FlockFixtureScope(
                    flockInstance.get(null), mountInstance.get(null), npcInstance.get(null), previousGroupType);

            npcInstance.set(null, unsafe().allocateInstance(NPCPlugin.class));
            FlockPlugin flockPlugin = (FlockPlugin) unsafe().allocateInstance(FlockPlugin.class);
            setField(FlockPlugin.class, flockPlugin, "flockMembershipComponentType", scope.flockMembershipType);
            flockInstance.set(null, flockPlugin);
            MountPlugin mountPlugin = (MountPlugin) unsafe().allocateInstance(MountPlugin.class);
            setField(MountPlugin.class, mountPlugin, "mountComponentType", scope.nativeMountType);
            mountInstance.set(null, mountPlugin);
            setField(EntityModule.class, entityModule, "entityGroupComponentType", scope.entityGroupType);
            return scope;
        }

        @Override
        public void close() throws Exception {
            staticField(FlockPlugin.class, "instance").set(null, oldFlockPlugin);
            staticField(MountPlugin.class, "instance").set(null, oldMountPlugin);
            staticField(NPCPlugin.class, "instance").set(null, oldNpcPlugin);
            setField(EntityModule.class, EntityModule.get(), "entityGroupComponentType", oldEntityGroupType);
        }
    }

    @Nonnull
    private static Role flyingRole(boolean grounded) throws Exception {
        Role role = (Role) unsafe().allocateInstance(Role.class);
        MotionControllerFly fly = (MotionControllerFly) unsafe().allocateInstance(MotionControllerFly.class);
        configureFlyingRole(role, fly, grounded);
        return role;
    }

    @Nonnull
    private static Role formationFlyingRole(boolean grounded) throws Exception {
        Role role = (Role) unsafe().allocateInstance(Role.class);
        MotionControllerTameworkFormationFly fly = (MotionControllerTameworkFormationFly)
                unsafe().allocateInstance(MotionControllerTameworkFormationFly.class);
        configureFlyingRole(role, fly, grounded);
        return role;
    }

    @Nonnull
    private static MotionControllerTameworkFormationFly formationFlyController(double horizontalSpeed,
                                                                                double verticalSpeed)
            throws Exception {
        MotionControllerTameworkFormationFly controller = (MotionControllerTameworkFormationFly)
                unsafe().allocateInstance(MotionControllerTameworkFormationFly.class);
        setField(MotionControllerBase.class, controller, "maxHorizontalSpeed", horizontalSpeed);
        setField(MotionControllerBase.class, controller, "pitch", 0.0f);
        setField(MotionControllerBase.class, controller, "effectHorizontalSpeedMultiplier", 1.0);
        setField(MotionControllerFly.class, controller, "maxClimbSpeed", verticalSpeed);
        setField(MotionControllerFly.class, controller, "maxSinkSpeed", verticalSpeed);
        return controller;
    }

    @Nonnull
    private static BuilderSupport formationSupport(
            @Nonnull BuilderMotionControllerTameworkFormationFly builder,
            @Nonnull String formation) {
        ExecutionContext context = new ExecutionContext();
        BuilderSupport support = new BuilderSupport(
                new BuilderManager(), null, context, builder, new RoleStats());
        StdScope scope = new StdScope(null);
        scope.addVar("FlightFormation", formation);
        support.setScope(scope);
        return support;
    }

    @Nonnull
    private static JsonObject formationFlyConfig() {
        JsonObject data = new JsonObject();
        data.addProperty("Type", BuilderMotionControllerTameworkFormationFly.BUILDER_ID);
        data.addProperty("MinAirSpeed", 2.0);
        JsonObject compute = new JsonObject();
        compute.addProperty("Compute", "FlightFormation");
        data.add("Formation", compute);
        return data;
    }

    @Nonnull
    private static BuilderMotionControllerTameworkFormationFly formationFlyBuilder() throws Exception {
        BuilderMotionControllerTameworkFormationFly builder = new BuilderMotionControllerTameworkFormationFly();
        List<String> readErrors = new ArrayList<>();
        builder.readConfig(
                null,
                formationFlyConfig(),
                new BuilderManager(),
                formationParameters(),
                new BuilderValidationHelper("formation-test", null, null, null, null, new ExtraInfo(), null, readErrors)
        );
        assertTrue(readErrors.isEmpty(), () -> "Formation controller config errors: " + readErrors);
        return builder;
    }

    @Nonnull
    private static BuilderParameters formationParameters() throws Exception {
        StdScope parameterScope = new StdScope(null);
        parameterScope.addVar("FlightFormation", "");
        Constructor<BuilderParameters> constructor = BuilderParameters.class.getDeclaredConstructor(
                StdScope.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(parameterScope, "formation-test", null);
    }

    private static void configureFlyingRole(@Nonnull Role role,
                                            @Nonnull MotionControllerFly fly,
                                            boolean grounded) throws Exception {
        PositionProbeAir probe = (PositionProbeAir) unsafe().allocateInstance(PositionProbeAir.class);
        setField(PositionProbeBase.class, probe, "onGround", grounded);
        setField(MotionControllerFly.class, fly, "moveProbe", probe);
        setField(Role.class, role, "activeMotionController", fly);
    }

    private static Object getField(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
