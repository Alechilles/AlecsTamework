package com.alechilles.alecstamework;

import com.alechilles.alecstamework.items.persistence.DormantCompanionEcsBridge;
import com.alechilles.alecstamework.items.persistence.DormantCompanionWorldRemovalBridge;
import com.alechilles.alecstamework.items.persistence.HytaleDormantCompanionObservationFactory;
import com.alechilles.alecstamework.items.persistence.PositiveEvidenceDormantAuthor;
import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.npc.systems.CompanionDormantDeathSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDormantRemovalSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/** Registers the positive-evidence ECS and world boundaries for dormant authoring. */
final class TameworkDormantPersistenceRegistration {
    private TameworkDormantPersistenceRegistration() {
    }

    static void register(
            @Nonnull Tamework plugin,
            @Nonnull TameworkComponentRegistrar.RegisteredComponents components,
            @Nonnull PositiveEvidenceDormantAuthor dormantAuthor
    ) {
        HytaleDormantCompanionObservationFactory observations =
                new HytaleDormantCompanionObservationFactory(
                        NPCEntity.getComponentType(),
                        components.commandLinks(),
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
                    if (completion.failure() == null
                            && completion.result() != null
                            && completion.result().published()) {
                        return;
                    }
                    var entry = plugin.getLogger().at(Level.WARNING);
                    if (completion.failure() != null) {
                        entry = entry.withCause(completion.failure());
                    }
                    entry.log(
                            "Dormant companion evidence did not publish "
                                    + "(observation="
                                    + completion.observationKey() + ")."
                    );
                }
        );
        plugin.getEntityStoreRegistry().registerSystem(
                new CompanionDormantDeathSystem(
                        bridge,
                        NPCEntity.getComponentType(),
                        components.commandLinks()
                )
        );
        plugin.getEntityStoreRegistry().registerSystem(
                new CompanionDormantRemovalSystem(
                        bridge,
                        NPCEntity.getComponentType(),
                        components.commandLinks()
                )
        );
        DormantCompanionWorldRemovalBridge worldRemoval =
                new DormantCompanionWorldRemovalBridge(
                        bridge,
                        NPCEntity.getComponentType(),
                        warning -> plugin.getLogger().at(Level.WARNING)
                                .withCause(warning.failure())
                                .log(warning.code() + " (world="
                                        + warning.worldKey() + ")")
                );
        TameworkEventRegistrationSupport.registerGlobal(
                plugin,
                Short.MAX_VALUE,
                RemoveWorldEvent.class,
                worldRemoval::onWorldRemoved,
                "delete-on-remove companion dormancy"
        );
    }
}
