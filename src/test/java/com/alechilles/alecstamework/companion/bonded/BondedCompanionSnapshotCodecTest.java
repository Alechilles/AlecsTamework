package com.alechilles.alecstamework.companion.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Full bonded snapshot preservation and profile-first presentation tests. */
class BondedCompanionSnapshotCodecTest {
    private static final UUID OWNER = UUID.fromString(
            "30000000-0000-0000-0000-000000000001"
    );
    private final BondedCompanionSnapshotCodec codec =
            new BondedCompanionSnapshotCodec();

    @Test
    void fullDragonRoundTripPreservesEveryGameplayFieldAndExtensionNamespace() {
        BondedCompanionSnapshot source = fullSnapshot(
                "Tamed_Dragon_Fire", "Ember", "Female", "horns",
                "{\"archetype\":\"fire\",\"bondLevel\":7}"
        );

        BondedCompanionSnapshot decoded = roundTrip(source);
        CoopResidentStateSnapshot state = decoded.fullState();

        assertEquals("Ember", state.npcName().getName());
        assertEquals(63.25D, state.healthPercent());
        assertEquals("horns", state.attachments().getAttachmentIds().get("head"));
        assertEquals("brave", state.traits().getTraitValues()[0].getId());
        assertEquals("flame-breath", state.talents().getPurchasedTalentIds()[0]);
        assertEquals(7, state.leveling().getLevel());
        assertEquals(-4_002L, state.needs().getLastUpdateMs());
        assertEquals(-4_001L, state.happiness().getLastUpdateMs());
        assertEquals(-4_004L, state.breeding().getCooldownUntilMs());
        assertEquals("Dragon_Horn", state.commandLinks().getToolIds()[0]);
        assertEquals("Female", state.lifeStage().getGender());
        assertEquals(
                "{\"archetype\":\"fire\",\"bondLevel\":7}",
                decoded.extensionData().get("hydragon:bond")
        );
    }

    @Test
    void miniwyvernRoundTripPreservesTheSameCompleteContract() {
        BondedCompanionSnapshot source = fullSnapshot(
                "Bonded_Miniwyvern_Storm", "Nimbus", "Male", "storm-crest",
                "{\"archetype\":\"storm\",\"ability\":\"dash\"}"
        );

        BondedCompanionSnapshot decoded = roundTrip(source);
        CoopResidentStateSnapshot state = decoded.fullState();

        assertEquals("Bonded_Miniwyvern_Storm", state.roleId());
        assertEquals("Nimbus", state.npcName().getName());
        assertEquals("storm-crest",
                state.attachments().getAttachmentIds().get("head"));
        assertEquals(7, state.leveling().getLevel());
        assertEquals("flame-breath",
                state.talents().getPurchasedTalentIds()[0]);
        assertEquals("{\"archetype\":\"storm\",\"ability\":\"dash\"}",
                decoded.extensionData().get("hydragon:bond"));
    }

    @Test
    void absentOptionalComponentsStayAbsentInTheEncodedSnapshot() {
        BondedCompanionSnapshot sparse = BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshot(
                        UUID.fromString("40000000-0000-0000-0000-000000000001"),
                        null, -1, "Bonded_Miniwyvern", null, null, null,
                        null, null, null, null, null, null, null, null,
                        null, null, -99L
                ),
                Map.of("hydragon:bond", "{}")
        );

        String encoded = codec.encode(sparse);
        BondedCompanionSnapshot decoded = codec.decode(encoded).snapshot();
        JsonObject fullState = JsonParser.parseString(encoded).getAsJsonObject()
                .getAsJsonObject("fullState");

