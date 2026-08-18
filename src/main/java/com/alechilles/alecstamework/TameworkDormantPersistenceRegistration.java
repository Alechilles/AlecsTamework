package com.alechilles.alecstamework;

import com.alechilles.alecstamework.items.persistence.DormantCompanionEcsBridge;
import com.alechilles.alecstamework.items.persistence.DormantCompanionWorldRemovalBridge;
import com.alechilles.alecstamework.items.persistence.HytaleDormantCompanionObservationFactory;
import com.alechilles.alecstamework.items.persistence.PositiveEvidenceDormantAuthor;
import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.npc.systems.CompanionDormantDeathSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDormantRemovalSystem;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.TameworkRuntimeParticipantRegistry;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.logging.Level;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Registers the positive-evidence ECS and world boundaries for dormant authoring. */
final class TameworkDormantPersistenceRegistration {
    private TameworkDormantPersistenceRegistration() {
    }

    /** Registers dormant evidence boundaries only for an active plan. */
    static void registerIfActive(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull PositiveEvidenceDormantAuthor dormantAuthor,
            @Nonnull TameworkRuntimeActivationPlan activationPlan
    ) {
        if (activationPlan.isActive(
                TameworkRuntimeModule.DORMANT_PERSISTENCE)) {
            register(plugin, components, dormantAuthor);
        }
    }

    /** Declares dormant evidence boundaries for registrar preflight. */
    static void declareIfActive(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull PositiveEvidenceDormantAuthor dormantAuthor,
            @Nonnull TameworkRuntimeActivationPlan activationPlan,
            @Nonnull TameworkRuntimeParticipantRegistry participants
    ) {
        if (!activationPlan.isActive(TameworkRuntimeModule.DORMANT_PERSISTENCE)) {
            return;
        }
        build(plugin, components, dormantAuthor, new BoundaryRegistrar() {
            @Override
            public void entitySystem(String id, Supplier<?> factory) {
                participants.entitySystem(
                        TameworkRuntimeModule.DORMANT_PERSISTENCE, id, factory);
            }

            @Override
            public void listener(String id, Runnable registration) {
                participants.listener(
                        TameworkRuntimeModule.DORMANT_PERSISTENCE, id, registration);
            }
        });
    }

    static void register(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull PositiveEvidenceDormantAuthor dormantAuthor
    ) {
        build(plugin, components, dormantAuthor, new BoundaryRegistrar() {
            @Override
            public void entitySystem(String id, Supplier<?> factory) {
                plugin.getEntityStoreRegistry().registerSystem(
                        (com.hypixel.hytale.component.system.ISystem) factory.get());
            }

            @Override
            public void listener(String id, Runnable registration) {
                registration.run();
            }
        });
    }

    private static void build(
            Tamework plugin,
            TameworkComponentRegistrar.RegisteredComponents components,
            PositiveEvidenceDormantAuthor dormantAuthor,
            BoundaryRegistrar registrar
    ) {
        HytaleDormantCompanionObservationFactory observations =
                new HytaleDormantCompanionObservationFactory(
                        NPCEntity.getComponentType(),
                        components.projectionIdentity(),
                        components.persistenceRetirement(),
                        DeathComponent.getComponentType(),
                        TransformComponent.getComponentType(),
                        System::currentTimeMillis
                );
        DormantCompanionEcsBridge bridge = new DormantCompanionEcsBridge(
                observations,
                dormantAuthor,
                completion -> {
                    var result = completion.result();
                    if (completion.failure() == null
                            && result != null
                            && result.published()) {
                        return;
                    }
                    var entry = plugin.getLogger().at(Level.WARNING);
                    Throwable failure = completion.failure() != null
                            ? completion.failure()
                            : result == null ? null : result.failure();
                    if (failure != null) {
                        entry = entry.withCause(failure);
                    }
                    entry.log(
                            "Dormant companion evidence did not publish "
                                    + "(observation="
                                    + completion.observationKey()
                                    + ", status="
                                    + (result == null ? "<none>" : result.status())
                                    + ", workflowStatus="
                                    + (result == null
                                    ? "<none>" : result.workflowStatus())
                                    + ", detail="
                                    + (result == null ? "<none>" : result.detail())
                                    + ", operation="
                                    + (result == null
                                    ? "<none>" : result.operationId())
                                    + ")."
                    );
                }
        );
        registrar.entitySystem("dormant-companion-death", () ->
                new CompanionDormantDeathSystem(
                        bridge,
                        NPCEntity.getComponentType(),
                        components.commandLinks()
                ));
        registrar.entitySystem("dormant-companion-removal", () ->
                new CompanionDormantRemovalSystem(
                        bridge,
                        NPCEntity.getComponentType(),
                        components.commandLinks()
                ));
        DormantCompanionWorldRemovalBridge worldRemoval =
                new DormantCompanionWorldRemovalBridge(
                        bridge,
                        NPCEntity.getComponentType(),
                        warning -> plugin.getLogger().at(Level.WARNING)
                                .withCause(warning.failure())
                                .log(warning.code() + " (world="
                                        + warning.worldKey() + ")")
                );
        registrar.listener("dormant-companion-world-removal", () ->
                TameworkEventRegistrationSupport.registerGlobal(
                        plugin,
                        Short.MAX_VALUE,
                        RemoveWorldEvent.class,
                        worldRemoval::onWorldRemoved,
                        "delete-on-remove companion dormancy"
                ));
    }

    private interface BoundaryRegistrar {
        void entitySystem(String id, Supplier<?> factory);

        void listener(String id, Runnable registration);
    }
}
