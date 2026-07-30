package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.npc.movement.NativeMountMovementSettingsService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedEffectService;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedResolver;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converges companion movement-speed effects and native rider settings after lifecycle changes.
 */
public final class CompanionMovementSpeedSyncSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 750L;
    private static final String MOVE_SPEED_MULTIPLIER_EFFECT_KEY = "MoveSpeedMultiplier";
    private static final AtomicLong CONFIG_REVISION = new AtomicLong();

    private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);
    private final CompanionMovementSpeedResolver speedResolver = new CompanionMovementSpeedResolver();
    private final NativeMountMovementSettingsService riderSettings = new NativeMountMovementSettingsService();

    /** Marks all live-store fingerprints stale after companion movement asset changes. */
    public static void invalidateConfigRevision() {
        CONFIG_REVISION.incrementAndGet();
    }

    /**
     * Refreshes one companion after an interaction has made its attachment component visible in this store.
     */
    public void refreshImmediately(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        if (refreshCompanion(npcRef, store)) {
            commitFingerprint(statesByStore.get(store), buildFingerprint(npcRef, store));
        }
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        TickState state = statesByStore.get(store);
        long nowMs = System.currentTimeMillis();
        if (nowMs < state.nextSweepAtMs) {
            return;
        }
        state.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;

        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            return;
        }
        HashSet<UUID> activeIds = new HashSet<>();
        store.forEachChunk(Query.and(npcType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> buffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                Ref<EntityStore> npcRef = chunk.getReferenceTo(index);
                NPCEntity npc = chunk.getComponent(index, npcType);
                if (npcRef == null || !npcRef.isValid() || npc == null || npc.getUuid() == null) {
                    continue;
                }
                if (!TamedStateResolver.isTamed(npcRef, store)) {
                    continue;
                }
                UUID npcId = npc.getUuid();
                activeIds.add(npcId);
                MovementSpeedFingerprint fingerprint = buildFingerprint(npcRef, store);
                if (!hasChanged(state.lastFingerprintByNpc.get(npcId), fingerprint)) {
                    continue;
                }
                buffer.run(bufferStore -> {
                    try {
                        if (refreshCompanion(npcRef, bufferStore)) {
                            commitFingerprint(state, buildFingerprint(npcRef, bufferStore));
                        }
                    } catch (RuntimeException | LinkageError ignored) {
                        // Keep the prior fingerprint so the next sweep retries this transient state.
                    }
                });
            }
        });
        pruneInactiveKeys(state.lastFingerprintByNpc, activeIds);
    }

    private boolean refreshCompanion(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        if (!npcRef.isValid() || store.getComponent(npcRef, NPCEntity.getComponentType()) == null) {
            return false;
        }
        if (shouldSkipManagedMovement(
                hasComponent(npcRef, store, TameworkMountedGlideComponent.getComponentType()),
                hasComponent(npcRef, store, AvatarFlightSourceComponent.getComponentType()))) {
            return true;
        }
        NPCMountComponent mount = store.getComponent(npcRef, NPCMountComponent.getComponentType());
        boolean nativeMountPresent = mount != null;
        String sourceRoleId = NativeMountMovementSettingsService.resolveManagedRoleId(npcRef, store);
        double multiplier = resolveQuantizedMultiplier(npcRef, sourceRoleId, store);
        boolean controllerAvailable = store.getComponent(npcRef, EffectControllerComponent.getComponentType()) != null;
        boolean effectApplied = controllerAvailable
                && CompanionMovementSpeedEffectService.applyResolvedMultiplier(npcRef, store, sourceRoleId, multiplier);
        Ref<EntityStore> riderRef = nativeMountPresent
                ? NativeMountMovementSettingsService.resolveMountedRiderRef(mount, store) : null;
        Player rider = riderRef == null ? null : store.getComponent(riderRef, Player.getComponentType());
        PlayerRef riderPlayerRef = riderRef == null ? null : store.getComponent(riderRef, PlayerRef.getComponentType());
        boolean activeRiderAvailable = riderRef != null && riderRef.isValid()
                && rider != null && riderPlayerRef != null;
        boolean riderSettingsApplied = activeRiderAvailable && effectApplied && riderSettings.applyScaledSettings(
                sourceRoleId, NativeMountMovementSettingsService.resolveMountedSourceRoleScopes(mount), riderRef,
                riderPlayerRef, rider, store, multiplier);
        return isRefreshComplete(new RefreshCompletion(
                effectApplied, nativeMountPresent, activeRiderAvailable, riderSettingsApplied));
    }

    @Nullable
    private MovementSpeedFingerprint buildFingerprint(@Nonnull Ref<EntityStore> npcRef,
                                                      @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return null;
        }
        NPCMountComponent mount = store.getComponent(npcRef, NPCMountComponent.getComponentType());
        PlayerRef owner = mount == null ? null : mount.getOwnerPlayerRef();
        String sourceRoleId = NativeMountMovementSettingsService.resolveManagedRoleId(npcRef, store);
        return createFingerprint(
                npc.getUuid(),
                sourceRoleId,
                resolveEffectiveAttachments(npcRef, store),
                resolveQuantizedMultiplier(npcRef, sourceRoleId, store),
                CONFIG_REVISION.get(),
                mount != null,
                owner == null ? null : owner.getUuid()
        );
    }

    private double resolveQuantizedMultiplier(@Nonnull Ref<EntityStore> npcRef,
                                              @Nullable String sourceRoleId,
                                              @Nonnull Store<EntityStore> store) {
        return speedResolver.resolve(
                TwCompanionMovementConfig.resolveForRole(sourceRoleId),
                resolveEffectiveAttachments(npcRef, store),
                CompanionProgressionModifierService.resolveMultiplier(
                        npcRef, store, sourceRoleId, MOVE_SPEED_MULTIPLIER_EFFECT_KEY, 1.0)
        ).quantizedMultiplier();
    }

    @Nonnull
    private Map<String, String> resolveEffectiveAttachments(@Nonnull Ref<EntityStore> npcRef,
                                                            @Nonnull Store<EntityStore> store) {
        return CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
    }

    static MovementSpeedFingerprint createFingerprint(@Nonnull UUID npcUuid,
                                                       @Nullable String sourceRoleId,
                                                       @Nullable Map<String, String> effectiveAttachments,
                                                       double quantizedMultiplier,
                                                       long configRevision,
                                                       boolean nativeMounted,
                                                       @Nullable UUID riderUuid) {
        Map<String, String> attachments = effectiveAttachments == null || effectiveAttachments.isEmpty()
                ? Map.of() : Map.copyOf(effectiveAttachments);
        return new MovementSpeedFingerprint(
                npcUuid, sourceRoleId, attachments, quantizedMultiplier, configRevision, nativeMounted, riderUuid);
    }

    static boolean hasChanged(@Nullable MovementSpeedFingerprint previous,
                              @Nullable MovementSpeedFingerprint current) {
        return current != null && !current.equals(previous);
    }

    /** Pure lifecycle completion rule used by the deferred callback before it commits a fingerprint. */
    static boolean isRefreshComplete(@Nonnull RefreshCompletion completion) {
        return completion.controllerAvailable()
                && (!completion.nativeMountPresent()
                || (completion.activeRiderAvailable() && completion.riderSettingsApplied()));
    }

    /** Keeps Tamework glide and avatar-flight sessions outside native mount movement ownership. */
    static boolean shouldSkipManagedMovement(boolean mountedGlideActive, boolean avatarFlightActive) {
        return mountedGlideActive || avatarFlightActive;
    }

    private static <T extends Component<EntityStore>> boolean hasComponent(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull Store<EntityStore> store,
                                            @Nullable ComponentType<EntityStore, T> type) {
        return type != null && store.getComponent(ref, type) != null;
    }

    private static void commitFingerprint(@Nonnull TickState state, @Nullable MovementSpeedFingerprint fingerprint) {
        if (fingerprint != null) {
            state.lastFingerprintByNpc.put(fingerprint.npcUuid(), fingerprint);
        }
    }

    static <T> void pruneInactiveKeys(@Nonnull Map<UUID, T> valuesByNpc, @Nonnull HashSet<UUID> activeNpcIds) {
        if (activeNpcIds.isEmpty()) {
            valuesByNpc.clear();
            return;
        }
        for (UUID npcId : new ArrayList<>(valuesByNpc.keySet())) {
            if (npcId != null && !activeNpcIds.contains(npcId)) {
                valuesByNpc.remove(npcId);
            }
        }
    }

    record MovementSpeedFingerprint(@Nonnull UUID npcUuid,
                                    @Nullable String sourceRoleId,
                                    @Nonnull Map<String, String> effectiveAttachments,
                                    double quantizedMultiplier,
                                    long configRevision,
                                    boolean nativeMounted,
                                    @Nullable UUID riderUuid) {
    }

    /** Captures the current callback-time dependencies needed before a fingerprint may be committed. */
    record RefreshCompletion(boolean controllerAvailable,
                             boolean nativeMountPresent,
                             boolean activeRiderAvailable,
                             boolean riderSettingsApplied) {
    }

    private static final class TickState {
        private long nextSweepAtMs;
        private final Map<UUID, MovementSpeedFingerprint> lastFingerprintByNpc = new HashMap<>();
    }
}
