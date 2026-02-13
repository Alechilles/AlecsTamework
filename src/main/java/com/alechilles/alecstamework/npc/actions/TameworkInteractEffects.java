package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.DropItemEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FloatingTextEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HookEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeStep;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModifyStatsEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.OwnerSource;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.PlaySoundEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RemoveItemsHandEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RemoveItemsInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetStateEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetTamedEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SpawnParticlesEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StatDelta;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.UiMessageEffect;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

final class TameworkInteractEffects {
    private static final String HEALTH_STAT_ID = "Health";
    private static final String EMPTY_ROLE_ID = "Empty_Role";
    private static final String DEFAULT_MOUNT_ANCHOR_X_PARAM = "MountAnchorX";
    private static final String DEFAULT_MOUNT_ANCHOR_Y_PARAM = "MountAnchorY";
    private static final String DEFAULT_MOUNT_ANCHOR_Z_PARAM = "MountAnchorZ";
    private static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM = "MountMovementConfig";
    private static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_ID = "Mount";
    private static final ModeStep[] DEFAULT_MODE_CYCLE = new ModeStep[] {
            new ModeStep("Hold", null, null),
            new ModeStep("Idle", null, null),
            new ModeStep("Defend", null, null)
    };

    private final ActionTameworkInteract owner;
    private final InteractionInventoryEffects inventoryEffects;
    private final InteractionPresentationEffects presentationEffects;

    TameworkInteractEffects(ActionTameworkInteract owner) {
        this.owner = owner;
        this.inventoryEffects = new InteractionInventoryEffects(owner);
        this.presentationEffects = new InteractionPresentationEffects();
    }

    boolean applyCustomEffects(Effects effects,
                               Ref<EntityStore> npcRef,
                               Role role,
                               InfoProvider infoProvider,
                               Store<EntityStore> store,
                               Player player) {
        if (effects == null) {
            return false;
        }
        boolean applied = false;
        SetTamedEffect setTamed = effects.getSetTamed();
        if (setTamed != null) {
            applied |= applySetTamed(setTamed, npcRef, store);
        }
        SetOwnerEffect setOwner = effects.getSetOwner();
        if (setOwner != null) {
            applied |= applySetOwner(setOwner, npcRef, store, player);
        }
        ModifyStatsEffect modifyStats = effects.getModifyStats();
        if (modifyStats != null) {
            applied |= applyModifyStats(modifyStats, npcRef, store);
        }
        SetStateEffect setState = effects.getSetState();
        if (setState != null) {
            applied |= applySetState(setState, npcRef, role, store);
        }
        RemoveItemsHandEffect removeItemsHand = effects.getRemoveItemsHand();
        if (removeItemsHand != null) {
            applied |= inventoryEffects.applyRemoveItemsHand(removeItemsHand, player);
        }
        RemoveItemsInventoryEffect removeItemsInventory = effects.getRemoveItemsInventory();
        if (removeItemsInventory != null) {
            applied |= inventoryEffects.applyRemoveItemsInventory(removeItemsInventory, player);
        }
        AddItemInventoryEffect addItemInventory = effects.getAddItemInventory();
        if (addItemInventory != null) {
            applied |= inventoryEffects.applyAddItemInventory(addItemInventory, player);
        }
        if (Boolean.TRUE.equals(effects.getMount())) {
            applied |= applyMount(npcRef, role, infoProvider, store);
        }
        PlaySoundEffect playSound = effects.getPlaySound();
        if (playSound != null) {
            applied |= presentationEffects.applyPlaySound(playSound, npcRef, store, player);
        }
        SpawnParticlesEffect spawnParticles = effects.getSpawnParticles();
        if (spawnParticles != null) {
            applied |= presentationEffects.applySpawnParticles(spawnParticles, npcRef, store, player);
        }
        DropItemEffect dropItem = effects.getDropItem();
        if (dropItem != null) {
            applied |= inventoryEffects.applyDropItem(dropItem, npcRef, store);
        }
        HookEffect hookEffect = effects.getTriggerNpcHook();
        if (hookEffect != null) {
            applied |= applyTriggerNpcHook(hookEffect, npcRef, store, player);
        }
        FloatingTextEffect floatingText = effects.getShowFloatingText();
        if (floatingText != null) {
            applied |= presentationEffects.applyFloatingText(floatingText, npcRef, store, player);
        }
        UiMessageEffect uiMessage = effects.getShowUiMessage();
        if (uiMessage != null) {
            applied |= presentationEffects.applyUiMessage(uiMessage, player);
        }
        return applied;
    }

