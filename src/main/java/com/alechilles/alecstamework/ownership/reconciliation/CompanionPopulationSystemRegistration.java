package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.systems.CompanionPermanentDeathDamageGateSystem;
import com.alechilles.alecstamework.npc.systems.CompanionPermanentDeathFallbackSystem;
import com.alechilles.alecstamework.npc.systems.CompanionPermanentDeathRetentionSystem;
import com.alechilles.alecstamework.ownership.CompanionPermanentDeathCoordinator;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Registers the focused ECS observers that project live companion population truth. */
public final class CompanionPopulationSystemRegistration {
    private CompanionPopulationSystemRegistration() {
    }

    public static void register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry,
            @Nonnull OwnerPopulationRuntime runtime,
            @Nonnull CommandLinkedNpcCaptureService captureService,
            @Nonnull CommandLinkedNpcCoopService coopService,
            @Nonnull CommandLinkedNpcDeathService deathService,
            @Nonnull CommandLinkedNpcLostService lostService,
            @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
            @Nonnull CompanionPopulationRuntimeReconciler.WarningSink warningSink
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(captureService, "captureService");
        Objects.requireNonNull(coopService, "coopService");
        Objects.requireNonNull(deathService, "deathService");
        Objects.requireNonNull(lostService, "lostService");
        Objects.requireNonNull(ownerType, "ownerType");
        runtime.runtimeReconciler().setWarningSink(warningSink);
        CompanionPermanentDeathCoordinator permanentDeath =
                new CompanionPermanentDeathCoordinator(runtime.mutationScheduler());
        permanentDeath.setWarningSink(warningSink::warn);

        CompanionRemovalLifecycleClassifier classifier = new CompanionRemovalLifecycleClassifier(
                npcUuid -> captureService.getCapturedSnapshot(npcUuid) != null,
                npcUuid -> coopService.getCoopSnapshot(npcUuid) != null,
                npcUuid -> deathService.getDeadSnapshot(npcUuid) != null,
                lostService::isLost,
                deathService::isPermanentlyReleasedDeath
        );
        registry.registerSystem(new CompanionPopulationLifecycleSystem(
                runtime.runtimeReconciler(),
                runtime.index(),
                runtime.identityResolver(),
                classifier,
                ownerType,
                NPCEntity.getComponentType(),
                UUIDComponent.getComponentType(),
                TransformComponent.getComponentType()
        ));
        registry.registerSystem(new CompanionPermanentDeathDamageGateSystem(
                permanentDeath,
                ownerType
        ));
        registry.registerSystem(new CompanionPermanentDeathFallbackSystem(
                permanentDeath,
                ownerType
        ));
        registry.registerSystem(new CompanionPermanentDeathRetentionSystem(
                permanentDeath,
                runtime.index(),
                runtime.identityResolver(),
                ownerType
        ));
        registry.registerSystem(new CompanionOwnerComponentReconciliationSystem(
                runtime.runtimeReconciler(),
                runtime.mutationService(),
                ownerType,
                UUIDComponent.getComponentType(),
                TransformComponent.getComponentType()
        ));
        registry.registerSystem(new CompanionPhysicalLocationSystem(
                runtime.runtimeReconciler(),
                ownerType,
                UUIDComponent.getComponentType(),
                TransformComponent.getComponentType()
        ));
    }
}
