package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.damage.ExpiryDismountFallProtectionService;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Arms fall protection only for a verified mounted lease-expiry removal. */
final class ExpiryDismountFallProtectionArmer {
    private ExpiryDismountFallProtectionArmer() {
    }

    static void arm(
            World world,
            Store<EntityStore> store,
            Ref<EntityStore> companionRef,
            BondedCompanionProjectionCleanupService.CleanupIntent intent
    ) {
        if (!"LEASE_EXPIRED".equals(intent.reason())) return;
        UUID riderUuid = riderUuid(store, companionRef);
        if (riderUuid == null) return;
        Ref<EntityStore> riderRef = world.getEntityRef(riderUuid);
        if (riderRef == null || !riderRef.isValid()
                || store.getComponent(riderRef, Player.getComponentType()) == null) {
            return;
        }
        ExpiryDismountFallProtectionService.getInstance().arm(
                riderUuid, System.currentTimeMillis());
    }

    @Nullable
    private static UUID riderUuid(
            Store<EntityStore> store, Ref<EntityStore> companionRef
    ) {
        String rawUuid = null;
        ComponentType<EntityStore, TameworkRideMountComponent> rideMountType =
                TameworkRideMountComponent.getComponentType();
        if (rideMountType != null) {
            TameworkRideMountComponent rideMount = store.getComponent(
                    companionRef, rideMountType);
            rawUuid = rideMount == null ? null : rideMount.getRiderUuid();
        }
        if (rawUuid == null || rawUuid.isBlank()) {
            ComponentType<EntityStore, TameworkMountedGlideComponent>
                    mountedGlideType = TameworkMountedGlideComponent
                            .getComponentType();
            if (mountedGlideType != null) {
                TameworkMountedGlideComponent mountedGlide = store.getComponent(
                        companionRef, mountedGlideType);
                rawUuid = mountedGlide == null
                        ? null : mountedGlide.getRiderUuid();
            }
        }
        if (rawUuid == null || rawUuid.isBlank()) return null;
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
