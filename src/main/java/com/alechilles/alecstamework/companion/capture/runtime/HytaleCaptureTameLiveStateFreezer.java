package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture
        .CaptureTameLiveStateHasher;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.npc.components
        .TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components
        .TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components
        .TameworkTamedComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Freezes the exact Hytale authorities covered by the tame/link live-state
 * digest.
 *
 * <p>The caller must invoke this on the target's world thread and discard the
 * supplied store/reference after this method returns. The resulting value is
 * immutable and engine-neutral.</p>
 */
public final class HytaleCaptureTameLiveStateFreezer {
    private HytaleCaptureTameLiveStateFreezer() {
    }

    /**
     * Returns an exact immutable state, or {@code null} when any required
     * component authority or target identity cannot be proven.
     */
    @Nullable
    public static CaptureTameLiveStateHasher.State freeze(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store,
            @Nonnull NpcAlias expectedAlias
    ) {
        if (reference == null || !reference.isValid() || store == null
                || expectedAlias == null) {
            return null;
        }
        try {
            Types types = Types.resolve();
            if (!types.complete()) {
                return null;
            }
            UUIDComponent identity = store.getComponent(
                    reference, types.uuid()
            );
            NPCEntity npc = store.getComponent(reference, types.npc());
            if (identity == null || npc == null || npc.getRole() == null
                    || !expectedAlias.value().equals(identity.getUuid())) {
                return null;
            }
            return state(reference, store, npc, types);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static CaptureTameLiveStateHasher.State state(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            NPCEntity npc,
            Types types
    ) {
        TameworkOwnerComponent owner = store.getComponent(
                reference, types.owner()
        );
        TameworkTamedComponent tamed = store.getComponent(
                reference, types.tamed()
        );
        TameworkCommandLinksComponent links = store.getComponent(
                reference, types.links()
        );
        return new CaptureTameLiveStateHasher.State(
                roleId(npc),
                owner != null,
                ownerId(owner == null ? null : owner.getOwnerId()),
                owner == null ? null : owner.getOwnerName(),
                tamed != null,
                tamed != null && tamed.isTamed(),
                links != null,
                ownerId(links == null ? null : links.getOwnerId()),
                links == null || links.getToolIds() == null
                        ? java.util.List.of()
                        : Arrays.asList(links.getToolIds()),
                links != null && links.hasHome(),
                links != null && links.hasHome()
                        ? links.getHomeX() : 0.0D,
                links != null && links.hasHome()
                        ? links.getHomeY() : 0.0D,
                links != null && links.hasHome()
                        ? links.getHomeZ() : 0.0D,
                npc.getSpawnConfiguration(),
                npc.getEnvironment(),
                present(reference, store, types.marker()),
                present(reference, store, types.beacon())
        );
    }

    private static String roleId(NPCEntity npc) {
        String roleId = npc.getRole().getRoleName();
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalStateException(
                    "Live NPC role ID is unavailable"
            );
        }
        return roleId.trim();
    }

    @Nullable
    private static OwnerId ownerId(@Nullable java.util.UUID value) {
        return value == null ? null : new OwnerId(value);
    }

    private static <T extends Component<EntityStore>> boolean present(
            Ref<EntityStore> reference,
            Store<EntityStore> store,
            ComponentType<EntityStore, T> type
    ) {
        return store.getComponent(reference, type) != null;
    }

    @Nullable
    private static <T extends Component<EntityStore>>
    ComponentType<EntityStore, T> componentType(
            ComponentTypeSupplier<T> supplier
    ) {
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ComponentTypeSupplier<
            T extends Component<EntityStore>> {
        ComponentType<EntityStore, T> get();
    }

    private record Types(
            ComponentType<EntityStore, UUIDComponent> uuid,
            ComponentType<EntityStore, NPCEntity> npc,
            ComponentType<EntityStore, TameworkOwnerComponent> owner,
            ComponentType<EntityStore, TameworkTamedComponent> tamed,
            ComponentType<EntityStore, TameworkCommandLinksComponent> links,
            ComponentType<EntityStore, SpawnMarkerReference> marker,
            ComponentType<EntityStore, SpawnBeaconReference> beacon
    ) {
        private static Types resolve() {
            return new Types(
                    componentType(UUIDComponent::getComponentType),
                    componentType(NPCEntity::getComponentType),
                    componentType(TameworkOwnerComponent::getComponentType),
                    componentType(TameworkTamedComponent::getComponentType),
                    componentType(
                            TameworkCommandLinksComponent::getComponentType
                    ),
                    componentType(SpawnMarkerReference::getComponentType),
                    componentType(SpawnBeaconReference::getComponentType)
            );
        }

        private boolean complete() {
            return uuid != null && npc != null && owner != null
                    && tamed != null && links != null && marker != null
                    && beacon != null;
        }
    }
}
