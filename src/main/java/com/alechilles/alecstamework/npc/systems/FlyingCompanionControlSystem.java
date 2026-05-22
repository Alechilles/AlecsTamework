package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkFlyingCompanionComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.NavState;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides Java-side settle assistance for flying companions and similar flyers that need help settling on the ground.
 */
public final class FlyingCompanionControlSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 50L;
    private static final int SAFE_VERTICAL_SCAN_STEPS = 8;
    private static final int SAFE_HORIZONTAL_SCAN_RADIUS = 2;
    private static final double SAFE_STAND_HEIGHT_OFFSET = 0.05;
    private static final double MIN_GROUNDED_HEIGHT_TOLERANCE = 0.35;
    private static final double LANDING_HORIZONTAL_GAIN = 2.0;
    private static final double LANDING_HORIZONTAL_MAX_SPEED = 0.18;
    private static final double LANDING_VERTICAL_MIN_SPEED = 0.035;
    private static final double LANDING_VERTICAL_EASE_DISTANCE = 1.0;

    private long nextSweepAtMs;
    private final Map<Ref<EntityStore>, Vector3d> landingTargets = new HashMap<>();
    private final Map<Ref<EntityStore>, String> debugSignatures = new HashMap<>();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkFlyingCompanionComponent> flyingType =
                TameworkFlyingCompanionComponent.getComponentType();
        if (npcType == null || transformType == null || flyingType == null) {
            return;
        }

        List<Ref<EntityStore>> refs = new ArrayList<>();
        store.forEachChunk(
                Query.and(npcType, transformType, flyingType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        if (ref == null || !ref.isValid()) {
                            continue;
                        }
                        refs.add(ref);
                    }
                }
        );

        for (Ref<EntityStore> ref : refs) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            tickCompanion(ref, store, npcType, transformType, flyingType);
        }
    }

    private void tickCompanion(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                               @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                               @Nonnull ComponentType<EntityStore, TameworkFlyingCompanionComponent> flyingType) {
        NPCEntity npc = safeGetComponent(store, ref, npcType);
        TransformComponent transform = safeGetComponent(store, ref, transformType);
        TameworkFlyingCompanionComponent component = safeGetComponent(store, ref, flyingType);
        if (npc == null || transform == null || component == null) {
            return;
        }

        if (component.isSettledMode()) {
            landingTargets.remove(ref);
            return;
        }

        if (!component.isHoldMode()) {
            landingTargets.remove(ref);
            debugSignatures.remove(ref);
            if (!TameworkFlyingCompanionComponent.PHASE_FOLLOWING.equals(component.getPhase())
                    || component.getStableTickCount() != 0
                    || component.getNextCommandAtMs() != 0L) {
                component.enterFollowingPhase();
            }
            return;
        }

        Vector3d currentPosition = transform.getPosition();
        double currentY = currentPosition.y;
        String currentState = resolveCurrentStateSpec(npc);
        Vector3d standPosition = resolveLandingTarget(ref, store, currentPosition);
        boolean groundedByController = isGroundedByController(npc.getRole());
        logDebugSignature(ref, store, npc, component, currentState, currentY, standPosition, groundedByController);
        double groundedHeightTolerance = Math.max(
                MIN_GROUNDED_HEIGHT_TOLERANCE,
                component.getVerticalMovementEpsilon() * 4.0
        );

        if (matchesState(currentState, component.getGroundedState())) {
            if (!component.isGroundedPhase()) {
                component.enterGroundedPhase(currentY);
            }
            if (!component.isSettledMode()) {
                component.setMode(TameworkFlyingCompanionComponent.MODE_SETTLED);
            }
            landingTargets.remove(ref);
            component.setLastObservedY(currentY);
            return;
        }

        if (!TameworkFlyingCompanionComponent.PHASE_LANDING.equals(component.getPhase())) {
            component.enterLandingPhase(currentY);
        }
        component.setLastObservedY(currentY);

        if (standPosition != null && Math.abs(currentY - standPosition.y) <= groundedHeightTolerance) {
            if (groundedByController) {
                if (!component.isGroundedPhase()) {
                    logDebugEvent(ref, store, npc, "finalizeGroundedWithoutPrepare targetY=" + formatDouble(standPosition.y));
                    component.enterGroundedPhase(currentY);
                }
                if (!component.isSettledMode()) {
                    component.setMode(TameworkFlyingCompanionComponent.MODE_SETTLED);
                }
                if (!matchesState(currentState, component.getGroundedState())) {
                    logDebugEvent(ref, store, npc, "reapplyGroundedState state=" + safeValue(component.getGroundedState()));
                    applyState(npc.getRole(), ref, store, component.getGroundedState());
                }
                landingTargets.remove(ref);
                component.setLastObservedY(currentY);
                return;
            }
            if (component.isGroundedPhase()) {
                if (!matchesState(currentState, component.getGroundedState())) {
                    logDebugEvent(ref, store, npc, "reapplyGroundedState state=" + safeValue(component.getGroundedState()));
                    applyState(npc.getRole(), ref, store, component.getGroundedState());
                }
                landingTargets.remove(ref);
                component.setLastObservedY(currentY);
                return;
            }
            prepareGroundedHandoff(ref, store, npc, standPosition);
            if (isGroundedByController(npc.getRole())) {
                finalizeGroundedHandoff(ref, store, npc, component, standPosition);
            }
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs < component.getNextCommandAtMs()) {
            return;
        }

        if (standPosition != null) {
            applySmoothLandingMotion(ref, store, npc, component, currentPosition, standPosition);
        }
        component.setNextCommandAtMs(nowMs + component.getReissueDelayMs());
        if (!matchesState(currentState, component.getLandingState())) {
            applyState(npc.getRole(), ref, store, component.getLandingState());
        }
    }

    private void applyState(Role role,
                            Ref<EntityStore> npcRef,
                            Store<EntityStore> store,
                            String stateSpec) {
        if (role == null || role.getStateSupport() == null || stateSpec == null || stateSpec.isBlank()) {
            return;
        }
        String state = stateSpec;
        String subState = null;
        if (state.contains(".")) {
            String[] parts = state.split("\\.", 2);
            state = parts[0];
            subState = parts[1];
        }
        StateSupport support = role.getStateSupport();
        if (support.getStateHelper() != null) {
            int stateIndex = support.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                return;
            }
            String resolvedSubState = subState;
            if (resolvedSubState == null || resolvedSubState.isBlank()) {
                String defaultSubState = support.getStateHelper().getDefaultSubState();
                resolvedSubState = defaultSubState == null ? "" : defaultSubState;
            }
            support.setState(npcRef, state, resolvedSubState, store);
            return;
        }
        support.setState(npcRef, state, subState == null ? "" : subState, store);
    }

    private void prepareGroundedHandoff(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull NPCEntity npc,
                                        @Nonnull Vector3d standPosition) {
        logDebugEvent(ref, store, npc, "prepareGroundedHandoff targetY=" + formatDouble(standPosition.y));
        Role role = npc.getRole();
        if (role != null) {
            role.setActiveMotionController(ref, npc, "Walk", store);
        }
        npc.moveTo(ref, standPosition.x, standPosition.y, standPosition.z, store);
    }

    private void finalizeGroundedHandoff(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull NPCEntity npc,
                                         @Nonnull TameworkFlyingCompanionComponent component,
                                         @Nonnull Vector3d standPosition) {
        logDebugEvent(ref, store, npc, "finalizeGroundedHandoff targetY=" + formatDouble(standPosition.y));
        Role role = npc.getRole();
        landingTargets.remove(ref);
        component.enterGroundedPhase(standPosition.y);
        component.setMode(TameworkFlyingCompanionComponent.MODE_SETTLED);
        applyState(role, ref, store, component.getGroundedState());
    }

    private void applySmoothLandingMotion(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull NPCEntity npc,
                                          @Nonnull TameworkFlyingCompanionComponent component,
                                          @Nonnull Vector3d currentPosition,
                                          @Nonnull Vector3d standPosition) {
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        logDebugEvent(ref, store, npc, "applySmoothLandingMotion targetY=" + formatDouble(standPosition.y));
        MotionController controller = role.getActiveMotionController();
        if (controller != null) {
            controller.setNavState(NavState.AT_GOAL, 0.0, 0.0);
        }

        double horizontalDx = standPosition.x - currentPosition.x;
        double horizontalDz = standPosition.z - currentPosition.z;
        double horizontalDistance = Math.sqrt(horizontalDx * horizontalDx + horizontalDz * horizontalDz);
        double horizontalSpeed = Math.min(LANDING_HORIZONTAL_MAX_SPEED, horizontalDistance * LANDING_HORIZONTAL_GAIN);
        double velocityX = 0.0;
        double velocityZ = 0.0;
        if (horizontalDistance > 1.0e-4 && horizontalSpeed > 0.0) {
            velocityX = horizontalDx / horizontalDistance * horizontalSpeed;
            velocityZ = horizontalDz / horizontalDistance * horizontalSpeed;
        }

        double verticalDistance = Math.max(0.0, currentPosition.y - standPosition.y);
        double configuredVerticalSpeed = Math.max(LANDING_VERTICAL_MIN_SPEED, component.getDescendStep());
        double easedVerticalSpeed = Math.min(
                configuredVerticalSpeed,
                Math.max(LANDING_VERTICAL_MIN_SPEED, verticalDistance / LANDING_VERTICAL_EASE_DISTANCE * configuredVerticalSpeed)
        );
        double velocityY = -easedVerticalSpeed;

        role.setVelocity(new Vector3d(velocityX, velocityY, velocityZ), (VelocityConfig) null, true);
    }

    @Nullable
    private Vector3d resolveLandingTarget(@Nonnull Ref<EntityStore> ref,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull Vector3d currentPosition) {
        Vector3d existing = landingTargets.get(ref);
        if (existing != null) {
            return existing;
        }
        Vector3d resolved = resolveNearestSafeStandPosition(store, currentPosition);
        if (resolved != null) {
            landingTargets.put(ref, resolved);
        }
        return resolved;
    }

    private String resolveCurrentStateSpec(NPCEntity npc) {
        if (npc == null || npc.getRole() == null || npc.getRole().getStateSupport() == null) {
            return null;
        }
        StateSupport support = npc.getRole().getStateSupport();
        String stateName = support.getStateName();
        if (stateName == null || stateName.isBlank()) {
            return null;
        }
        if (support.getStateHelper() == null) {
            return stateName;
        }
        int currentState = support.getStateIndex();
        int currentSubState = support.getSubStateIndex();
        String subStateName = support.getStateHelper().getSubStateName(currentState, currentSubState);
        if (subStateName == null || subStateName.isBlank() || "Default".equalsIgnoreCase(subStateName)) {
            return stateName;
        }
        return stateName + "." + subStateName;
    }

    private boolean matchesState(String currentState, String expectedState) {
        if (currentState == null || expectedState == null || expectedState.isBlank()) {
            return false;
        }
        if (currentState.equalsIgnoreCase(expectedState)) {
            return true;
        }
        int currentDot = currentState.indexOf('.');
        if (currentDot >= 0 && expectedState.equalsIgnoreCase(currentState.substring(0, currentDot))) {
            return true;
        }
        int expectedDot = expectedState.indexOf('.');
        if (expectedDot >= 0 && currentState.equalsIgnoreCase(expectedState.substring(0, expectedDot))) {
            return true;
        }
        return false;
    }

    private boolean isGroundedByController(@Nullable Role role) {
        if (role == null) {
            return false;
        }
        MotionController controller = role.getActiveMotionController();
        if (controller == null) {
            return false;
        }
        return controller.onGround();
    }

    private void logDebugSignature(@Nonnull Ref<EntityStore> ref,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull NPCEntity npc,
                                   @Nonnull TameworkFlyingCompanionComponent component,
                                   @Nullable String currentState,
                                   double currentY,
                                   @Nullable Vector3d standPosition,
                                   boolean groundedByController) {
        if (!shouldLogDebug(npc)) {
            return;
        }
        String controllerType = resolveControllerType(npc.getRole());
        String signature = String.format(
                "mode=%s phase=%s state=%s controller=%s grounded=%s y=%s standY=%s",
                safeValue(component.getMode()),
                safeValue(component.getPhase()),
                safeValue(currentState),
                safeValue(controllerType),
                groundedByController,
                formatDouble(currentY),
                standPosition == null ? "<none>" : formatDouble(standPosition.y)
        );
        String previous = debugSignatures.get(ref);
        if (signature.equals(previous)) {
            return;
        }
        debugSignatures.put(ref, signature);
        logDebugEvent(ref, store, npc, signature);
    }

    private void logDebugEvent(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull NPCEntity npc,
                               @Nonnull String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null || !plugin.isDebugFlyingCompanionEnabled()) {
            return;
        }
        String roleName = npc.getRoleName() == null || npc.getRoleName().isBlank() ? "<unknown>" : npc.getRoleName();
        String uuid = resolveUuid(store, ref);
        plugin.getLogger().at(Level.INFO).log(
                "[FlyingCompanionDebug] role=" + roleName + " uuid=" + uuid + " " + message
        );
    }

    private boolean shouldLogDebug(@Nonnull NPCEntity npc) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || !plugin.isDebugFlyingCompanionEnabled()) {
            return false;
        }
        String roleName = npc.getRoleName();
        return roleName != null && roleName.toLowerCase().contains("wyvern");
    }

    @Nullable
    private String resolveControllerType(@Nullable Role role) {
        if (role == null) {
            return null;
        }
        MotionController controller = role.getActiveMotionController();
        return controller == null ? null : controller.getType();
    }

    @Nonnull
    private String resolveUuid(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (uuidType == null) {
            return "<no-uuid-type>";
        }
        UUIDComponent component = store.getComponent(ref, uuidType);
        if (component == null || component.getUuid() == null) {
            return "<no-uuid>";
        }
        return component.getUuid().toString();
    }

    @Nonnull
    private String safeValue(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    @Nonnull
    private String formatDouble(double value) {
        return String.format("%.3f", value);
    }

    @Nullable
    private Vector3d resolveNearestSafeStandPosition(@Nonnull Store<EntityStore> store, @Nonnull Vector3d origin) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || world.getChunkStore() == null) {
            return null;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore != null ? chunkStore.getStore() : null;
        if (chunkStoreStore == null) {
            return null;
        }

        int originBlockX = (int) Math.floor(origin.x);
        int originBlockY = (int) Math.floor(origin.y);
        int originBlockZ = (int) Math.floor(origin.z);
        Vector3d best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        Map<Long, WorldChunk> chunkCache = new HashMap<>();

        for (int dx = -SAFE_HORIZONTAL_SCAN_RADIUS; dx <= SAFE_HORIZONTAL_SCAN_RADIUS; dx++) {
            for (int dz = -SAFE_HORIZONTAL_SCAN_RADIUS; dz <= SAFE_HORIZONTAL_SCAN_RADIUS; dz++) {
                int blockX = originBlockX + dx;
                int blockZ = originBlockZ + dz;
                for (int step = 0; step <= SAFE_VERTICAL_SCAN_STEPS; step++) {
                    int upY = originBlockY + step;
                    if (canStandAt(chunkStore, chunkStoreStore, blockX, upY, blockZ, chunkCache)) {
                        Vector3d candidate = toStandPosition(blockX, upY, blockZ);
                        double distanceSquared = distanceSquared(candidate, origin);
                        if (distanceSquared < bestDistanceSquared) {
                            best = candidate;
                            bestDistanceSquared = distanceSquared;
                        }
                        break;
                    }
                    if (step == 0) {
                        continue;
                    }
                    int downY = originBlockY - step;
                    if (canStandAt(chunkStore, chunkStoreStore, blockX, downY, blockZ, chunkCache)) {
                        Vector3d candidate = toStandPosition(blockX, downY, blockZ);
                        double distanceSquared = distanceSquared(candidate, origin);
                        if (distanceSquared < bestDistanceSquared) {
                            best = candidate;
                            bestDistanceSquared = distanceSquared;
                        }
                        break;
                    }
                }
            }
        }

        return best;
    }

    private boolean canStandAt(@Nonnull ChunkStore chunkStore,
                               @Nonnull Store<ChunkStore> chunkStoreStore,
                               int blockX,
                               int blockY,
                               int blockZ,
                               @Nonnull Map<Long, WorldChunk> chunkCache) {
        WorldChunk feetChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        WorldChunk headChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        WorldChunk groundChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        if (feetChunk == null || headChunk == null || groundChunk == null) {
            return false;
        }
        int feetFluid = feetChunk.getFluidId(blockX, blockY, blockZ);
        int headFluid = headChunk.getFluidId(blockX, blockY + 1, blockZ);
        int groundFluid = groundChunk.getFluidId(blockX, blockY - 1, blockZ);
        if (feetFluid != 0 || headFluid != 0) {
            return false;
        }
        int feetBlockId = feetChunk.getBlock(blockX, blockY, blockZ);
        int headBlockId = headChunk.getBlock(blockX, blockY + 1, blockZ);
        int groundBlockId = groundChunk.getBlock(blockX, blockY - 1, blockZ);
        if (isSolidBlock(feetBlockId, feetFluid) || isSolidBlock(headBlockId, headFluid)) {
            return false;
        }
        return isSolidBlock(groundBlockId, groundFluid);
    }

    @Nullable
    private WorldChunk resolveWorldChunk(@Nonnull ChunkStore chunkStore,
                                         @Nonnull Store<ChunkStore> chunkStoreStore,
                                         int blockX,
                                         int blockZ,
                                         @Nonnull Map<Long, WorldChunk> chunkCache) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        if (chunkCache.containsKey(chunkIndex)) {
            return chunkCache.get(chunkIndex);
        }
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
        if (chunkRef == null || !chunkRef.isValid()) {
            chunkCache.put(chunkIndex, null);
            return null;
        }
        WorldChunk worldChunk = chunkStoreStore.getComponent(chunkRef, WorldChunk.getComponentType());
        chunkCache.put(chunkIndex, worldChunk);
        return worldChunk;
    }

    @Nonnull
    private Vector3d toStandPosition(int blockX, int blockY, int blockZ) {
        return new Vector3d(blockX + 0.5, blockY + SAFE_STAND_HEIGHT_OFFSET, blockZ + 0.5);
    }

    private boolean isSolidBlock(int blockId, int fluidId) {
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null || blockType == BlockType.UNKNOWN) {
            return false;
        }
        return WorldUtil.isSolidOnlyBlock(blockType, fluidId);
    }

    private double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private <T extends Component<EntityStore>> T safeGetComponent(@Nonnull Store<EntityStore> store,
                                                                  @Nonnull Ref<EntityStore> reference,
                                                                  @Nonnull ComponentType<EntityStore, T> componentType) {
        try {
            return store.getComponent(reference, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return null;
        }
    }
}
