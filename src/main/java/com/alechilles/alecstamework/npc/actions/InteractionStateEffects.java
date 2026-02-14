package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModifyStatsEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.OwnerSource;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetStateEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetTamedEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.StatDelta;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.UUID;

/** Applies interaction effects that change NPC ownership, stats, or states. */
final class InteractionStateEffects {
    private static final String HEALTH_STAT_ID = "Health";

    // Marks the NPC as tamed and assigns owner based on the interacting player.
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
        return true;
    }

    // Sets the owner component based on the configured source.
    boolean applySetOwner(SetOwnerEffect effect, Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
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
        return true;
    }
}
