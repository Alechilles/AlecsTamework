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
import com.hypixel.hytale.component.ComponentAccessor;
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
import com.hypixel.hytale.server.npc.asset.builder.InstructionContextHelper;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerBase;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.movement.Steering;
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
import org.joml.Vector3dc;
import sun.misc.Unsafe;

class FlightFormationEligibilityTest {
    @Test
    void groundFollowerMatchesSlowLeaderInsteadOfNormalizingToFullSpeed() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install();
             FlightFixture fixture = new FlightFixture(scope)) {
            GroundWalk walk = (GroundWalk) unsafe().allocateInstance(GroundWalk.class);
            walk.walkable = true;
            setField(Role.class, fixture.followerRole, "activeMotionController", walk);
            TransformComponent self = new TransformComponent();
            fixture.store.put(fixture.followerRef, TransformComponent.getComponentType(), self);
            TransformComponent leader = fixture.store.getComponent(fixture.leaderRef, TransformComponent.getComponentType());
            Vector3d heading = new Vector3d(0, 0, -1);
            BodyMotionTameworkGroundFormation motion = groundMotion();
            Steering steering = new Steering();
            BodyMotionTameworkGroundFormation.resolveGroundTarget(0, 5, 0.05,
                    leader.getPosition(), heading, 0, self.getPosition());
            motion.computeSteering(fixture.followerRef, fixture.followerRole, null, 0.05, steering, fixture.store);
            leader.getPosition().z -= 0.05;
            leader.getPosition().y += 0.5;
            BodyMotionTameworkGroundFormation.resolveGroundTarget(0, 5, 0.1,
                    leader.getPosition(), heading, 0, self.getPosition());
            assertTrue(motion.computeSteering(fixture.followerRef, fixture.followerRole,
                    null, 0.05, steering, fixture.store));
            assertEquals(1.0, steering.getTranslation().length() * walk.getMaximumSpeed(), 0.03,
                    "Ground formation must preserve the leader's slow walking speed.");
            assertEquals(0.0, steering.getTranslation().y, 1.0E-9,
                    "Climbing by the leader must not inject vertical flight steering.");
        }
    }

    @Test
    void blockedGroundFollowerLimitsProbesAndResumesWhenGroundClears() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install();
             FlightFixture fixture = new FlightFixture(scope)) {
            GroundWalk walk = (GroundWalk) unsafe().allocateInstance(GroundWalk.class);
            setField(Role.class, fixture.followerRole, "activeMotionController", walk);
            TransformComponent self = new TransformComponent();
            self.getPosition().set(20, 0, 20);
            fixture.store.put(fixture.followerRef, TransformComponent.getComponentType(), self);
            BodyMotionTameworkGroundFormation motion = groundMotion();
            Steering steering = new Steering();
            for (int tick = 0; tick < 20; tick++) {
                motion.computeSteering(fixture.followerRef, fixture.followerRole, null, 0.05, steering, fixture.store);
                assertEquals(0.0, steering.getTranslation().length(), 1.0E-9);
            }
            assertTrue(walk.probes <= 12, "Blocked followers must not probe twice every frame.");
            walk.walkable = true;
            boolean resumed = false;
            for (int tick = 0; tick < 80; tick++) {
                motion.computeSteering(fixture.followerRef, fixture.followerRole, null, 0.05, steering, fixture.store);
                resumed |= steering.getTranslation().length() > 0;
                self.getPosition().add(new Vector3d(steering.getTranslation()).mul(0.2));
            }
            assertTrue(resumed);
            assertTrue(steering.getTranslation().length() <= 1.0);
        }
    }

    @Test
    void groundLeaderDetoursAndReturnsToItsTravelHeading() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install();
             FlightFixture fixture = new FlightFixture(scope)) {
            GroundWalk walk = (GroundWalk) unsafe().allocateInstance(GroundWalk.class);
            walk.walkable = true;
            walk.blockForward = true;
            walk.blockedTravel = 0.75;
            setField(Role.class, fixture.followerRole, "activeMotionController", walk);
            TransformComponent self = new TransformComponent();
            fixture.store.put(fixture.followerRef, TransformComponent.getComponentType(), self);
            fixture.store.put(fixture.followerRef, scope.flockMembershipType, null);
            BodyMotionTameworkGroundFormation motion = groundMotion(true);
            Steering steering = new Steering();
            boolean movedSideways = false;
            double previousYaw = 0;
            for (int tick = 0; tick < 200; tick++) {
                if (tick == 80) walk.blockForward = false;
                motion.computeSteering(fixture.followerRef, fixture.followerRole, null, 0.05, steering, fixture.store);
                if (steering.hasYaw()) {
                    double change = steering.getYaw() - previousYaw;
                    assertTrue(Math.abs(Math.atan2(Math.sin(change), Math.cos(change))) <= Math.toRadians(45) * 0.05 + 0.001, "Yaw change at tick " + tick + ": " + change);
                    previousYaw = steering.getYaw();
                }
                if (tick < 80 && steering.getTranslation().length() > 0) {
                    double direction = Math.atan2(steering.getTranslation().x, -steering.getTranslation().z);
                    assertEquals(Math.toRadians(45), direction, Math.toRadians(2.1),
                            "Obstacle recovery must finish turning before walking across the corner.");
                }
                if (tick > 40 && tick < 80) {
                    assertTrue(steering.getTranslation().x > 0.1,
                            "Keep making progress along the clear side instead of reversing the detour.");
                }
                movedSideways |= Math.abs(steering.getTranslation().x) > 0.1;
                self.getPosition().add(new Vector3d(steering.getTranslation()).mul(4 * 0.05));
            }
            assertTrue(movedSideways, "A blocked leader must take an available local detour.");
            assertEquals(0, steering.getTranslation().x, 1e-6);
            assertTrue(steering.getTranslation().z < -0.1, "Clear ground must restore the original travel direction.");
        }
    }

    @Test
    void groundLeaderTakesShallowDetourBeforeReachingNearbyObstacle() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install();
             FlightFixture fixture = new FlightFixture(scope)) {
            GroundWalk walk = (GroundWalk) unsafe().allocateInstance(GroundWalk.class);
            walk.walkable = true;
            walk.blockForward = true;
            walk.blockedTravel = 2.0;
            walk.shallowClearance = true;
            setField(Role.class, fixture.followerRole, "activeMotionController", walk);
            TransformComponent self = new TransformComponent();
            fixture.store.put(fixture.followerRef, TransformComponent.getComponentType(), self);
            BodyMotionTameworkGroundFormation motion = groundMotion(true);
            Steering steering = new Steering();
            boolean avoidedEarly = false;
            for (int tick = 0; tick < 20; tick++) {
                motion.computeSteering(fixture.followerRef, fixture.followerRole, null, 0.05, steering, fixture.store);
                if (steering.getTranslation().length() > 0) {
                    double angle = Math.abs(Math.atan2(steering.getTranslation().x, -steering.getTranslation().z));
                    assertTrue(angle < Math.toRadians(30), "Use the available shallow route before a sharp recovery turn.");
                    avoidedEarly |= angle > Math.toRadians(10);
                }
                self.getPosition().add(new Vector3d(steering.getTranslation()).mul(0.2));
            }
            assertTrue(avoidedEarly, "Detect the obstruction two blocks ahead before walking up to it.");
            assertTrue(walk.probes <= 12, "Earlier avoidance retains the bounded probe cadence.");
        }
    }

    @Test
    void groundLeaderRecoversWhenClearProbesStillProduceNoProgress() throws Exception {
        try (HytaleModuleScope ignored = HytaleModuleScope.install();
             FlockFixtureScope scope = FlockFixtureScope.install();
             FlightFixture fixture = new FlightFixture(scope)) {
            GroundWalk walk = (GroundWalk) unsafe().allocateInstance(GroundWalk.class);
            walk.walkable = true;
            setField(Role.class, fixture.followerRole, "activeMotionController", walk);
            fixture.store.put(fixture.followerRef, TransformComponent.getComponentType(), new TransformComponent());
            BodyMotionTameworkGroundFormation motion = groundMotion(true);
            Steering steering = new Steering();
            boolean recovered = false;
            for (int tick = 0; tick < 60; tick++) {
                motion.computeSteering(fixture.followerRef, fixture.followerRole, null, 0.05, steering, fixture.store);
                recovered |= Math.abs(steering.getTranslation().x) > 0.1;
            }
            assertTrue(recovered, "Physical obstruction must trigger a detour even if terrain probes are clear.");
        }
    }

    @Test
    void homeRangeTurnsDistantJourneysInwardWithoutRestrictingNearbyTravel() {
        Vector3d home = new Vector3d(1000, 50, 1000);
        Vector3d heading = new Vector3d(1, 0, 0);
        BodyMotionTameworkGroundFormation.biasHeadingTowardHome(new Vector3d(1100, 300, 1000), home, 200, heading);
        assertEquals(new Vector3d(1, 0, 0), heading);
        BodyMotionTameworkGroundFormation.biasHeadingTowardHome(new Vector3d(1200, 300, 1000), home, 200, heading);
        assertEquals(new Vector3d(-1, 0, 0), heading);
        assertEquals(new Vector3d(1000, 50, 1000), home, "Choosing a heading must never move the home anchor.");
        heading.set(1, 0, 0);
        BodyMotionTameworkGroundFormation.biasHeadingTowardHome(new Vector3d(1400, 50, 1000), home, 0, heading);
        assertEquals(new Vector3d(1, 0, 0), heading, "Existing users without HomeRange keep unrestricted travel.");
    }

    @Test
    void groundFollowerToleratesSlotErrorAndLimitsLateralCorrection() {
        Vector3d output = new Vector3d();
        BodyMotionTameworkGroundFormation.resolveGroundTranslation(new Vector3d(), new Vector3d(1, 0, 0),
                new Vector3d(0, 0, 1), 4, 0.25, 0.35, 1.5, 0.05, output);
        assertEquals(0, output.x, 1e-9);
        assertEquals(0.25, output.z, 1e-9);
        BodyMotionTameworkGroundFormation.resolveGroundTranslation(new Vector3d(), new Vector3d(10, 0, 0),
                new Vector3d(0, 0, 1), 4, 0.25, 0.35, 1.5, 0.05, output);
        assertTrue(output.x > 0);
        assertTrue(Math.atan2(output.x, output.z) <= Math.toRadians(20) + 1e-9);
    }

    private static BodyMotionTameworkGroundFormation groundMotion() throws Exception {
        return groundMotion(false);
    }

    private static BodyMotionTameworkGroundFormation groundMotion(boolean lead) throws Exception {
        BuilderBodyMotionTameworkGroundFormation builder = new BuilderBodyMotionTameworkGroundFormation();
        List<String> errors = new ArrayList<>();
        JsonObject config = new JsonObject();
        config.addProperty("Lead", lead);
        builder.readConfig(null, config, new BuilderManager(), formationParameters(),
                new BuilderValidationHelper("ground-formation-test", null, null, null,
                        new InstructionContextHelper(InstructionType.Default),
                        new ExtraInfo(), null, errors));
        assertTrue(errors.isEmpty(), () -> "Ground formation config errors: " + errors);
        BuilderSupport support = new BuilderSupport(new BuilderManager(), null,
                new ExecutionContext(), builder, new RoleStats());
        support.setScope(new StdScope(null));
        return builder.build(support);
    }

    private static final class GroundWalk extends MotionControllerWalk {
        private boolean walkable;
        private boolean blockForward;
        private boolean shallowClearance;
        private double blockedTravel;
        private int probes;

        private GroundWalk() { super(null, null); }

        @Override
        public double getMaximumSpeed() { return 4.0; }

        @Override
        public double probeMove(Ref<EntityStore> ref, Vector3dc position, Vector3dc direction,
                                ProbeMoveData data, ComponentAccessor<EntityStore> accessor) {
            probes++;
            return walkable ? (!blockForward || Math.abs(direction.x()) / direction.length() > (shallowClearance ? 0.2 : 0.6)
                    ? direction.length() : Math.min(blockedTravel, direction.length())) : 0.0;
        }
    }

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
