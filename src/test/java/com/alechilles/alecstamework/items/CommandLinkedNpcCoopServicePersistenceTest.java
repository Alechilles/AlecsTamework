package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcCoopServicePersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsStateSnapshotJson() throws Exception {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        long capturedAtMs = 123_456L;
        CommandLinkedNpcCoopService.CoopSlotContext slot = CommandLinkedNpcCoopService.CoopSlotContext.of(
                "default",
                "Coop_Chicken",
                12,
                70,
                12,
                2
        );

        TameworkLifeStageComponent lifeStage = new TameworkLifeStageComponent();
        lifeStage.setStage("Adult");
        lifeStage.setBornAtMs(1L);
        lifeStage.setAdultAtMs(2L);
        lifeStage.setGrowthScalingEnabled(true);

        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        npcUuid,
                        "coop_chicken",
                        2,
                        "tamed_chicken",
                        new TameworkCommandLinksComponent(ownerId, new String[] {"tool-alpha"}),
                        new TameworkOwnerComponent(ownerId, "Owner"),
                        new TameworkTamedComponent(true),
                        new TameworkNpcNameComponent(
                                "Clucky",
                                ownerId,
                                99L,
                                TameworkNpcNameComponent.NameSource.Player
                        ),
                        new TameworkHappinessComponent("happy_cfg", 0.75, 100L),
                        new TameworkNeedsComponent("needs_cfg", 0.2, 0.1, 0.0, 101L, 102L),
                        new TameworkBreedingComponent("breed_cfg", 0.5, 103L, true, true, 104L, null, 105L, 106L),
                        new TameworkTraitsComponent(
                                "traits_cfg",
                                107L,
                                new TameworkTraitsComponent.TraitValue[] {
                                        new TameworkTraitsComponent.TraitValue("friendly", 1.0)
                                }
                        ),
                        lifeStage,
                        new TameworkAttachmentsComponent("attach_cfg", Map.of("head", "crest")),
                        capturedAtMs
                );

        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService(
                    runtime.getCoopLedgerRepository(),
                    runtime.getHealthService(),
                    runtime.getNpcProfileRepository()
            );
            service.captureResident(
                    npcUuid,
                    "tamed_chicken",
                    slot,
                    ownerId,
                    new String[] {"tool-alpha"},
                    "Clucky",
                    snapshot
            );

            assertTrue(runtime.awaitWriteQueueIdle(5_000L));

            CommandLinkedNpcCoopService reloaded = new CommandLinkedNpcCoopService(
                    runtime.getCoopLedgerRepository(),
                    runtime.getHealthService(),
                    runtime.getNpcProfileRepository()
            );
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot persisted = reloaded.getLedgerSlotSnapshot(slot);
            assertNotNull(persisted);
            assertNotNull(persisted.stateSnapshot());
            assertEquals(npcUuid, persisted.stateSnapshot().npcUuid());
            assertEquals("tamed_chicken", persisted.stateSnapshot().roleId());
            assertNotNull(persisted.stateSnapshot().commandLinks());
            assertEquals(ownerId, persisted.stateSnapshot().commandLinks().getOwnerId());
            assertNotNull(persisted.stateSnapshot().npcName());
            assertEquals("Clucky", persisted.stateSnapshot().npcName().getName());
            assertNotNull(persisted.stateSnapshot().attachments());
            assertEquals("crest", persisted.stateSnapshot().attachments().getAttachmentIds().get("head"));
            assertNotNull(persisted.stateSnapshot().traits());
            assertEquals(1, persisted.stateSnapshot().traits().getTraitValues().length);
            assertEquals("friendly", persisted.stateSnapshot().traits().getTraitValues()[0].getId());
            assertEquals(capturedAtMs, persisted.stateSnapshot().capturedAtMs());
        }
    }
}
