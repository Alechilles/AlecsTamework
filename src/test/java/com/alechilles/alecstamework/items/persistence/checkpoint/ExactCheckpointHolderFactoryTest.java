package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.List;
import java.util.UUID;
import org.joml.Vector3d;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Guards exact state retention when a checkpoint holder is relocated. */
class ExactCheckpointHolderFactoryTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "30000000-0000-0000-0000-000000000001"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID CURRENT_TOOL = UUID.fromString(
            "40000000-0000-0000-0000-000000000001"
    );

    @Test
    void preservesUnrelatedNpcStateWhileReplacingOnlyTransientPlacement() {
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        ComponentType<EntityStore, UUIDComponent> uuidType =
                registry.registerComponent(
                        UUIDComponent.class,
                        () -> new UUIDComponent(ALIAS.value())
                );
        ComponentType<EntityStore, NPCEntity> npcType =
                registry.registerComponent(NPCEntity.class, NPCEntity::new);
        ComponentType<EntityStore, TransformComponent> transformType =
                registry.registerComponent(
                        TransformComponent.class, TransformComponent::new
                );
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                registry.registerComponent(
                        TameworkOwnerComponent.class,
                        TameworkOwnerComponent::new
                );
        ComponentType<EntityStore, TameworkTamedComponent> tamedType =
                registry.registerComponent(
                        TameworkTamedComponent.class,
                        TameworkTamedComponent::new
                );
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                registry.registerComponent(
                        TameworkCommandLinksComponent.class,
                        TameworkCommandLinksComponent::new
                );
        ComponentType<EntityStore, TestState> appearanceType =
                registry.registerComponent(TestState.class, TestState::new);
        ComponentType<EntityStore, TransientState> transientType =
                registry.registerComponent(
                        TransientState.class, TransientState::new
                );

        Holder<EntityStore> holder = registry.newHolder();
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(ALIAS.value());
        npc.setRoleName("Cat_Pet");
        npc.setToDespawn();
        TestState appearance = new TestState("brown-tuxedo");
        holder.addComponent(uuidType, new UUIDComponent(ALIAS.value()));
        holder.addComponent(npcType, npc);
        holder.addComponent(
                transformType,
                new TransformComponent(
                        new org.joml.Vector3d(1, 2, 3), new Rotation3f()
                )
        );
        holder.addComponent(
                ownerType,
                new TameworkOwnerComponent(OWNER.value(), "Owner")
        );
        holder.addComponent(tamedType, new TameworkTamedComponent(true));
        holder.addComponent(
                linksType,
                new TameworkCommandLinksComponent(
                        OWNER.value(),
                        new String[] {
                                "50000000-0000-0000-0000-000000000001"
                        },
                        new Vector3d(4, 5, 6)
                )
        );
        holder.addComponent(appearanceType, appearance);
        holder.addComponent(transientType, new TransientState());

        ExactCheckpointHolderFactory factory =
                new ExactCheckpointHolderFactory(
                        ignored -> holder,
                        new ExactCheckpointHolderFactory.ComponentTypes(
                                uuidType,
                                npcType,
                                transformType,
                                ownerType,
                                tamedType,
                                linksType,
                                List.of(transientType)
                        )
                );
        Holder<EntityStore> restored = factory.prepare(plan());

        assertSame(appearance, restored.getComponent(appearanceType));
        assertNull(restored.getComponent(transientType));
        assertEquals(
                new org.joml.Vector3d(10, 20, 30),
                restored.getComponent(transformType).getPosition()
        );
        assertEquals(ALIAS.value(), restored.getComponent(uuidType).getUuid());
        assertFalse(restored.getComponent(npcType).isDespawning());
        TameworkCommandLinksComponent links = restored.getComponent(
                linksType
        );
        assertEquals(
                List.of(CURRENT_TOOL.toString()),
                List.of(links.getToolIds())
        );
        assertEquals(new Vector3d(4, 5, 6), links.getHomePosition());
    }

    private ExactCheckpointRecallRecoveryAuthor.RecoveryPlan plan() {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE, "Cat", "Cat_Pet", null, null,
                "world-a", -10_000, -9_000, -9_000, 0
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS, PROFILE, 7, CompanionAlias.State.CURRENT,
                null, -9_000, null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OWNER,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        ALIAS.toString(), "world-a"
                ),
                new LifecycleRevision(9),
                null,
                -8_000,
                new ReconciliationGeneration(4),
                null,
                "world-a"
        );
        CompanionProfileReadModel profile = new CompanionProfileReadModel(
                identity,
                alias,
                lifecycle,
                List.of(new CompanionToolLink(
                        PROFILE, CURRENT_TOOL, "COMMAND_ITEM", -9_000, -8_000
                )),
                List.of(),
                null
        );
        CompanionEntityCheckpoint checkpoint =
                CompanionEntityCheckpoint.create(
                        PROFILE,
                        ALIAS,
                        7,
                        OWNER,
                        new LifecycleRevision(9),
                        new ReconciliationGeneration(4),
                        "world-a",
                        1,
                        2,
                        3,
                        CompanionEntityCheckpoint.CaptureBoundary.UNLOAD,
                        -7_000,
                        new BsonDocument(),
                        new CompanionEntityCheckpointCodec()
                );
        return new ExactCheckpointRecallRecoveryAuthor.RecoveryPlan(
                profile,
                checkpoint,
                new ImportedRecallRecoverySink.RecallDestination(
                        "world-b", 10, 20, 30
                ),
                new ImportedRecallRecoverySink.RecallSourceSection(
                        "world-a", 0, 0, 0
                ),
                false
        );
    }

    private static final class TestState implements Component<EntityStore> {
        private final String value;

        private TestState() {
            this("");
        }

        private TestState(String value) {
            this.value = value;
        }

        @Override
        public Component<EntityStore> clone() {
            return new TestState(value);
        }
    }

    private static final class TransientState
            implements Component<EntityStore> {
        @Override
        public Component<EntityStore> clone() {
            return new TransientState();
        }
    }
}