        assertFalse(fullState.has("npcName"));
        assertFalse(fullState.has("needs"));
        assertFalse(fullState.has("attachments"));
        assertNull(decoded.fullState().npcName());
        assertNull(decoded.fullState().needs());
        assertNull(decoded.fullState().attachments());
    }

    @Test
    void laterStorePreservesPriorComponentsMissingFromTheNewCapture() {
        BondedCompanionSnapshot previous = fullSnapshot(
                "Tamed_Dragon_Fire", "Ember", "Female", "horns",
                "{\"ability\":\"flame\",\"charge\":2}"
        );
        CoopResidentStateSnapshot newerState = new CoopResidentStateSnapshot(
                UUID.fromString("40000000-0000-0000-0000-000000000002"),
                null, -1, "Tamed_Dragon_Fire",
                new TameworkCommandLinksComponent(OWNER, new String[] {"New_Horn"}),
                null, null, null, null, null, null, null, null, null, null,
                new TameworkAttachmentsComponent(
                        "attachments", Map.of("head", "new-horns")
                ),
                42.0D, -3_000L
        );
        BondedCompanionSnapshot newer = BondedCompanionSnapshot.of(
                newerState, Map.of("hydragon:appearance", "{\"skin\":\"ash\"}")
        );

        BondedCompanionSnapshot merged = previous.mergeForStore(newer);
        BondedCompanionSnapshot decoded = roundTrip(merged);

        assertEquals("Ember", decoded.fullState().npcName().getName());
        assertNotNull(decoded.fullState().needs());
        assertNotNull(decoded.fullState().happiness());
        assertEquals("new-horns",
                decoded.fullState().attachments().getAttachmentIds().get("head"));
        assertEquals("New_Horn",
                decoded.fullState().commandLinks().getToolIds()[0]);
        assertEquals(42.0D, decoded.fullState().healthPercent());
        assertEquals("{\"ability\":\"flame\",\"charge\":2}",
                decoded.extensionData().get("hydragon:bond"));
        assertEquals("{\"skin\":\"ash\"}",
                decoded.extensionData().get("hydragon:appearance"));
    }

    @Test
    void panelPresentationUsesSnapshotAndConfiguredRoleWithoutALiveNpc() {
        BondedCompanionSnapshot snapshot = fullSnapshot(
                "Bonded_Miniwyvern_Storm", "Nimbus", "Male", "storm-crest",
                "{\"ability\":\"dash\"}"
        );
        BondedCompanionSnapshotPresentationMapper mapper =
                new BondedCompanionSnapshotPresentationMapper(roleId ->
                        new BondedCompanionSnapshotPresentationMapper
                                .RolePresentation(
                                "Storm Miniwyvern", "Miniwyvern", null,
                                Map.of("variant", "storm")
                        )
                );

        BondedCompanionSnapshotPresentationMapper.Presentation presentation =
                mapper.map(snapshot);

        assertEquals("Nimbus", presentation.displayName());
        assertEquals("Miniwyvern", presentation.species());
        assertEquals("Male", presentation.gender());
        assertEquals("storm", presentation.data().get("variant"));
        assertEquals("7", presentation.data().get("level"));
        assertEquals("63.25", presentation.data().get("healthPercent"));
    }

    @Test
    void malformedOrUnsupportedBondedEnvelopeFailsExplicitly() {
        assertEquals(BondedCompanionSnapshotCodec.Status.NOT_FOUND,
                codec.decode(" ").status());
        assertEquals(BondedCompanionSnapshotCodec.Failure.INVALID_JSON,
                codec.decode("{").failure());
        assertEquals(BondedCompanionSnapshotCodec.Failure.UNSUPPORTED_VERSION,
                codec.decode("{\"version\":2}").failure());
        assertEquals(BondedCompanionSnapshotCodec.Failure.INVALID_FULL_STATE,
                codec.decode("{\"version\":1,\"fullState\":[]}").failure());
    }

    private BondedCompanionSnapshot roundTrip(BondedCompanionSnapshot source) {
        BondedCompanionSnapshotCodec.DecodeResult result =
                codec.decode(codec.encode(source));
        assertEquals(BondedCompanionSnapshotCodec.Status.FOUND, result.status());
        assertNotNull(result.snapshot());
        return result.snapshot();
    }

    private BondedCompanionSnapshot fullSnapshot(
            String roleId, String name, String gender,
            String attachment, String extensionJson
    ) {
        TameworkLifeStageComponent lifeStage = new TameworkLifeStageComponent();
        lifeStage.setStage("Adult");
        lifeStage.setBornAtMs(-4_006L);
        lifeStage.setAdultAtMs(-4_007L);
        lifeStage.setFullyGrownAtMs(-4_008L);
        lifeStage.setGender(gender);
        TameworkHappinessComponent.ActiveImpulse impulse =
                new TameworkHappinessComponent.ActiveImpulse();
        impulse.setKey("fed");
        impulse.setValue(0.25D);
        impulse.setExpiresAtMs(-4_009L);
        CoopResidentStateSnapshot state = new CoopResidentStateSnapshot(
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                null, -1, roleId,
                new TameworkCommandLinksComponent(
                        OWNER, new String[] {"Dragon_Horn"}
                ),
                new TameworkOwnerComponent(OWNER, "Owner"),
                new TameworkTamedComponent(true),
                new TameworkNpcNameComponent(
                        name, OWNER, -4_000L,
                        TameworkNpcNameComponent.NameSource.Player
                ),
                new TameworkHappinessComponent(
                        "happy", 0.75D, -4_001L,
                        new TameworkHappinessComponent.ActiveImpulse[] {impulse}
                ),
                new TameworkNeedsComponent(
                        "needs", 0.2D, 0.3D, 0.1D, 0.0D,
                        -4_002L, -4_003L
                ),
                new TameworkBreedingComponent(
                        "breed", 0.8D, -4_010L, true, true,
                        -4_004L, null, -4_005L, 4_000L
                ),
                new TameworkLevelingComponent(
                        "level", 7, 20.0D, 50.0D, -4_011L
                ),
                new TameworkTraitsComponent(
                        "traits", 112L,
                        new TameworkTraitsComponent.TraitValue[] {
                            new TameworkTraitsComponent.TraitValue("brave", 1.0D)
                        }
                ),
                new TameworkTalentsComponent(
                        "talents", 1, new String[] {"flame-breath"}
                ),
                lifeStage,
                new TameworkAttachmentsComponent(
                        "attachments", Map.of("head", attachment)
                ),
                63.25D,
                -4_100L
        );
        return BondedCompanionSnapshot.of(
                state, Map.of("hydragon:bond", extensionJson)
        );
    }
}
