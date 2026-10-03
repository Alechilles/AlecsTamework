package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** A terrain-clipped particle beam that follows native head motion and only damages players. */
public final class ActionTameworkBeam extends TameworkActionBase {
    private static final float PARTICLE_LIFETIME = 0.12f;
    private final double range;
    private final float damage;
    private final String particleSystem;
    private final double particleNativeLength;
    private final double originHeight;
    private final double originForward;
    private final double beamRadius;
    private final int targetSlot;
    private final Cadence particles = new Cadence(0.1);
    private final Cadence damagePulses;
    private boolean failed;

    public ActionTameworkBeam(@Nonnull BuilderActionTameworkBeam builder, @Nonnull BuilderSupport support) {
        super(builder);
        range = builder.getRange(support);
        damage = (float) builder.getDamage(support);
        particleSystem = builder.getParticleSystem(support);
        particleNativeLength = builder.getParticleNativeLength(support);
        originHeight = builder.getOriginHeight(support);
        originForward = builder.getOriginForward(support);
        beamRadius = builder.getBeamRadius(support);
        targetSlot = builder.getTargetSlot(support);
        damagePulses = new Cadence(Math.max(0.25, builder.getDamageInterval(support)));
    }

    @Override
    public void activate(@Nullable Role role, @Nullable InfoProvider infoProvider) {
        super.activate(role, infoProvider);
        particles.reset();
        damagePulses.reset();
        failed = false;
    }

    @Override
    public boolean execute(@Nullable Ref<EntityStore> npcRef, @Nullable Role role,
                           @Nullable InfoProvider infoProvider, double dt, @Nullable Store<EntityStore> store) {
        if (failed || npcRef == null || !npcRef.isValid() || store == null) return false;
        super.execute(npcRef, role, infoProvider, dt, store);
        if (isDead(npcRef, store)) return false;
        MarkedEntitySupport marked = NpcSupportAccess.markedEntity(role, npcRef, store);
        Ref<EntityStore> target = marked == null ? null : marked.getMarkedEntityRef(targetSlot);
        if (target == null || !target.isValid() || isDead(target, store)) return false;

        boolean emit = particles.advance(dt);
        boolean hurt = damage > 0 && damagePulses.advance(dt);
        if (!emit && !hurt) return true;
        try {
            if (ParticleSystem.getAssetMap().getAsset(particleSystem) == null) {
                throw new IllegalStateException("Beam particle system is not loaded: " + particleSystem);
            }
            TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
            HeadRotation head = store.getComponent(npcRef, HeadRotation.getComponentType());
            if (transform == null || head == null) return false;
            World world = store.getExternalData().getWorld();
            var rotation = head.getRotation();
            // Both direction and horizontal launch offset use actual native head rotation.
            Vector3d direction = Transform.getDirection(rotation.pitch(), rotation.yaw()).normalize();
            Vector3d origin = new Vector3d(transform.getPosition()).add(0, originHeight, 0)
                    .add(Transform.getDirection(0, rotation.yaw()).mul(originForward));
            Vector3d hit = TargetUtil.getTargetLocation(world, ActionTameworkBeam::blocksBeam,
                    origin.x, origin.y, origin.z, direction.x, direction.y, direction.z, range);
            double length = hit == null ? range : Math.min(range, origin.distance(hit));
            if (length <= 0) return true;
            if (emit) {
                Vector3d midpoint = new Vector3d(direction).mul(length * 0.5).add(origin);
                ParticleUtil.spawnParticleEffect(particleSystem, midpoint, rotation.yaw(), rotation.pitch(), 0,
                        (float) (length / particleNativeLength), PARTICLE_LIFETIME, store);
            }
            if (hurt) damagePlayers(npcRef, store, origin, direction, length);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            // A broken effect stops this activation, rather than silently damaging without its beam.
            failed = true;
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin != null) plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                    "TameworkBeam stopped: particle=%s", particleSystem);
            return false;
        }
    }

    private static boolean blocksBeam(int blockId) {
        if (blockId == 0) return false;
        BlockType block = BlockType.getAssetMap().getAsset(blockId);
        // Unknown blocks fail closed; Empty material includes non-colliding decoration.
        return block == null || block.getMaterial() == BlockMaterial.Solid;
    }

    private static boolean isDead(Ref<EntityStore> ref, Store<EntityStore> store) {
        return store.getArchetype(ref).contains(DeathComponent.getComponentType());
    }

    private void damagePlayers(Ref<EntityStore> npcRef, Store<EntityStore> store,
                               Vector3d origin, Vector3d direction, double length) {
        DamageCause cause = DamageCause.getAssetMap().getAsset("Fire");
        if (cause == null) cause = DamageCause.PHYSICAL;
        if (cause == null) throw new IllegalStateException("No Fire or Physical damage cause is loaded");
        SpatialResource<Ref<EntityStore>, EntityStore> spatial =
                store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        if (spatial == null) return;
        // Spatial entries use feet positions; include player bounds near the ray endpoint.
        List<Ref<EntityStore>> nearby = SpatialResource.getThreadLocalReferenceList();
        spatial.getSpatialStructure().collect(origin, length + beamRadius + 3.0, nearby);
        // Damage handlers may reuse the spatial scratch list; this copy stays in this callback.
        List<Ref<EntityStore>> candidates = new ArrayList<>(nearby);
        for (int i = 0; i < candidates.size(); i++) {
            Ref<EntityStore> playerRef = candidates.get(i);
            if (playerRef == null || !playerRef.isValid() || isDead(playerRef, store)
                    || store.getComponent(playerRef, Player.getComponentType()) == null) continue;
            TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
            BoundingBox bounds = store.getComponent(playerRef, BoundingBox.getComponentType());
            if (transform == null || bounds == null) continue;
            Vector3d min = new Vector3d(bounds.getBoundingBox().getMin()).add(transform.getPosition());
            Vector3d max = new Vector3d(bounds.getBoundingBox().getMax()).add(transform.getPosition());
            if (intersectsSegment(origin, direction, length, min, max, beamRadius)) {
                DamageSystems.executeDamage(playerRef, store,
                        new Damage(new Damage.EntitySource(npcRef), cause, damage));
            }
        }
    }

    /** Slab intersection against the player's world bounds, capped at the terrain endpoint. */
    static boolean intersectsSegment(Vector3dc origin, Vector3dc direction, double length,
                                     Vector3dc min, Vector3dc max, double radius) {
        double near = 0;
        double far = length;
        for (int axis = 0; axis < 3; axis++) {
            double start = origin.get(axis);
            double delta = direction.get(axis);
            double low = min.get(axis) - radius;
            double high = max.get(axis) + radius;
            if (Math.abs(delta) < 1e-12) {
                if (start < low || start > high) return false;
                continue;
            }
            double first = (low - start) / delta;
            double last = (high - start) / delta;
            if (first > last) {
                double swap = first;
                first = last;
                last = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, last);
            if (near > far) return false;
        }
        return near <= far;
    }

    /** Each tick emits at most one pulse, dropping missed intervals after a stalled tick. */
    static final class Cadence {
        private final double interval;
        private double remaining;

        Cadence(double interval) {
            this.interval = interval;
        }

        void reset() {
            remaining = 0;
        }

        boolean advance(double dt) {
            remaining -= Math.max(0, dt);
            if (remaining > 1e-9) return false;
            remaining = interval;
            return true;
        }
    }
}
