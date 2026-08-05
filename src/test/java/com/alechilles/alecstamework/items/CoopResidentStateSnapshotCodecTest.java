package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopResidentStateSnapshotCodecTest {
    private final CoopResidentStateSnapshotCodec codec = new CoopResidentStateSnapshotCodec();

    @Test
    void fullSnapshotRoundTripPreservesVersionOneShapeAndSignedTimestamps() {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot source = fullSnapshot();

        String encoded = codec.encode(source);
        CoopResidentStateSnapshotCodec.DecodeResult result = codec.decode(encoded);

        assertTrue(encoded.startsWith("{\"version\":\"1\",\"npcUuid\":"));
        assertEquals(CoopResidentStateSnapshotCodec.Status.FOUND, result.status());
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot decoded = result.snapshot();
        assertNotNull(decoded);
        assertEquals(source.npcUuid(), decoded.npcUuid());
        assertEquals("coop_chicken", decoded.coopId());
        assertEquals("Tamed_Chicken", decoded.roleId());
        assertEquals(-9_001L, decoded.capturedAtMs());
        assertEquals(-101L, decoded.happiness().getLastUpdateMs());
        assertEquals(-102L, decoded.needs().getLastUpdateMs());
        assertEquals(-103L, decoded.needs().getLastPassiveSweepMs());
        assertEquals(-104L, decoded.breeding().getCooldownUntilMs());
        assertEquals(-105L, decoded.breeding().getCooldownStartedAtMs());
        assertEquals(-106L, decoded.lifeStage().getBornAtMs());
        assertEquals(-107L, decoded.lifeStage().getAdultAtMs());
        assertEquals("crest", decoded.attachments().getAttachmentIds().get("head"));
        assertEquals(37.5, decoded.healthPercent());
    }

    @Test
    void distinguishesMissingMalformedUnsupportedAndInvalidComponentPayloads() {
        assertEquals(CoopResidentStateSnapshotCodec.Status.NOT_FOUND, codec.decode(null).status());
        assertEquals(CoopResidentStateSnapshotCodec.Status.NOT_FOUND, codec.decode("  ").status());

        CoopResidentStateSnapshotCodec.DecodeResult malformed = codec.decode("{");
        assertEquals(CoopResidentStateSnapshotCodec.Status.FAILED, malformed.status());
        assertEquals(CoopResidentStateSnapshotCodec.Failure.INVALID_JSON, malformed.failure());

        CoopResidentStateSnapshotCodec.DecodeResult unsupported = codec.decode(
                "{\"version\":\"2\",\"npcUuid\":\"" + UUID.randomUUID() + "\"}"
        );
        assertEquals(CoopResidentStateSnapshotCodec.Failure.UNSUPPORTED_VERSION, unsupported.failure());
        assertEquals("version", unsupported.field());

        CoopResidentStateSnapshotCodec.DecodeResult missingUuid = codec.decode("{\"version\":\"1\"}");
        assertEquals(CoopResidentStateSnapshotCodec.Failure.MISSING_SOURCE_UUID, missingUuid.failure());

        CoopResidentStateSnapshotCodec.DecodeResult invalidUuid = codec.decode(
                "{\"version\":\"1\",\"npcUuid\":\"not-a-uuid\"}"
        );
        assertEquals(CoopResidentStateSnapshotCodec.Failure.INVALID_SOURCE_UUID, invalidUuid.failure());

        CoopResidentStateSnapshotCodec.DecodeResult invalidComponent = codec.decode(
                "{\"version\":\"1\",\"npcUuid\":\"" + UUID.randomUUID() + "\",\"owner\":[]}"
        );
        assertEquals(CoopResidentStateSnapshotCodec.Failure.INVALID_COMPONENT_DATA, invalidComponent.failure());
        assertEquals("owner", invalidComponent.field());
        assertNull(invalidComponent.snapshot());

        assertInvalidScalar("{\"version\":1,\"npcUuid\":\"" + UUID.randomUUID() + "\"}", "version");
        assertInvalidScalar("{\"npcUuid\":99}", "npcUuid");
        assertInvalidScalar(
                "{\"npcUuid\":\"" + UUID.randomUUID() + "\",\"residentSlot\":\"2\"}",
                "residentSlot"
        );
        assertInvalidScalar(
                "{\"npcUuid\":\"" + UUID.randomUUID() + "\",\"capturedAtMs\":false}",
                "capturedAtMs"
        );
        assertInvalidScalar(
                "{\"npcUuid\":\"" + UUID.randomUUID() + "\",\"healthPercent\":\"37.5\"}",
                "healthPercent"
        );
    }

    @Test
    void acceptsLegacyVersionlessPayload() {
        UUID sourceUuid = UUID.randomUUID();

        CoopResidentStateSnapshotCodec.DecodeResult result = codec.decode(
                "{\"npcUuid\":\"" + sourceUuid + "\",\"capturedAtMs\":-44}"
        );

        assertEquals(CoopResidentStateSnapshotCodec.Status.FOUND, result.status());
        assertEquals(sourceUuid, result.snapshot().npcUuid());
        assertEquals(-44L, result.snapshot().capturedAtMs());
    }

    @Test
    void decodedSnapshotDoesNotShareMutableComponentStateWithSource() {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot source = fullSnapshot();
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot decoded =
                codec.decode(codec.encode(source)).snapshot();

        assertNotNull(decoded);
        assertNotSame(source.commandLinks(), decoded.commandLinks());
        assertNotSame(source.needs(), decoded.needs());
        decoded.commandLinks().setToolIds(new String[] {"changed"});
        decoded.needs().setLastUpdateMs(77L);

        assertEquals("tool-alpha", source.commandLinks().getToolIds()[0]);
        assertEquals(-102L, source.needs().getLastUpdateMs());
    }

    @Test
    void preAddPlanWritesEveryComponentAndKeepsLiveEffectsDeferred() {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot source = fullSnapshot();
        TameworkProjectionIdentityComponent marker = new TameworkProjectionIdentityComponent(
                "profile-1",
                "operation-1",
                TameworkProjectionIdentityComponent.KIND_RECOVERY,
                null,
                source.npcUuid(),
                4L
        );
        EnumMap<CoopResidentStateRestorer.ComponentSlot, Component<EntityStore>> written =
                new EnumMap<>(CoopResidentStateRestorer.ComponentSlot.class);

        CoopResidentStateRestorer.PostAddWork postAddWork = new CoopResidentStateRestorer()
                .restore(written::put, source, marker);

        assertEquals(13, written.size());
        for (CoopResidentStateRestorer.ComponentSlot slot : CoopResidentStateRestorer.ComponentSlot.values()) {
            assertTrue(written.containsKey(slot), "missing pre-add component " + slot);
        }
        assertNotSame(source.commandLinks(), written.get(CoopResidentStateRestorer.ComponentSlot.COMMAND_LINKS));
        assertNotSame(marker, written.get(CoopResidentStateRestorer.ComponentSlot.PROJECTION_IDENTITY));
        assertEquals("Clucky", postAddWork.displayName());
        assertTrue(postAddWork.hasDisplayNameWork());
        assertTrue(postAddWork.hasHealthWork());
        assertTrue(postAddWork.hasAttachmentWork());
        assertNotSame(source.attachments(), postAddWork.attachments());
        TameworkAttachmentsComponent writtenAttachments = (TameworkAttachmentsComponent) written.get(
                CoopResidentStateRestorer.ComponentSlot.ATTACHMENTS
        );
        assertNotSame(writtenAttachments.getAttachmentIds(), postAddWork.attachments().getAttachmentIds());

        ((TameworkCommandLinksComponent) written.get(CoopResidentStateRestorer.ComponentSlot.COMMAND_LINKS))
                .setToolIds(new String[] {"changed-after-plan"});
        assertEquals("tool-alpha", source.commandLinks().getToolIds()[0]);
        assertFalse(postAddWork.attachments().getAttachmentIds().isEmpty());
    }

    @Test
    void projectionPostAddWorkAssignsTheRestoredOwnerAsMasterTarget() {
        UUID ownerUuid = UUID.randomUUID();
        ComponentRegistry<EntityStore> registry = new ComponentRegistry<>();
        Store<EntityStore> store = registry.addStore(null, null);
        try {
            ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                    registry.registerComponent(
                            TameworkOwnerComponent.class,
                            TameworkOwnerComponent::new
                    );
            Holder<EntityStore> npc = registry.newHolder();
            npc.addComponent(ownerType, new TameworkOwnerComponent(ownerUuid, "Owner"));
            Ref<EntityStore> npcRef = store.addEntity(npc, AddReason.SPAWN);
            Ref<EntityStore> ownerRef = store.addEntity(
                    registry.newHolder(), AddReason.SPAWN);
            AtomicReference<UUID> resolvedOwner = new AtomicReference<>();
            AtomicReference<Ref<EntityStore>> assignedTarget = new AtomicReference<>();

            boolean assigned = PlannedNpcProjectionPostAddService.assignOwnerTarget(
                    npcRef,
                    store,
                    ownerType,
                    requestedOwner -> {
                        resolvedOwner.set(requestedOwner);
                        return ownerRef;
                    },
                    assignedTarget::set
            );

            assertTrue(assigned);
            assertEquals(ownerUuid, resolvedOwner.get());
            assertSame(ownerRef, assignedTarget.get());
        } finally {
            registry.removeStore(store);
            registry.shutdown();
        }
    }

    private void assertInvalidScalar(String raw, String expectedField) {
        CoopResidentStateSnapshotCodec.DecodeResult result = codec.decode(raw);
        assertEquals(CoopResidentStateSnapshotCodec.Status.FAILED, result.status());
        assertEquals(CoopResidentStateSnapshotCodec.Failure.INVALID_FIELD, result.failure());
        assertEquals(expectedField, result.field());
    }

    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        TameworkLifeStageComponent lifeStage = new TameworkLifeStageComponent();
        lifeStage.setStage("Adult");
        lifeStage.setBornAtMs(-106L);
        lifeStage.setAdultAtMs(-107L);
        lifeStage.setFullyGrownAtMs(-108L);
        TameworkHappinessComponent.ActiveImpulse impulse = new TameworkHappinessComponent.ActiveImpulse();
        impulse.setKey("fed");
        impulse.setValue(0.25);
        impulse.setExpiresAtMs(-109L);
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                npcUuid,
                "Coop_Chicken",
                2,
                "Tamed_Chicken",
                new TameworkCommandLinksComponent(ownerUuid, new String[] {"tool-alpha"}),
                new TameworkOwnerComponent(ownerUuid, "Owner"),
                new TameworkTamedComponent(true),
                new TameworkNpcNameComponent(
                        "Clucky",
                        ownerUuid,
                        -100L,
                        TameworkNpcNameComponent.NameSource.Player
                ),
                new TameworkHappinessComponent("happy", 0.75, -101L, new TameworkHappinessComponent.ActiveImpulse[] {impulse}),
                new TameworkNeedsComponent("needs", 0.2, 0.3, 0.1, 0.0, -102L, -103L),
                new TameworkBreedingComponent("breed", 0.8, -110L, true, true, -104L, null, -105L, 4_000L),
                new TameworkLevelingComponent("level", 4, 20.0, 50.0, 111L),
                new TameworkTraitsComponent(
                        "traits",
                        112L,
                        new TameworkTraitsComponent.TraitValue[] {
                            new TameworkTraitsComponent.TraitValue("friendly", 1.0)
                        }
                ),
                new TameworkTalentsComponent("talents", 1, new String[] {"swift"}),
                lifeStage,
                new TameworkAttachmentsComponent("attachments", Map.of("head", "crest")),
                37.5,
                -9_001L
        );
    }
}
