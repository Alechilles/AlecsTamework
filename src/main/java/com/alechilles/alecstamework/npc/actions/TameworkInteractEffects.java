package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.DropItemEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FloatingTextEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HookEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemQuantity;
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
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.ComponentUpdateType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
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
    private final InteractionUiMessageService uiMessageService = new InteractionUiMessageService();

    TameworkInteractEffects(ActionTameworkInteract owner) {
        this.owner = owner;
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
            applied |= applyRemoveItemsHand(removeItemsHand, player);
        }
        RemoveItemsInventoryEffect removeItemsInventory = effects.getRemoveItemsInventory();
        if (removeItemsInventory != null) {
            applied |= applyRemoveItemsInventory(removeItemsInventory, player);
        }
        AddItemInventoryEffect addItemInventory = effects.getAddItemInventory();
        if (addItemInventory != null) {
            applied |= applyAddItemInventory(addItemInventory, player);
        }
        if (Boolean.TRUE.equals(effects.getMount())) {
            applied |= applyMount(npcRef, role, infoProvider, store);
        }
        PlaySoundEffect playSound = effects.getPlaySound();
        if (playSound != null) {
            applied |= applyPlaySound(playSound, npcRef, store, player);
        }
        SpawnParticlesEffect spawnParticles = effects.getSpawnParticles();
        if (spawnParticles != null) {
            applied |= applySpawnParticles(spawnParticles, npcRef, store, player);
        }
        DropItemEffect dropItem = effects.getDropItem();
        if (dropItem != null) {
            applied |= applyDropItem(dropItem, npcRef, store);
        }
        HookEffect hookEffect = effects.getTriggerNpcHook();
        if (hookEffect != null) {
            applied |= applyTriggerNpcHook(hookEffect, npcRef, store, player);
        }
        FloatingTextEffect floatingText = effects.getShowFloatingText();
        if (floatingText != null) {
            applied |= applyFloatingText(floatingText, npcRef, store, player);
        }
        UiMessageEffect uiMessage = effects.getShowUiMessage();
        if (uiMessage != null) {
            applied |= applyUiMessage(uiMessage, player);
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
            maybeShowFeedingCombatText(npcRef, store, player, healAmount);
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
                emitted |= applyMessageText(next.message, npcRef, store, player);
            }
            if (showUiMessage) {
                emitted |= applyUiMessage(next.message, player);
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

    private boolean applyRemoveItemsHand(RemoveItemsHandEffect effect, Player player) {
        if (effect == null) {
            return false;
        }
        int quantity = effect.getQuantity() != null ? effect.getQuantity() : 1;
        return owner.removeHeldItemQuantity(player, quantity);
    }

    private boolean applyRemoveItemsInventory(RemoveItemsInventoryEffect effect, Player player) {
        if (effect == null || player == null) {
            return false;
        }
        ItemQuantity[] items = effect.getItems();
        if (items == null || items.length == 0) {
            return false;
        }
        CombinedItemContainer container = owner.resolveInventoryContainer(player);
        if (container == null) {
            return false;
        }
        boolean applied = false;
        for (ItemQuantity item : items) {
            if (item == null || item.getItem() == null || item.getItem().isBlank()) {
                continue;
            }
            int quantity = item.getQuantity() != null ? item.getQuantity() : 1;
            if (quantity <= 0) {
                continue;
            }
            ItemStackTransaction transaction = container.removeItemStack(new ItemStack(item.getItem(), quantity));
            if (transaction != null) {
                ItemStack remainder = transaction.getRemainder();
                if (remainder == null || remainder.isEmpty() || remainder.getQuantity() < quantity) {
                    applied = true;
                }
            }
        }
        return applied;
    }

    private boolean applyAddItemInventory(AddItemInventoryEffect effect, Player player) {
        if (effect == null || player == null) {
            return false;
        }
        ItemQuantity[] items = effect.getItems();
        if (items == null || items.length == 0) {
            return false;
        }
        CombinedItemContainer container = owner.resolveInventoryContainer(player);
        if (container == null) {
            return false;
        }
        boolean applied = false;
        for (ItemQuantity item : items) {
            if (item == null || item.getItem() == null || item.getItem().isBlank()) {
                continue;
            }
            int quantity = item.getQuantity() != null ? item.getQuantity() : 1;
            if (quantity <= 0) {
                continue;
            }
            ItemStackTransaction transaction = container.addItemStack(new ItemStack(item.getItem(), quantity));
            if (transaction != null) {
                ItemStack remainder = transaction.getRemainder();
                if (remainder == null || remainder.isEmpty() || remainder.getQuantity() < quantity) {
                    applied = true;
                }
            }
        }
        return applied;
    }

    private void maybeShowFeedingCombatText(Ref<EntityStore> npcRef,
                                            Store<EntityStore> store,
                                            Player player,
                                            double healAmount) {
        if (npcRef == null || store == null || player == null) {
            return;
        }
        if (healAmount <= 0) {
            return;
        }
        String text = formatHealText(healAmount);
        if (text == null || text.isBlank()) {
            return;
        }
        queueCombatText(npcRef, store, player, text);
    }

    private boolean applyFloatingText(FloatingTextEffect effect,
                                      Ref<EntityStore> npcRef,
                                      Store<EntityStore> store,
                                      Player player) {
        if (effect == null) {
            return false;
        }
        return applyMessageText(effect.getMessage(), npcRef, store, player);
    }

    private boolean applyUiMessage(UiMessageEffect effect, Player player) {
        if (effect == null) {
            return false;
        }
        return applyUiMessage(effect.getMessage(), player);
    }

    private boolean applyUiMessage(String message, Player player) {
        return uiMessageService.show(player, message);
    }

    private boolean applyMessageText(String message,
                                     Ref<EntityStore> npcRef,
                                     Store<EntityStore> store,
                                     Player player) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (npcRef == null || store == null || player == null) {
            return false;
        }
        // Size/Duration/Color are placeholders for now; CombatText uses the global UI asset.
        return queueCombatText(npcRef, store, player, message);
    }

    private boolean applySpawnParticles(SpawnParticlesEffect effect,
                                        Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Player player) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        String particleSystem = effect.getParticleSystem();
        if (particleSystem == null || particleSystem.isBlank()) {
            return false;
        }
        Vector3d position = resolveNpcPosition(npcRef, store, effect.getOffset());
        if (position == null) {
            return false;
        }
        Color color = effect.getColor();
        if (effect.isPlayerOnly()) {
            if (player == null) {
                return false;
            }
            Ref<EntityStore> playerRef = player.getReference();
            if (playerRef == null || !playerRef.isValid()) {
                return false;
            }
            if (color != null) {
                ParticleUtil.spawnParticleEffect(
                        particleSystem,
                        position,
                        0f,
                        0f,
                        0f,
                        1f,
                        color,
                        Collections.singletonList(playerRef),
                        store
                );
            } else {
                ParticleUtil.spawnParticleEffect(
                        particleSystem,
                        position,
                        Collections.singletonList(playerRef),
                        store
                );
            }
        } else {
            if (color != null) {
                List<Ref<EntityStore>> viewers = resolveViewerRefs(player);
                if (viewers.isEmpty()) {
                    ParticleUtil.spawnParticleEffect(particleSystem, position, store);
                } else {
                    ParticleUtil.spawnParticleEffect(
                            particleSystem,
                            position,
                            0f,
                            0f,
                            0f,
                            1f,
                            color,
                            viewers,
                            store
                    );
                }
            } else {
                ParticleUtil.spawnParticleEffect(particleSystem, position, store);
            }
        }
        return true;
    }

    private boolean applyPlaySound(PlaySoundEffect effect,
                                   Ref<EntityStore> npcRef,
                                   Store<EntityStore> store,
                                   Player player) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        String soundEvent = effect.getSoundEvent();
        if (soundEvent == null || soundEvent.isBlank()) {
            return false;
        }
        int soundIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
        if (soundIndex <= 0) {
            return false;
        }
        Vector3d position = resolveNpcPosition(npcRef, store, effect.getOffset());
        if (position == null) {
            return false;
        }
        float volume = effect.getVolume() != null ? effect.getVolume().floatValue() : 1.0f;
        float pitch = effect.getPitch() != null ? effect.getPitch().floatValue() : 1.0f;
        if (effect.isPlayerOnly()) {
            if (player == null) {
                return false;
            }
            Ref<EntityStore> playerRef = player.getReference();
            if (playerRef == null || !playerRef.isValid()) {
                return false;
            }
            SoundUtil.playSoundEvent3dToPlayer(
                    playerRef,
                    soundIndex,
                    SoundCategory.SFX,
                    position.x,
                    position.y,
                    position.z,
                    volume,
                    pitch,
                    store
            );
        } else {
            SoundUtil.playSoundEvent3d(
                    soundIndex,
                    SoundCategory.SFX,
                    position.x,
                    position.y,
                    position.z,
                    volume,
                    pitch,
                    store
            );
        }
        return true;
    }

    private boolean applyDropItem(DropItemEffect effect,
                                  Ref<EntityStore> npcRef,
                                  Store<EntityStore> store) {
        if (effect == null || npcRef == null || store == null) {
            return false;
        }
        List<ItemStack> drops = resolveDropItems(effect);
        if (drops.isEmpty()) {
            return false;
        }
        float throwSpeed = effect.getThrowSpeed() != null ? effect.getThrowSpeed().floatValue() : 0.0f;
        boolean applied = false;
        for (ItemStack stack : drops) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (throwSpeed > 0.0f) {
                ItemUtils.throwItem(npcRef, stack, throwSpeed, store);
            } else {
                ItemUtils.dropItem(npcRef, stack, store);
            }
            applied = true;
        }
        return applied;
    }

    private List<ItemStack> resolveDropItems(DropItemEffect effect) {
        List<ItemStack> drops = new ArrayList<>();
        if (effect == null) {
            return drops;
        }
        Random random = new Random();
        String dropListId = effect.getDropList();
        if (dropListId != null && !dropListId.isBlank()) {
            DefaultAssetMap<String, ItemDropList> assetMap = ItemDropList.getAssetMap();
            ItemDropList dropList = assetMap != null ? assetMap.getAssetMap().get(dropListId) : null;
            if (dropList == null) {
                owner.logDebug("DropItem effect: drop list not found: " + dropListId);
            } else {
                ItemDropContainer container = dropList.getContainer();
                if (container != null) {
                    List<ItemDrop> roll = new ArrayList<>();
                    container.populateDrops(roll, random::nextDouble, null);
                    for (ItemDrop drop : roll) {
                        if (drop == null || drop.getItemId() == null || drop.getItemId().isBlank()) {
                            continue;
                        }
                        int quantity = drop.getRandomQuantity(random);
                        if (quantity <= 0) {
                            continue;
                        }
                        if (drop.getMetadata() != null) {
                            drops.add(new ItemStack(drop.getItemId(), quantity, drop.getMetadata()));
                        } else {
                            drops.add(new ItemStack(drop.getItemId(), quantity));
                        }
                    }
                }
            }
        }
        if (!drops.isEmpty()) {
            return drops;
        }
        String itemId = effect.getItem();
        if (itemId == null || itemId.isBlank()) {
            return drops;
        }
        int quantity = resolveDropQuantity(effect, random);
        if (quantity > 0) {
            drops.add(new ItemStack(itemId, quantity));
        }
        return drops;
    }

    private int resolveDropQuantity(DropItemEffect effect, Random random) {
        int min = effect.getQuantityMin() != null ? effect.getQuantityMin() : 1;
        int max = effect.getQuantityMax() != null ? effect.getQuantityMax() : min;
        if (min < 1) {
            min = 1;
        }
        if (max < min) {
            max = min;
        }
        if (max == min) {
            return min;
        }
        return random.nextInt(max - min + 1) + min;
    }

    private Vector3d resolveNpcPosition(Ref<EntityStore> npcRef,
                                        Store<EntityStore> store,
                                        Vector3d offset) {
        if (npcRef == null || store == null) {
            return null;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        if (offset != null) {
            position.x += offset.x;
            position.y += offset.y;
            position.z += offset.z;
        }
        return position;
    }

    private List<Ref<EntityStore>> resolveViewerRefs(Player player) {
        if (player == null || player.getWorld() == null) {
            return List.of();
        }
        List<Ref<EntityStore>> refs = new ArrayList<>();
        for (PlayerRef playerRef : player.getWorld().getPlayerRefs()) {
            if (playerRef == null) {
                continue;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private boolean queueCombatText(Ref<EntityStore> npcRef,
                                    Store<EntityStore> store,
                                    Player player,
                                    String text) {
        if (npcRef == null || store == null || player == null || text == null || text.isBlank()) {
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        EntityTrackerSystems.EntityViewer viewer = store.getComponent(
                playerRef,
                EntityTrackerSystems.EntityViewer.getComponentType()
        );
        if (viewer == null) {
            return false;
        }
        ComponentUpdate update = new ComponentUpdate();
        update.type = ComponentUpdateType.CombatText;
        CombatTextUpdate combatTextUpdate = new CombatTextUpdate();
        combatTextUpdate.hitAngleDeg = 0.0f;
        combatTextUpdate.text = text;
        update.combatTextUpdate = combatTextUpdate;
        viewer.queueUpdate(npcRef, update);
        return true;
    }

    private String formatHealText(double healAmount) {
        if (healAmount <= 0) {
            return null;
        }
        double rounded = Math.round(healAmount);
        if (Math.abs(healAmount - rounded) < 0.01) {
            return "+" + (int) rounded + " HP";
        }
        return String.format(Locale.US, "+%.1f HP", healAmount);
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
