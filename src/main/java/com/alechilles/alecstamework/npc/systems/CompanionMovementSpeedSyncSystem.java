package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.movement.NativeMountMovementSettingsService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedEffectService;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedResolver;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
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
    private final CompanionMovementSpeedInputFingerprint inputFingerprint =
            new CompanionMovementSpeedInputFingerprint();
    private final CompanionMovementSpeedResolver speedResolver = new CompanionMovementSpeedResolver();
    private final NativeMountMovementSettingsService riderSettings = new NativeMountMovementSettingsService();

    /** Marks all live-store fingerprints stale after movement-related config changes. */
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
        HashSet<UUID> activeIds = state.activeNpcIds;
        activeIds.clear();
        boolean levelingEnabled = CompanionProgressionSettings.isLevelingEnabled();
        boolean talentsEnabled = CompanionProgressionSettings.isTalentsEnabled();
        long configRevision = CONFIG_REVISION.get();
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
                if (!hasInputChanged(
                        state.lastFingerprintByNpc.get(npcId), npcRef, npcId, npc.getRoleIndex(), store,
                        levelingEnabled, talentsEnabled, configRevision)) {
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
        CompanionMovementSpeedResolver.Result resolved = resolveMovementMultiplier(npcRef, sourceRoleId, store);
        double effectMultiplier = selectAppliedMultiplier(false, resolved);
        double riderMultiplier = selectAppliedMultiplier(nativeMountPresent, resolved);
        boolean controllerAvailable = store.getComponent(npcRef, EffectControllerComponent.getComponentType()) != null;
        boolean effectApplied = controllerAvailable
                && CompanionMovementSpeedEffectService.applyResolvedMultiplier(
                        npcRef, store, sourceRoleId, nativeMountPresent ? 1.0 : effectMultiplier);
        Ref<EntityStore> riderRef = nativeMountPresent
                ? NativeMountMovementSettingsService.resolveMountedRiderRef(mount, store) : null;
        Player rider = riderRef == null ? null : store.getComponent(riderRef, Player.getComponentType());
        PlayerRef riderPlayerRef = riderRef == null ? null : store.getComponent(riderRef, PlayerRef.getComponentType());
        boolean activeRiderAvailable = riderRef != null && riderRef.isValid()
                && rider != null && riderPlayerRef != null;
        boolean riderSettingsApplied = activeRiderAvailable && effectApplied && riderSettings.applyScaledSettings(
                sourceRoleId, NativeMountMovementSettingsService.resolveMountedSourceRoleScopes(mount), riderRef,
                riderPlayerRef, rider, store, riderMultiplier);
        return isRefreshComplete(new RefreshCompletion(
                effectApplied, nativeMountPresent, activeRiderAvailable, riderSettingsApplied));
    }

    @Nullable
    private MovementSpeedFingerprint buildFingerprint(@Nonnull Ref<EntityStore> npcRef,
                                                      @Nonnull Store<EntityStore> store) {
        return buildFingerprint(
                npcRef,
                store,
                CompanionProgressionSettings.isLevelingEnabled(),
                CompanionProgressionSettings.isTalentsEnabled());
    }

    private boolean hasInputChanged(@Nullable MovementSpeedFingerprint previous,
                                    @Nonnull Ref<EntityStore> npcRef,
                                    @Nonnull UUID npcUuid,
                                    int roleIndex,
                                    @Nonnull Store<EntityStore> store,
                                    boolean levelingEnabled,
                                    boolean talentsEnabled,
                                    long configRevision) {
        NPCMountComponent mount = store.getComponent(npcRef, NPCMountComponent.getComponentType());
        PlayerRef owner = mount == null ? null : mount.getOwnerPlayerRef();
        return hasChanged(
                previous,
                npcUuid,
                NativeMountMovementSettingsService.resolveManagedRoleId(npcRef, store),
                inputFingerprint.resolve(npcRef, store, roleIndex, levelingEnabled, talentsEnabled),
                configRevision,
                mount != null,
                owner == null ? null : owner.getUuid());
    }

    @Nullable
    private MovementSpeedFingerprint buildFingerprint(@Nonnull Ref<EntityStore> npcRef,
                                                       @Nonnull Store<EntityStore> store,
                                                       boolean levelingEnabled,
                                                       boolean talentsEnabled) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return null;
        }
        NPCMountComponent mount = store.getComponent(npcRef, NPCMountComponent.getComponentType());
        PlayerRef owner = mount == null ? null : mount.getOwnerPlayerRef();
        String sourceRoleId = NativeMountMovementSettingsService.resolveManagedRoleId(npcRef, store);
        return new MovementSpeedFingerprint(
                npc.getUuid(),
                sourceRoleId,
                inputFingerprint.resolve(
                        npcRef, store, npc.getRoleIndex(), levelingEnabled, talentsEnabled),
                CONFIG_REVISION.get(),
                mount != null,
                owner == null ? null : owner.getUuid()
        );
    }

    @Nonnull
    private CompanionMovementSpeedResolver.Result resolveMovementMultiplier(@Nonnull Ref<EntityStore> npcRef,
                                                                             @Nullable String sourceRoleId,
                                                                             @Nonnull Store<EntityStore> store) {
        return speedResolver.resolve(
                TwCompanionMovementConfig.resolveForRole(sourceRoleId),
                resolveEffectiveAttachments(npcRef, store),
                CompanionProgressionModifierService.resolveMultiplier(
                        npcRef, store, sourceRoleId, MOVE_SPEED_MULTIPLIER_EFFECT_KEY, 1.0)
        );
    }

    /** Uses exact values for native riders and static-effect-compatible values for unmounted companions. */
    static double selectAppliedMultiplier(boolean nativeMountPresent,
                                          @Nonnull CompanionMovementSpeedResolver.Result resolved) {
        return nativeMountPresent ? resolved.clampedMultiplier() : resolved.quantizedMultiplier();
    }

    @Nonnull
    private Map<String, String> resolveEffectiveAttachments(@Nonnull Ref<EntityStore> npcRef,
                                                            @Nonnull Store<EntityStore> store) {
        return CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
    }

    static boolean hasChanged(@Nullable MovementSpeedFingerprint previous,
                              @Nullable MovementSpeedFingerprint current) {
        return current != null && !current.equals(previous);
    }

    static boolean hasChanged(@Nullable MovementSpeedFingerprint previous,
                              @Nonnull UUID npcUuid,
                              @Nullable String sourceRoleId,
                              long inputSignature,
                              long configRevision,
                              boolean nativeMounted,
                              @Nullable UUID riderUuid) {
        return previous == null
                || !npcUuid.equals(previous.npcUuid())
                || !Objects.equals(sourceRoleId, previous.sourceRoleId())
                || inputSignature != previous.inputSignature()
                || configRevision != previous.configRevision()
                || nativeMounted != previous.nativeMounted()
                || !Objects.equals(riderUuid, previous.riderUuid());
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
        Iterator<UUID> npcIds = valuesByNpc.keySet().iterator();
        while (npcIds.hasNext()) {
            UUID npcId = npcIds.next();
            if (npcId != null && !activeNpcIds.contains(npcId)) {
                npcIds.remove();
            }
        }
    }

    record MovementSpeedFingerprint(@Nonnull UUID npcUuid,
                                    @Nullable String sourceRoleId,
                                    long inputSignature,
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
        private final HashSet<UUID> activeNpcIds = new HashSet<>();
        private final Map<UUID, MovementSpeedFingerprint> lastFingerprintByNpc = new HashMap<>();
    }
}
