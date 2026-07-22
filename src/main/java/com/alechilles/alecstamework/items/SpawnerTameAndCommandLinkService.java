package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies the live-NPC half of a successful TameAndCommandLink capture. */
final class SpawnerTameAndCommandLinkService {
    private static final int DISABLE_SPAWN_DRIVEN_DESPAWN = Integer.MIN_VALUE;
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";

    @Nonnull
    Decision preflight(@Nullable Ref<EntityStore> targetRef,
                       @Nullable Store<EntityStore> store,
                       @Nullable String targetRoleId) {
        if (targetRef == null || !targetRef.isValid() || store == null) {
            return Decision.deny("capture-tame-link-target-unavailable");
        }
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return Decision.deny("capture-tame-link-live-role-unavailable");
        }
        NPCPlugin plugin = NPCPlugin.get();
        if (targetRoleId == null || targetRoleId.isBlank() || plugin == null
                || plugin.getIndex(targetRoleId) < 0) {
            return Decision.deny("capture-tame-link-target-role-unavailable");
        }
        if (TameworkTamedComponent.getComponentType() == null) {
            return Decision.deny("capture-tame-link-tamed-component-unavailable");
        }
        if (TameworkCommandLinksComponent.getComponentType() == null
                || TameworkProjectionIdentityComponent.getComponentType() == null) {
            return Decision.deny("capture-tame-link-lifecycle-components-unavailable");
        }
        return Decision.allow(targetRoleId.trim(), plugin.getIndex(targetRoleId));
    }

    boolean apply(@Nonnull Ref<EntityStore> targetRef,
                  @Nonnull Store<EntityStore> store,
                  @Nonnull Decision prepared,
                  @Nonnull CommandLifecycle lifecycle) {
        if (!prepared.allowed()) return false;
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                TameworkTamedComponent.getComponentType();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> identityType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (npc == null || npc.getRole() == null || tamedType == null
                || linksType == null || identityType == null) return false;

        store.putComponent(targetRef, tamedType, new TameworkTamedComponent(true));
        String rosterLinkId = "roster:" + lifecycle.ownerUuid() + ":" + lifecycle.commandFamilyId();
        store.putComponent(targetRef, linksType, new TameworkCommandLinksComponent(
                lifecycle.ownerUuid(), new String[] {rosterLinkId}));
        store.putComponent(targetRef, identityType, new TameworkProjectionIdentityComponent(
                lifecycle.profileId(), lifecycle.operationId(),
                TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                lifecycle.commandFamilyId(), lifecycle.sourceNpcUuid(), 0L));
        detachSpawnReferences(targetRef, store, npc);
        npc.setSpawnConfiguration(DISABLE_SPAWN_DRIVEN_DESPAWN);
        CompanionProgressionBootstrapService.ensureProgressionComponents(targetRef, store);
        TameworkEntityEffectService.removeEffect(targetRef, TRANQUILIZER_EFFECT_ID, store);
        RoleChangeSystem.requestRoleChange(
                targetRef, npc.getRole(), prepared.targetRoleIndex(), false, store);
        return true;
    }

    private static void detachSpawnReferences(Ref<EntityStore> targetRef,
                                              Store<EntityStore> store,
                                              NPCEntity npc) {
        ComponentType<EntityStore, SpawnMarkerReference> markerType = componentType(
                SpawnMarkerReference::getComponentType);
        if (markerType != null && store.getComponent(targetRef, markerType) != null) {
            store.tryRemoveComponent(targetRef, markerType);
        }
        ComponentType<EntityStore, SpawnBeaconReference> beaconType = componentType(
                SpawnBeaconReference::getComponentType);
        if (beaconType != null && store.getComponent(targetRef, beaconType) != null) {
            store.tryRemoveComponent(targetRef, beaconType);
        }
        npc.updateSpawnTrackingState(false);
    }

    @Nullable
    private static <T extends Component<EntityStore>> ComponentType<EntityStore, T> componentType(
            ComponentTypeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ComponentTypeSupplier<T extends Component<EntityStore>> {
        ComponentType<EntityStore, T> get();
    }

    record Decision(boolean allowed,
                    @Nullable String targetRoleId,
                    int targetRoleIndex,
                    @Nonnull String reason) {
        private static Decision allow(String roleId, int roleIndex) {
            return new Decision(true, roleId, roleIndex, "capture-tame-link-ready");
        }

        private static Decision deny(String reason) {
            return new Decision(false, null, -1, reason);
        }
    }

    record CommandLifecycle(@Nonnull String profileId,
                            @Nonnull String operationId,
                            @Nonnull UUID ownerUuid,
                            @Nonnull String commandFamilyId,
                            @Nonnull UUID sourceNpcUuid) {
        CommandLifecycle {
            profileId = requireText(profileId, "profileId");
            operationId = requireText(operationId, "operationId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            sourceNpcUuid = Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }
    }
}
