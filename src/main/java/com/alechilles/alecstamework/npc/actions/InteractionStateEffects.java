package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModifyStatsEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.OwnerSource;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetStateEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetTamedEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StatDelta;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Applies interaction effects that change NPC ownership, stats, or states. */
final class InteractionStateEffects {
    private static final String HEALTH_STAT_ID = "Health";
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";
    private static final int EFFECT_INDEX_UNRESOLVED = Integer.MIN_VALUE;
    private static final int DISABLE_DESPAWN_SPAWN_CONFIGURATION = Integer.MIN_VALUE;
    private static int tranquilizerEffectIndex = EFFECT_INDEX_UNRESOLVED;
    private final TameClaimLimitPolicyService claimLimitPolicyService =
            new TameClaimLimitPolicyService();
    /**
     * Cross-template swaps (for example wild -> tamed livestock) can fail when preserving
     * state/substate names from the source role. Interaction-driven role changes should
     * start from the target role's default state.
     */
    // Marks the NPC as tamed and assigns owner based on the interacting player.
    boolean applyStartTaming(Ref<EntityStore> npcRef,
                             Store<EntityStore> store,
                             Player player,
                             @Nullable OwnerAppliedContinuation continuation) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (isNewPlayerOwnershipAcquisitionDenied(
                npcRef, store, player, ownerType
        )) {
            return false;
        }
        OwnerAppliedContinuation safeContinuation = continuation == null
                ? OwnerAppliedContinuation.NOOP
                : continuation;
        if (ownerType != null && player != null) {
            PlayerRef ref = player.getPlayerRef();
            store.putComponent(
                    npcRef,
                    ownerType,
                    new TameworkOwnerComponent(
                            player.getUuid(), ref == null ? null : ref.getUsername()
                    )
            );
        }
        applyTameBundleAfterOwnership(npcRef, store);
        safeContinuation.onApplied(npcRef, store, player);
        return true;
    }

    // Starts the harvest state using the default substate if available.
    boolean applyStartHarvest(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String subState = resolveDefaultSubState(role);
        role.getStateSupport().setState(npcRef, "$Harvest", subState, store);
        return true;
    }

    // Sets the tamed component directly from config.
    boolean applySetTamed(SetTamedEffect effect, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        Boolean value = effect.getValue();
        if (value == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType == null) {
            return false;
        }
        store.putComponent(npcRef, tamedType, new TameworkTamedComponent(value));
        if (value) {
            detachFromSpawnReferences(npcRef, store);
            applyDisableSpawnDrivenDespawn(npcRef, store);
            CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        }
        return true;
    }

    // Sets the owner component based on the configured source.
    boolean applySetOwner(SetOwnerEffect effect,
                          Ref<EntityStore> npcRef,
                          Store<EntityStore> store,
                          Player player,
                          @Nullable OwnerAppliedContinuation continuation) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return false;
        }
        ResolvedOwner owner = resolveOwner(effect, npcRef, store, player, ownerType);
        if (owner == null) {
            return false;
        }
        store.putComponent(
                npcRef, ownerType,
                new TameworkOwnerComponent(owner.id(), owner.name())
        );
        OwnerAppliedContinuation safe = continuation == null
                ? OwnerAppliedContinuation.NOOP
                : continuation;
        safe.onApplied(npcRef, store, player);
        return true;
    }

    @Nullable
    private ResolvedOwner resolveOwner(
            SetOwnerEffect effect,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable Player player,
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        OwnerSource source = effect.getSource();
        if (source == null) {
            return null;
        }
        if (source == OwnerSource.Player) {
            if (player == null || isNewPlayerOwnershipAcquisitionDenied(
                    npcRef, store, player, ownerType
            )) {
                return null;
            }
            PlayerRef ref = player.getPlayerRef();
            return new ResolvedOwner(
                    player.getUuid(), ref == null ? null : ref.getUsername()
            );
        }
        if (source == OwnerSource.None) {
            return new ResolvedOwner(null, null);
        }
        UUID ownerId = parseOwnerId(effect.getUuid());
        String ownerName = effect.getName();
        return ownerId == null && (ownerName == null || ownerName.isBlank())
                ? null
                : new ResolvedOwner(ownerId, ownerName);
    }

    @Nullable
    private static UUID parseOwnerId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isNewPlayerOwnershipAcquisitionDenied(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            Player player,
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType
    ) {
        if (npcRef == null || !npcRef.isValid() || store == null
                || player == null || ownerType == null || player.getUuid() == null) {
            return false;
        }
        TameworkOwnerComponent existing = store.getComponent(npcRef, ownerType);
        if (existing != null && existing.getOwnerId() != null) {
            return false;
        }
        OwnerPopulationCapService.Decision ownerCap =
                OwnerPopulationCapService.evaluateAcquisition(store, player.getUuid());
        if (!ownerCap.allowed()) {
            OwnerMessageUtil.sendPopulationCapReached(
                    player, ownerCap.currentCount(), ownerCap.limit(), ownerCap.scope()
            );
            return true;
        }
        BreedingClaimLimitPolicyService.Decision claimCap =
                claimLimitPolicyService.evaluate(store, resolvePosition(npcRef, store));
        if (claimCap.allowed()) {
            return false;
        }
        if ("claim-cap-reached".equals(claimCap.reason())) {
            OwnerMessageUtil.sendClaimPopulationCapReached(
                    player, claimCap.currentCount(), claimCap.effectiveCap()
            );
        }
        return true;
    }

    @Nullable
    private static Vector3d resolvePosition(
            Ref<EntityStore> npcRef, Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TransformComponent> type =
                TransformComponent.getComponentType();
        TransformComponent transform = type == null
                ? null
                : store.getComponent(npcRef, type);
        return transform == null ? null : transform.getPosition();
    }

    void applyTameBundleAfterOwnership(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        clearTranquilizerEffect(npcRef, store);
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(npcRef, tamedType, new TameworkTamedComponent(true));
        }
        detachFromSpawnReferences(npcRef, store);
        applyDisableSpawnDrivenDespawn(npcRef, store);
        logDespawnDebug("start_taming", npcRef, store, null);
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
    }

    @FunctionalInterface
    interface OwnerAppliedContinuation {
        OwnerAppliedContinuation NOOP = (npcRef, store, player) -> {
        };

        void onApplied(Ref<EntityStore> npcRef,
                       Store<EntityStore> store,
                       @Nullable Player player);
    }

    private record ResolvedOwner(@Nullable UUID id, @Nullable String name) {
    }

    // Applies stat deltas from a modify stats effect.
    boolean applyModifyStats(ModifyStatsEffect effect, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (effect == null) {
            return false;
        }
        StatDelta[] deltas = effect.getStats();
        if (deltas == null || deltas.length == 0) {
            return false;
        }
        boolean applied = false;
        for (StatDelta delta : deltas) {
            if (delta == null) {
                continue;
            }
            String statId = delta.getStatId();
            Double amount = delta.getAmount();
            if (statId == null || statId.isBlank() || amount == null || amount == 0.0) {
                continue;
            }
            applied |= applyStatDelta(npcRef, store, statId, amount);
        }
        return applied;
    }

    // Sets the role state and substate from the config.
    boolean applySetState(SetStateEffect effect, Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (effect == null || role == null || role.getStateSupport() == null) {
            return false;
        }
        String state = effect.getState();
        String subState = effect.getSubState();
        if (state != null && state.contains(".") && (subState == null || subState.isBlank())) {
            String[] parts = state.split("\\.", 2);
            state = parts[0];
            subState = parts.length > 1 ? parts[1] : subState;
        }
        if (state == null || state.isBlank()) {
            if (subState == null || subState.isBlank()) {
                return false;
            }
            StateSupport stateSupport = role.getStateSupport();
            if (stateSupport.getStateHelper() == null) {
                return false;
            }
            int currentState = stateSupport.getStateIndex();
            if (currentState == StateSupport.NO_STATE) {
                return false;
            }
            state = stateSupport.getStateHelper().getStateName(currentState);
        }
        if (subState == null || subState.isBlank()) {
            subState = resolveDefaultSubState(role);
        }
        role.getStateSupport().setState(npcRef, state, subState == null ? "" : subState, store);
        return true;
    }

    // Swaps the NPC role using the role change system.
    boolean applySetRole(String roleId, boolean changeAppearance, Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (role == null || npcRef == null || store == null) {
            return false;
        }
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            return false;
        }
        int roleIndex = plugin.getIndex(roleId);
        if (roleIndex < 0) {
            return false;
        }
        clearTranquilizerEffect(npcRef, store);
        RoleChangeSystem.requestRoleChange(
                npcRef,
                role,
                roleIndex,
                changeAppearance,
                store
        );
        applyDisableSpawnDrivenDespawn(npcRef, store);
        logDespawnDebug("set_role", npcRef, store, roleId);
        return true;
    }

    // Immediately opts this NPC out of spawn-configuration-driven despawn checks.
    boolean applyDisableSpawnDrivenDespawn(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            return false;
        }
        NPCEntity npc = store.getComponent(npcRef, npcType);
        if (npc == null || npc.getSpawnConfiguration() == DISABLE_DESPAWN_SPAWN_CONFIGURATION) {
            return false;
        }
        npc.setSpawnConfiguration(DISABLE_DESPAWN_SPAWN_CONFIGURATION);
        return true;
    }

    // Detaches tameable NPCs from spawn marker/beacon ownership so marker-lost cleanup cannot reclaim companions.
    private boolean detachFromSpawnReferences(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        boolean detached = false;
        ComponentType<EntityStore, SpawnMarkerReference> markerReferenceType = null;
        try {
            markerReferenceType = SpawnMarkerReference.getComponentType();
        } catch (RuntimeException | LinkageError ignored) {
            markerReferenceType = null;
        }
        if (markerReferenceType != null && store.getComponent(npcRef, markerReferenceType) != null) {
            store.tryRemoveComponent(npcRef, markerReferenceType);
            detached = true;
        }
        ComponentType<EntityStore, SpawnBeaconReference> beaconReferenceType = null;
        try {
            beaconReferenceType = SpawnBeaconReference.getComponentType();
        } catch (RuntimeException | LinkageError ignored) {
            beaconReferenceType = null;
        }
        if (beaconReferenceType != null && store.getComponent(npcRef, beaconReferenceType) != null) {
            store.tryRemoveComponent(npcRef, beaconReferenceType);
            detached = true;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            return detached;
        }
        NPCEntity npc = store.getComponent(npcRef, npcType);
        if (npc == null) {
            return detached;
        }
        return npc.updateSpawnTrackingState(false) || detached;
    }

    private static void logDespawnDebug(String stage,
                                        Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        String targetRoleId) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || !plugin.isDebugDespawnEnabled() || plugin.getLogger() == null) {
            return;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null || npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, npcType);
        if (npc == null) {
            return;
        }
        String roleName = npc.getRoleName();
        if (!plugin.matchesDebugDespawnRole(roleName) && !plugin.matchesDebugDespawnRole(targetRoleId)) {
            return;
        }
        plugin.getLogger().at(Level.INFO).log(
                "Despawn diagnostics: tame flow stage=" + stage
                        + " uuid=" + (npc.getUuid() == null ? "<none>" : npc.getUuid())
                        + " role=" + (roleName == null || roleName.isBlank() ? "<none>" : roleName)
                        + " targetRole=" + (targetRoleId == null || targetRoleId.isBlank() ? "<none>" : targetRoleId)
                        + " spawnConfig=" + npc.getSpawnConfiguration()
        );
    }

    // Applies a healing delta to the NPC stats.
    boolean applyHeal(Ref<EntityStore> npcRef, Store<EntityStore> store, double healAmount) {
        if (healAmount <= 0) {
            return false;
        }
        return applyStatDelta(npcRef, store, HEALTH_STAT_ID, healAmount);
    }

    // Resolves the default substate for a role.
    String resolveDefaultSubState(Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return "";
        }
        String sub = role.getStateSupport().getStateHelper().getDefaultSubState();
        return sub == null ? "" : sub;
    }

    // Adds a delta to the NPC stat map for the specified stat.
    private boolean applyStatDelta(Ref<EntityStore> npcRef,
                                   Store<EntityStore> store,
                                   String statId,
                                   double delta) {
        if (npcRef == null || store == null || statId == null || statId.isBlank()) {
            return false;
        }
        if (Math.abs(delta) < 0.0001) {
            return false;
        }
        ComponentType<EntityStore, EntityStatMap> type = EntityStatMap.getComponentType();
        if (type == null) {
            return false;
        }
        EntityStatMap statMap = store.getComponent(npcRef, type);
        if (statMap == null) {
            return false;
        }
        int statIndex = EntityStatType.getAssetMap().getIndex(statId);
        if (statIndex < 0) {
            return false;
        }
        statMap.addStatValue(statIndex, (float) delta);
        if (HEALTH_STAT_ID.equals(statId) && delta > 0.0) {
            CompanionNeedsService.allowExternalHeal(npcRef, store, delta);
        }
        return true;
    }

    // Clears the tranquilizer status effect before tame/role-swap transitions so remove updates are emitted reliably.
    private boolean clearTranquilizerEffect(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        int effectIndex = resolveTranquilizerEffectIndex();
        if (effectIndex == EFFECT_INDEX_UNRESOLVED) {
            return false;
        }
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return false;
        }
        EffectControllerComponent effectController = store.getComponent(npcRef, effectType);
        if (effectController == null || !effectController.getActiveEffects().containsKey(effectIndex)) {
            return false;
        }
        effectController.removeEffect(npcRef, effectIndex, RemovalBehavior.COMPLETE, store);
        return true;
    }

    private int resolveTranquilizerEffectIndex() {
        if (tranquilizerEffectIndex != EFFECT_INDEX_UNRESOLVED) {
            return tranquilizerEffectIndex;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> effectMap = EntityEffect.getAssetMap();
        if (effectMap == null) {
            return EFFECT_INDEX_UNRESOLVED;
        }
        int resolved = effectMap.getIndex(TRANQUILIZER_EFFECT_ID);
        if (resolved != EFFECT_INDEX_UNRESOLVED) {
            tranquilizerEffectIndex = resolved;
        }
        return resolved;
    }
}