    boolean applyStartTaming(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(npcRef, tamedType, new TameworkTamedComponent(true));
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null && player != null) {
            PlayerRef ref = player.getPlayerRef();
            UUID ownerId = player.getUuid();
            String ownerName = ref != null ? ref.getUsername() : null;
            store.putComponent(npcRef, ownerType, new TameworkOwnerComponent(ownerId, ownerName));
        }
        return true;
    }

    boolean applyFeeding(Ref<EntityStore> npcRef, Store<EntityStore> store, double healAmount, Player player) {
        if (healAmount > 0) {
            applyHeal(npcRef, store, healAmount);
            presentationEffects.showFeedingCombatText(npcRef, store, player, healAmount);
        }
        return true;
    }

    boolean applyStartHarvest(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String subState = "";
        if (role.getStateSupport().getStateHelper() != null) {
            String defaultSub = role.getStateSupport().getStateHelper().getDefaultSubState();
            if (defaultSub != null && !defaultSub.isBlank()) {
                subState = defaultSub;
            }
        }
        role.getStateSupport().setState(npcRef, "$Harvest", subState, store);
        return true;
    }

    boolean applyMount(Ref<EntityStore> npcRef,
                       Role role,
                       InfoProvider infoProvider,
                       Store<EntityStore> store) {
        if (npcRef == null || role == null || store == null) {
            return false;
        }
        Ref<EntityStore> playerRef = owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        if (store.getArchetype(playerRef).contains(DeathComponent.getComponentType())) {
            return false;
        }
        ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
        if (mountType == null) {
            return false;
        }
        NPCMountComponent mountComponent = store.getComponent(npcRef, mountType);
        if (mountComponent != null) {
            return false;
        }
        mountComponent = store.ensureAndGetComponent(npcRef, mountType);
        mountComponent.setOriginalRoleIndex(NPCPlugin.get().getIndex(role.getRoleName()));
        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComponent == null) {
            return false;
        }
        float anchorX = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_X_PARAM, 0.0);
        float anchorY = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Y_PARAM, 0.0);
        float anchorZ = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Z_PARAM, 0.0);
        mountComponent.setOwnerPlayerRef(playerRefComponent);
        mountComponent.setAnchor(anchorX, anchorY, anchorZ);
        Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
        if (playerComponent == null) {
            return false;
        }
        PhysicsValues playerPhysicsValues = store.getComponent(playerRef, PhysicsValues.getComponentType());
        RoleChangeSystem.requestRoleChange(npcRef, role, NPCPlugin.get().getIndex(EMPTY_ROLE_ID), false, null, null, store);
        String movementConfigId = owner.getRoleStringParam(role, DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM);
        if (movementConfigId == null || movementConfigId.isBlank()) {
            movementConfigId = DEFAULT_MOUNT_MOVEMENT_CONFIG_ID;
        }
        MovementConfig movementConfig = MovementConfig.getAssetMap().getAsset(movementConfigId);
        if (movementConfig != null && playerPhysicsValues != null) {
            MovementManager movementManager = store.getComponent(playerRef, MovementManager.getComponentType());
            if (movementManager != null) {
                movementManager.setDefaultSettings(movementConfig.toPacket(), playerPhysicsValues, playerComponent.getGameMode());
                movementManager.applyDefaultSettings();
                movementManager.update(playerRefComponent.getPacketHandler());
            }
        }
        return true;
    }

    boolean applyToggleMode(ModeStep[] cycle,
                            boolean showFloatingText,
                            boolean showUiMessage,
                            Ref<EntityStore> npcRef,
                            Role role,
                            Store<EntityStore> store,
                            Player player) {
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        String defaultSub = resolveDefaultSubState(role);
        ModeStep[] resolvedCycle = (cycle == null || cycle.length == 0) ? DEFAULT_MODE_CYCLE : cycle;
        ResolvedModeStep[] resolved = resolveValidModeSteps(resolvedCycle, role, defaultSub);
        if (resolved.length == 0) {
            owner.logDebug("ModeToggle: no valid mode cycle states found for role " + role.getRoleName());
            return false;
        }
        int currentIndex = findCurrentModeIndex(resolved, role, defaultSub);
        int nextIndex = (currentIndex + 1) % resolved.length;
        if (currentIndex < 0) {
            nextIndex = 0;
        }
        ResolvedModeStep next = resolved[nextIndex];
        role.getStateSupport().setState(npcRef, next.state, next.subState, store);
            if (next.message != null && !next.message.isBlank()) {
                boolean emitted = false;
                if (showFloatingText) {
                    emitted |= presentationEffects.showFloatingTextMessage(next.message, npcRef, store, player);
                }
                if (showUiMessage) {
                    emitted |= presentationEffects.applyUiMessage(next.message, player);
                }
                if (emitted) {
                    owner.logDebug("ModeToggle: message=" + next.message);
                }
        }
        return true;
    }

    boolean applyStartBreeding() {
        owner.logUnsupported("Breeding interaction not yet implemented.");
        return false;
    }

    private boolean applySetTamed(SetTamedEffect effect,
                                  Ref<EntityStore> npcRef,
                                  Store<EntityStore> store) {
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
        return true;
    }

    private boolean applySetOwner(SetOwnerEffect effect,
                                  Ref<EntityStore> npcRef,
                                  Store<EntityStore> store,
                                  Player player) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return false;
        }
        OwnerSource source = effect.getSource();
        UUID ownerId = null;
        String ownerName = null;
        switch (source) {
            case Player -> {
                if (player == null) {
                    return false;
                }
                ownerId = player.getUuid();
                PlayerRef ref = player.getPlayerRef();
                ownerName = ref != null ? ref.getUsername() : null;
            }
            case None -> {
                ownerId = null;
                ownerName = null;
            }
            case Custom -> {
                String uuidText = effect.getUuid();
                if (uuidText != null && !uuidText.isBlank()) {
                    try {
                        ownerId = UUID.fromString(uuidText.trim());
                    } catch (IllegalArgumentException ignored) {
                        ownerId = null;
                    }
                }
                ownerName = effect.getName();
                if (ownerId == null && (ownerName == null || ownerName.isBlank())) {
                    return false;
                }
            }
        }
        store.putComponent(npcRef, ownerType, new TameworkOwnerComponent(ownerId, ownerName));
        return true;
    }

    private boolean applyModifyStats(ModifyStatsEffect effect,
                                     Ref<EntityStore> npcRef,
                                     Store<EntityStore> store) {
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

    private boolean applySetState(SetStateEffect effect,
                                  Ref<EntityStore> npcRef,
                                  Role role,
                                  Store<EntityStore> store) {
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

    private boolean applyHeal(Ref<EntityStore> npcRef, Store<EntityStore> store, double healAmount) {
        if (healAmount <= 0) {
            return false;
        }
        return applyStatDelta(npcRef, store, HEALTH_STAT_ID, healAmount);
    }

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
        return true;
    }

    private boolean applyTriggerNpcHook(HookEffect hookEffect,
                                        Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Player player) {
        if (hookEffect == null) {
            return false;
        }
        String hookId = hookEffect.getHookId();
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        if (hookEffect.isPlayerOnly() && player == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkHookComponent> type = TameworkHookComponent.getComponentType();
        if (type == null) {
            return false;
        }
        UUID playerId = null;
        String playerName = null;
        String heldItemId = null;
        if (player != null) {
            playerId = player.getUuid();
            PlayerRef ref = player.getPlayerRef();
            if (ref != null) {
                playerName = ref.getUsername();
            }
            ItemStack stack = owner.getActiveItem(player);
            if (stack != null) {
                heldItemId = stack.getItemId();
            }
        }
        long timestampMs = System.currentTimeMillis();
        TameworkHookComponent component = new TameworkHookComponent(
                hookId,
                playerId,
                playerName,
                heldItemId,
                timestampMs,
                hookEffect.isConsume()
        );
        store.putComponent(npcRef, type, component);
        return true;
    }

    private String resolveDefaultSubState(Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return "";
        }
        String sub = role.getStateSupport().getStateHelper().getDefaultSubState();
        return sub == null ? "" : sub;
    }

    private ResolvedModeStep[] resolveValidModeSteps(ModeStep[] cycle, Role role, String defaultSub) {
        if (cycle == null || cycle.length == 0 || role == null || role.getStateSupport() == null) {
            return new ResolvedModeStep[0];
        }
        StateSupport stateSupport = role.getStateSupport();
        if (stateSupport.getStateHelper() == null) {
            return new ResolvedModeStep[0];
        }
        ArrayList<ResolvedModeStep> resolved = new ArrayList<>();
        for (ModeStep step : cycle) {
            if (step == null || step.getState() == null || step.getState().isBlank()) {
                continue;
            }
            String state = step.getState();
            String sub = step.getSubState();
            if (sub == null || sub.isBlank()) {
                sub = defaultSub;
            }
            int stateIndex = stateSupport.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                continue;
            }
            String resolvedSub = sub == null ? "" : sub;
            if (!resolvedSub.isBlank()) {
                int subIndex = stateSupport.getStateHelper().getSubStateIndex(stateIndex, resolvedSub);
                if (subIndex == StateSupport.NO_STATE) {
                    continue;
                }
            }
            resolved.add(new ResolvedModeStep(state, resolvedSub, step.getMessage()));
        }
        return resolved.toArray(new ResolvedModeStep[0]);
    }

    private int findCurrentModeIndex(ResolvedModeStep[] steps, Role role, String defaultSub) {
        if (steps == null || steps.length == 0 || role == null || role.getStateSupport() == null) {
            return -1;
        }
        for (int i = 0; i < steps.length; i++) {
            ResolvedModeStep step = steps[i];
            if (step == null) {
                continue;
            }
            String sub = step.subState;
            if (sub == null || sub.isBlank()) {
                sub = defaultSub;
            }
            if (role.getStateSupport().inState(step.state, sub)) {
                return i;
            }
        }
        return -1;
    }

    private static final class ResolvedModeStep {
        private final String state;
        private final String subState;
        private final String message;

        private ResolvedModeStep(String state, String subState, String message) {
            this.state = state;
            this.subState = subState;
            this.message = message;
        }
    }
}
