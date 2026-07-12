package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Attempts queued command relocation requests when NPC entities are added back into the world store.
 */
public final class CommandNpcRelocationOnLoadSystem extends RefSystem<EntityStore> {
    @Nullable
    private static final Field STATE_INTERACTABLE_PLAYERS_FIELD = resolveStateSupportField("interactablePlayers");
    @Nullable
    private static final Field STATE_INTERACTED_PLAYERS_FIELD = resolveStateSupportField("interactedPlayers");
    @Nullable
    private static final Field STATE_CONTEXTUAL_INTERACTIONS_FIELD = resolveStateSupportField("contextualInteractions");

    private final CommandNpcRelocationService relocationService;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandLinkedNpcLostService lostService;
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;

    public CommandNpcRelocationOnLoadSystem(CommandNpcRelocationService relocationService,
                                            CommandLinkedNpcDeathService deathService,
                                            CommandLinkedNpcLostService lostService,
                                            CommandLinkedNpcStateSnapshotService stateSnapshotService) {
        this.relocationService = relocationService;
        this.deathService = deathService;
        this.lostService = lostService;
        this.stateSnapshotService = stateSnapshotService;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        sanitizeRoleReferencesOnAdd(reference, store);
        if (stateSnapshotService != null) {
            stateSnapshotService.onNpcAdded(reference, store);
        }
        if (relocationService == null) {
            if (deathService != null) {
                deathService.onNpcAdded(reference, store);
            }
            if (lostService != null) {
                lostService.onNpcAdded(reference, store);
            }
            return;
        }
        relocationService.onNpcAdded(reference, store);
        if (deathService != null) {
            deathService.onNpcAdded(reference, store);
        }
        if (lostService != null) {
            lostService.onNpcAdded(reference, store);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (stateSnapshotService != null) {
            stateSnapshotService.onNpcRemoved(reference, reason, store);
        }
        if (relocationService != null) {
            relocationService.onNpcRemoved(reference, reason, store);
        }
        if (deathService != null) {
            deathService.onNpcRemoved(reference, reason, store);
        }
        if (lostService != null) {
            lostService.onNpcRemoved(reference, reason, store);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    private void sanitizeRoleReferencesOnAdd(@Nonnull Ref<EntityStore> reference, @Nonnull Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        sanitizeMarkedEntitySupport(role, store);
        sanitizeStateSupport(role, store);
    }

    private void sanitizeMarkedEntitySupport(@Nonnull Role role, @Nonnull Store<EntityStore> store) {
        MarkedEntitySupport markedEntitySupport = role.getMarkedEntitySupport();
        if (markedEntitySupport == null) {
            return;
        }
        Ref<EntityStore>[] targets = markedEntitySupport.getEntityTargets();
        if (targets == null || targets.length == 0) {
            return;
        }
        for (int slot = 0; slot < targets.length; slot++) {
            Ref<EntityStore> target = targets[slot];
            if (isRefInCurrentStore(target, store)) {
                continue;
            }
            markedEntitySupport.setMarkedEntity(slot, null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sanitizeStateSupport(@Nonnull Role role, @Nonnull Store<EntityStore> store) {
        StateSupport stateSupport = role.getStateSupport();
        if (stateSupport == null) {
            return;
        }
        Ref<EntityStore> iterationTarget = stateSupport.getInteractionIterationTarget();
        if (!isRefInCurrentStore(iterationTarget, store)) {
            stateSupport.setInteractionIterationTarget(null);
        }

        Collection<Ref<EntityStore>> interactablePlayers =
                readFieldValue(stateSupport, STATE_INTERACTABLE_PLAYERS_FIELD, Collection.class);
        pruneRefCollection(interactablePlayers, store);

        Collection<Ref<EntityStore>> interactedPlayers =
                readFieldValue(stateSupport, STATE_INTERACTED_PLAYERS_FIELD, Collection.class);
        pruneRefCollection(interactedPlayers, store);

        Map<Ref<EntityStore>, String> contextualInteractions =
                readFieldValue(stateSupport, STATE_CONTEXTUAL_INTERACTIONS_FIELD, Map.class);
        if (contextualInteractions != null && !contextualInteractions.isEmpty()) {
            Iterator<Map.Entry<Ref<EntityStore>, String>> iterator = contextualInteractions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Ref<EntityStore>, String> entry = iterator.next();
                if (!isRefInCurrentStore(entry.getKey(), store)) {
                    iterator.remove();
                }
            }
        }
    }

    private void pruneRefCollection(@Nullable Collection<Ref<EntityStore>> refs, @Nonnull Store<EntityStore> store) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        Iterator<Ref<EntityStore>> iterator = refs.iterator();
        while (iterator.hasNext()) {
            Ref<EntityStore> candidate = iterator.next();
            if (!isRefInCurrentStore(candidate, store)) {
                iterator.remove();
            }
        }
    }

    private boolean isRefInCurrentStore(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return ref != null && ref.isValid() && ref.getStore() == store;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T readFieldValue(@Nonnull Object owner, @Nullable Field field, @Nonnull Class<T> type) {
        if (owner == null || field == null || type == null) {
            return null;
        }
        try {
            Object value = field.get(owner);
            if (type.isInstance(value)) {
                return (T) value;
            }
        } catch (IllegalAccessException ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private static Field resolveStateSupportField(@Nonnull String name) {
        try {
            Field field = StateSupport.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }
}
